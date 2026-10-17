package ar.changeo.payments.billing;

import ar.changeo.ChangeoApplication;
import ar.changeo.agreements.AgreementController;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.marketplace.MarketplaceController;
import ar.changeo.moderation.ModerationWorker;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.PaymentWorker;
import ar.changeo.payments.api.*;
import ar.changeo.payments.gateway.*;
import ar.changeo.subscriptions.*;
import ar.changeo.subscriptions.api.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {ChangeoApplication.class, SubscriptionBillingPostgresTest.Configuration.class}, properties = {
        "spring.datasource.url=${subscriptions.test.url}",
        "spring.flyway.schemas=sandbox", "spring.flyway.default-schema=sandbox", "spring.datasource.hikari.schema=sandbox",
        "spring.jpa.properties.hibernate.default_schema=sandbox", "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true",
        "changeo.synthetic-payments=true", "changeo.synthetic-subscriptions=true", "changeo.subscription-worker=false", "changeo.payments-worker=false",
        "changeo.moderation-worker=false", "changeo.notification-worker=false", "changeo.mfa-key=${subscriptions.test.root}/mfa.key",
        "changeo.files-root=${subscriptions.test.root}/artifacts", "changeo.payments-auth-key=${subscriptions.test.root}/payments/auth.key"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionBillingPostgresTest {
    @DynamicPropertySource static void privateConfiguration(DynamicPropertyRegistry properties) { SubscriptionTestPaths.localConfiguration(); }
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired BillingWorker worker;
    @Autowired BillingDispatchAccess dispatch;
    @MockitoSpyBean BillingStore store;
    @MockitoSpyBean PromotionBudgetAdapter promotionOwner;
    @MockitoSpyBean JobLoyaltyMoneyAccess loyaltyMoneyOwner;
    @Autowired SyntheticGateway gateway;
    @Autowired BillingOutcomeService outcomes;
    @Autowired SubscriptionBillingAccess billing;
    @Autowired ModerationWorker moderation;
    @Autowired PaymentWorker jobPayments;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired SubscriptionService subscriptions;
    @Autowired ProviderEntitlementAccess entitlements;
    @Autowired ParticipantIdentityAccess participants;
    @MockitoBean TokenDelivery delivery;
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    final List<MockHttpSession> sessions = new ArrayList<>();
    final Map<UUID, String> contacts = new HashMap<>();
    Properties operatorEnrollment;
    MockHttpSession operator;
    boolean changedPolicy;

    @TestConfiguration static class Configuration { @Bean @Primary TestClock subscriptionClock() { return new TestClock(); } }
    static class TestClock extends Clock {
        final AtomicReference<Instant> current = new AtomicReference<>(Instant.now().plusSeconds(Long.getLong("subscriptions.test.clock.offsetSeconds", 0L)).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        void advance(Duration duration) { current.updateAndGet(instant -> instant.plus(duration)); }
        @Override public Instant instant() { return current.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(SubscriptionTestPaths.database());
        Path enrollment = Path.of(".runtime/cfr-enrollment-20261006-1559.private");
        assertThat((Integer) Files.getAttribute(enrollment, "unix:uid")).isEqualTo(1000);
        assertThat((Integer) Files.getAttribute(enrollment, "unix:mode") & 0777).isEqualTo(0600);
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true;
        });
        clock.advance(Duration.ofSeconds(61)); operatorEnrollment = new Properties();
        long retainedCounter = jdbc.queryForObject("select coalesce(max(last_counter),0) from identity_admin_mfa", Long.class);
        Instant afterRetainedProof = Instant.ofEpochSecond(Math.multiplyExact(Math.addExact(retainedCounter, 2), 30));
        if (clock.instant().isBefore(afterRetainedProof)) { clock.advance(Duration.between(clock.instant(), afterRetainedProof)); }
        try (var input = Files.newBufferedReader(enrollment)) { operatorEnrollment.load(input); }
        operator = login(operatorEnrollment.getProperty("contact"), operatorEnrollment.getProperty("password"));
        operatorProof();
    }

    @AfterEach void cleanup() {
        if (changedPolicy) { publish(10000, 10); }
        if (operatorEnrollment != null) { operatorEnrollment.clear(); }
        for (var session : sessions) { if (!session.isInvalid()) { session.invalidate(); } }
        sessions.clear(); tokens.clear(); contacts.clear();
    }

    @Test void freeFoundingHasNoChargeAndRequiresNewConsentForPaid() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false);
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_root where provider_id=?", Integer.class, provider.id())).isZero();
        var view = view(provider); assertThat(view.entitlement()).isTrue(); assertThat(view.state()).isEqualTo("FREE_FOUNDING");
        assertThat(view.history()).isEmpty();
        mvc.perform(post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("subject", provider.id().toString()).param("version", Long.toString(view.version())).param("mode", "PAID").param("policyDigest", SubscriptionPolicy.example().digest()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_root where provider_id=?", Integer.class, provider.id())).isZero();
        clock.advance(Duration.ofDays(30)); assertThat(view(provider).entitlement()).isFalse();
    }

    @Test void fullServerSuccessAndCancellationRetainPaidThroughRights() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true);
        assertThat(view(provider).entitlement()).isFalse(); assertThat(view(provider).state()).isEqualTo("PAGO_PENDIENTE");
        assertThat(worker.processOne()).isTrue();
        var paid = view(provider); assertThat(paid.entitlement()).isTrue(); assertThat(paid.state()).isEqualTo("PAID");
        assertThat(paid.history()).singleElement().satisfies(period -> {
            assertThat(period.collectedMinor()).isEqualTo(10000); assertThat(period.feeSpentMinor()).isEqualTo(10); assertThat(period.confirmedUnreversed()).isTrue();
        });
        mvc.perform(post("/subscriptions/cancel").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("enrollment", paid.enrollment().orElseThrow().toString()).param("version", Long.toString(paid.version())).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
        var canceled = view(provider); assertThat(canceled.state()).isEqualTo("CANCELED"); assertThat(canceled.entitlement()).isTrue();
        assertThat(canceled.validUntil()).isEqualTo(paid.validUntil());
    }

    @Test void expiredProviderCannotCreateNewParticipationButCanCompleteSelectedWorkAndActAsCustomer() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false);
        var benefitEnd = view(provider).validUntil();
        var selected = negotiate(provider(), provider);
        var firstAssent = negotiate(provider(), provider, 0);
        var bilateralAssent = negotiate(provider(), provider, 1);
        var pending = publication(provider, ar.changeo.marketplace.PresenceType.OFFER, false);
        clock.advance(Duration.ofDays(31));
        provider = renewIdentity(provider);
        selected = new Journey(renewIdentity(selected.customer()), provider, selected.job(), selected.quote(), null);
        firstAssent = new Journey(renewIdentity(firstAssent.customer()), provider, firstAssent.job(), firstAssent.quote(), null);
        bilateralAssent = new Journey(renewIdentity(bilateralAssent.customer()), provider, bilateralAssent.job(), bilateralAssent.quote(), null);
        assertThat(view(provider).entitlement()).isFalse();
        assertThat(view(provider).state()).isEqualTo("EXPIRED");
        assertThat(view(provider).benefitEnd()).isEqualTo(benefitEnd);
        mvc.perform(get("/subscriptions").session(provider.session()).param("subject", provider.id().toString()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Fin del último período gratuito o de prueba")));
        for (var prospective : List.of(firstAssent, bilateralAssent)) {
            long version = jdbc.queryForObject("select version from agreements_quote where id=?", Long.class, prospective.quote().resourceId());
            mvc.perform(post("/agreements/accept").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                    .param("resource", prospective.quote().resourceId().toString()).param("revision", prospective.quote().revisionId().toString())
                    .param("version", Long.toString(version)).param("subject", provider.id().toString())).andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and side='PROVIDER'", Integer.class, prospective.quote().resourceId())).isZero();
        }
        mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("job", firstAssent.job().toString()).param("subject", provider.id().toString()).param("kind", "INITIAL")
                .param("scope", "Nuevo alcance vencido").param("serviceAmount", "123.45").param("deliveryAmount", "0").param("deliverables", "Una pieza")).andExpect(status().isOk());
        var composition = (AgreementController.Composition) provider.session().getAttribute("agreementComposition");
        mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString())
                .param("arrangement", "REMOTE").param("arrangementDescription", "Privada").param("completionDeadline", LocalDateTime.ofInstant(clock.instant().plusSeconds(86400), ZoneOffset.UTC).withSecond(0).withNano(0).toString())
                .param("feedbackRounds", "2").param("completionEvidence", "TEXT").param("evidenceDescription", "Una pieza").param("reviewWindowMinutes", "60").param("label", "GENERAL")).andExpect(status().isForbidden());
        mvc.perform(post("/marketplace/compose").session(provider.session()).with(csrf()).param("type", "OFFER").param("subject", provider.id().toString())
                .param("title", "Oferta vencida").param("body", "Servicio nuevo").param("label", "GENERAL")).andExpect(status().isOk());
        var draft = (MarketplaceController.Composition) provider.session().getAttribute("marketplaceComposition");
        String saved = mvc.perform(multipart("/marketplace/drafts").session(provider.session()).with(csrf()).param("stage", draft.stage().toString())
                .param("province", "Buenos Aires").param("area", "La Plata").param("modes", "REMOTE")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID savedResource = UUID.fromString(saved.substring(saved.lastIndexOf('/') + 1));
        UUID savedRevision = jdbc.queryForObject("select current_revision from marketplace_resource where id=?", UUID.class, savedResource);
        long savedVersion = jdbc.queryForObject("select version from marketplace_resource where id=?", Long.class, savedResource);
        mvc.perform(multipart("/marketplace/prepare").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("resource", savedResource.toString()).param("revision", savedRevision.toString()).param("version", Long.toString(savedVersion))
                .param("omitPreviousMedia", "true")).andExpect(status().isForbidden());
        decision(pending, "APPROVE"); for (int count = 0; count < 100 && moderation.processOne(); count++) {}
        assertThat(jdbc.queryForObject("select s.state from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, pending.revisionId())).isEqualTo("AWAITING_ELIGIBILITY");
        UUID thread = jdbc.queryForObject("select conversation_id from jobs_job where id=?", UUID.class, selected.job());
        UUID listing = jdbc.queryForObject("select listing_id from conversations_thread where id=?", UUID.class, thread);
        mvc.perform(post("/conversations/respond").session(selected.customer().session()).with(csrf()).param("listing", listing.toString())
                .param("subject", selected.customer().id().toString()).param("mode", "REMOTE")).andExpect(status().is3xxRedirection());
        var outsider = provider();
        mvc.perform(post("/conversations/respond").session(outsider.session()).with(csrf()).param("listing", listing.toString())
                .param("subject", outsider.id().toString()).param("mode", "REMOTE")).andExpect(status().isForbidden());
        mvc.perform(multipart("/conversations/messages").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString()).param("body", "Continuamos el trabajo seleccionado").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var request = publication(provider, ar.changeo.marketplace.PresenceType.REQUEST);
        mvc.perform(get("/listings/" + request.resourceId())).andExpect(status().isOk());
        var paid = paySelected(selected);
        assertThat(jdbc.queryForObject("select state from jobs_job where id=?", String.class, paid.job())).isEqualTo("COMPLETED");
        mvc.perform(multipart("/jobs/content").session(paid.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("job", paid.job().toString()).param("subject", paid.customer().id().toString()).param("kind", "REVIEW")
                .param("body", "Entrega completa según lo pactado").param("score", "5").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var review = jdbc.queryForObject("select id,current_revision from jobs_authored where job_id=? and kind='REVIEW'", (row, index) ->
                new ContentReference(SurfaceKind.COMPLETED_JOB_REVIEW, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), paid.job());
        mvc.perform(post("/jobs/submit").session(paid.customer().session()).with(csrf()).param("job", paid.job().toString()).param("subject", paid.customer().id().toString())
                .param("kind", review.ownerKind().name()).param("resource", review.resourceId().toString()).param("revision", review.revisionId().toString())).andExpect(status().is3xxRedirection());
        approve(review); recheck(provider, paid.job()); assertThat(progress(provider).verifiedMilestone()).isEqualTo(1);
        conservation();
    }

    @Test void foundingChoiceCannotReplaceAnActiveTrialOrItsGenuineConsent() throws Exception {
        var provider = provider(); enroll(provider, "TRIAL", true); var trial = view(provider);
        UUID consent = jdbc.queryForObject("select consent_id from subscriptions_enrollment where id=?", UUID.class, trial.enrollment().orElseThrow());
        mvc.perform(post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("subject", provider.id().toString()).param("version", Long.toString(trial.version())).param("mode", "FREE")
                .param("policyDigest", trial.policy().digest())).andExpect(status().isConflict());
        assertThat(view(provider).validUntil()).isEqualTo(trial.validUntil());
        assertThat(jdbc.queryForObject("select consent_id from subscriptions_enrollment where id=?", UUID.class, trial.enrollment().orElseThrow())).isEqualTo(consent);
        assertThat(jdbc.queryForObject("select founding_used from subscriptions_provider where provider_id=?", Boolean.class, provider.id())).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"TRIAL", "PAID"})
    void naturalTrialAndPaidGraceBoundariesDenyNewAuthorActionsAndRetainSelectedCompletion(String mode) throws Exception {
        var provider = provider(); enroll(provider, mode, true);
        if (mode.equals("PAID")) assertThat(worker.processOne()).isTrue();
        var original = view(provider); Instant end = original.validUntil().orElseThrow();
        var selected = negotiate(provider(), provider);
        var pending = publication(provider, ar.changeo.marketplace.PresenceType.OFFER, false, false);
        clock.advance(Duration.between(clock.instant(), end).minusSeconds(120));
        provider = renewIdentity(provider);
        clock.advance(Duration.between(clock.instant(), end).minusMillis(1));
        assertThat(view(provider).entitlement()).isTrue();
        clock.advance(Duration.ofMillis(2));
        if (mode.equals("PAID")) {
            var grace = view(provider); assertThat(grace.entitlement()).isTrue(); assertThat(grace.state()).isEqualTo("PAST_DUE");
            assertThat(grace.validUntil()).contains(original.policy().graceEnd(end));
            runAuthorPhase(provider, pending, "SUBMIT", true);
            clock.advance(Duration.between(clock.instant(), original.policy().graceEnd(end)).plusMillis(1));
            provider = renewIdentity(provider);
        }
        assertThat(view(provider).entitlement()).isFalse(); runAuthorPhase(provider, pending, "SUBMIT", false);
        assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?", UUID.class, pending.resourceId())).isNull();
        selected = new Journey(renewIdentity(selected.customer()), provider, selected.job(), selected.quote(), null);
        var completed = paySelected(selected);
        assertThat(jdbc.queryForObject("select state from jobs_job where id=?", String.class, completed.job())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select paid_minor from payments_root where job_id=?", Long.class, completed.job())).isEqualTo(12345);
        if (mode.equals("TRIAL")) assertThat(view(provider).history()).isEmpty();
        else assertThat(view(provider).history()).isEqualTo(original.history());
        conservation(); System.out.println("Actual natural period/grace loss with selected completion: " + mode);
    }

    @Test void realMonthlyRenewalKeepsDayThirtyOneAnchorAndNeverChargesMissedCalendarPeriods() throws Exception {
        ZoneId zone = SubscriptionPolicy.example().zone();
        YearMonth month = YearMonth.from(clock.instant().atZone(zone)).plusMonths(1);
        while (month.lengthOfMonth() != 31) month = month.plusMonths(1);
        Instant firstStart = month.atDay(31).atTime(12, 0).atZone(zone).toInstant();
        clock.advance(Duration.between(clock.instant(), firstStart));
        operator = login(operatorEnrollment.getProperty("contact"), operatorEnrollment.getProperty("password")); operatorProof();
        var provider = provider(); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var first = view(provider).history().getFirst();
        assertThat(first.start().atZone(zone).getDayOfMonth()).isEqualTo(31);
        assertThat(first.end()).isEqualTo(SubscriptionPolicy.example().nextEndpoint(first.start(), 31));
        clock.advance(Duration.between(clock.instant(), first.end()).plusMillis(1)); provider = renewIdentity(provider);
        boolean prepared = false; for (int count = 0; count < 20 && !prepared; count++) prepared = subscriptions.renewOne();
        assertThat(prepared).isTrue(); assertThat(worker.processOne()).isTrue();
        var renewed = view(provider); assertThat(renewed.history()).hasSize(2);
        var second = renewed.history().getLast(); assertThat(second.start()).isEqualTo(first.end());
        assertThat(second.end()).isEqualTo(SubscriptionPolicy.example().nextEndpoint(second.start(), 31));
        assertThat(second.end().atZone(zone).getDayOfMonth()).isEqualTo(Math.min(31, YearMonth.from(second.end().atZone(zone)).lengthOfMonth()));
        Instant missed = second.end().atZone(zone).plusMonths(3).toInstant();
        clock.advance(Duration.between(clock.instant(), missed)); provider = renewIdentity(provider);
        int roots = jdbc.queryForObject("select count(*) from payments_billing_root where provider_id=?", Integer.class, provider.id());
        for (int count = 0; count < 20; count++) subscriptions.renewOne();
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_root where provider_id=?", Integer.class, provider.id())).isEqualTo(roots);
        assertThat(view(provider).entitlement()).isFalse();
        Instant currentStart = clock.instant(); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var current = view(provider); assertThat(current.history()).hasSize(3); assertThat(current.history().getLast().start()).isEqualTo(currentStart);
        assertThat(current.history().subList(0, 2)).isEqualTo(renewed.history()); conservation();
    }

    @Test void caughtExpiredPublicationDenialRollsBackTheParticipatingOuterTransaction() throws Exception {
        var original = provider(); enroll(original, "FREE", false); var originalView = view(original);
        clock.advance(Duration.ofDays(31)); var provider = renewIdentity(original);
        var enrollment = originalView.enrollment().orElseThrow(); var sentinel = UUID.randomUUID();
        mvc.perform(post("/marketplace/compose").session(provider.session()).with(csrf()).param("type", "OFFER").param("subject", provider.id().toString())
                .param("title", "Oferta vencida").param("body", "Servicio privado").param("label", "GENERAL")).andExpect(status().isOk());
        var draft = (MarketplaceController.Composition) provider.session().getAttribute("marketplaceComposition");
        String saved = mvc.perform(multipart("/marketplace/drafts").session(provider.session()).with(csrf()).param("stage", draft.stage().toString())
                .param("province", "Buenos Aires").param("area", "La Plata").param("modes", "REMOTE")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource = UUID.fromString(saved.substring(saved.lastIndexOf('/') + 1));
        UUID revision = jdbc.queryForObject("select current_revision from marketplace_resource where id=?", UUID.class, resource);
        long version = jdbc.queryForObject("select version from marketplace_resource where id=?", Long.class, resource);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            try {
                mvc.perform(multipart("/marketplace/prepare").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                        .param("resource", resource.toString()).param("revision", revision.toString()).param("version", Long.toString(version))
                        .param("omitPreviousMedia", "true")).andExpect(status().isForbidden());
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            jdbc.update("insert into subscriptions_event values(?,?,?,?,?)", sentinel, enrollment, "ROLLBACK_SENTINEL", originalView.policy().version(), java.sql.Timestamp.from(clock.instant()));
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_event where id=?", Integer.class, sentinel)).isZero();
        assertThat(jdbc.queryForObject("select current_revision from marketplace_resource where id=?", UUID.class, resource)).isEqualTo(revision);
    }

    @ParameterizedTest @CsvSource({"SUBMIT,true", "SUBMIT,false", "RETRY,true", "RETRY,false", "ACTIVATE,true", "ACTIVATE,false"})
    void actualAuthorPublicationPhasesHoldCurrentFinancialEntitlementBeforeCanonical(String phase, boolean reversalFirst) throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var paid = view(provider); var charge = store.charges(billingBinding(paid.enrollment().orElseThrow())).getFirst();
        var reference = authorPhase(provider, phase);
        var observation = gateway.recordOutcome(charge.gatewayRequest(), GatewayObservation.Outcome.REVERSED, charge.gatewayRequest().amountMinor());
        var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var callbackPid = new AtomicInteger();
        try {
            if (reversalFirst) {
                doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    callbackPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    held.countDown(); await(release); return result;
                }).when(store).protect(eq(charge.request().binding()), eq(false));
                var callback = pool.submit(() -> { outcomes.record(observation); return true; });
                await(held); var author = pool.submit(() -> { runAuthorPhase(provider, reference, phase, false); return true; });
                awaitBlocked(callbackPid.get(), "payments_subscription_budget");
                assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?", UUID.class, reference.resourceId())).isNull();
                release.countDown(); assertThat(callback.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(author.get(5, TimeUnit.SECONDS)).isTrue();
                if (phase.equals("ACTIVATE")) assertThat(submissionState(reference)).isEqualTo("AWAITING_ELIGIBILITY");
                assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?", UUID.class, reference.resourceId())).isNull();
            } else {
                try (var canonical = dataSource.getConnection()) {
                    canonical.setAutoCommit(false); int canonicalPid;
                    try (var statement = canonical.prepareStatement("select id from sandbox.marketplace_resource where id=? for update")) {
                        statement.setObject(1, reference.resourceId()); statement.executeQuery().close();
                    }
                    try (var statement = canonical.createStatement(); var row = statement.executeQuery("select pg_backend_pid()")) { row.next(); canonicalPid = row.getInt(1); }
                    var author = pool.submit(() -> { runAuthorPhase(provider, reference, phase, true); return true; });
                    int authorPid = awaitBlocked(canonicalPid, "marketplace_resource");
                    var callback = pool.submit(() -> { outcomes.record(observation); return true; });
                    awaitBlocked(authorPid, "payments_subscription_budget");
                    assertThat(store.charge(charge.id()).state()).isEqualTo("SUCCESS");
                    canonical.commit(); assertThat(author.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(callback.get(5, TimeUnit.SECONDS)).isTrue();
                }
                if (phase.equals("ACTIVATE")) assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?", UUID.class, reference.resourceId())).isEqualTo(reference.revisionId());
            }
            assertThat(store.charge(charge.id()).state()).isEqualTo("REVERSED"); assertThat(view(provider).entitlement()).isFalse();
            conservation(); System.out.println("Actual author financial/canonical order: " + phase + "/" + (reversalFirst ? "reversal-first" : "author-first"));
        } finally { release.countDown(); reset(store); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private ContentReference authorPhase(Provider provider, String phase) throws Exception {
        var reference = publication(provider, ar.changeo.marketplace.PresenceType.OFFER, false, !phase.equals("SUBMIT"));
        if (phase.equals("RETRY")) {
            mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", "true")).andExpect(status().is3xxRedirection());
            approve(reference); assertThat(submissionState(reference)).isEqualTo("AWAITING_ELIGIBILITY");
            mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", "false")).andExpect(status().is3xxRedirection());
        } else if (phase.equals("ACTIVATE")) { decision(reference, "APPROVE"); }
        return reference;
    }

    @ParameterizedTest @CsvSource({"SUBMIT,true", "SUBMIT,false", "RETRY,true", "RETRY,false", "ACTIVATE,true", "ACTIVATE,false"})
    void actualAuthorPublicationPhasesProtectOriginalGuardianAndSubjectThroughCanonicalCommit(String phase, boolean guardianFirst) throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var provider = new Provider(child.id(), guardian.session()); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var enrollment = view(provider); var reference = authorPhase(provider, phase);
        var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var guardianPid = new AtomicInteger();
        try {
            if (guardianFirst) {
                var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    guardianPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    try { withdraw("GUARDIAN", provider, relationship, enrollment); } catch (Exception failure) { throw new IllegalStateException(failure); }
                    held.countDown(); await(release); return true;
                }));
                await(held); var author = pool.submit(() -> { runAuthorPhase(provider, reference, phase, false); return true; });
                awaitBlocked(guardianPid.get(), "identity_account");
                release.countDown(); assertThat(withdrawal.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(author.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?", UUID.class, reference.resourceId())).isNull();
            } else {
                try (var canonical = dataSource.getConnection()) {
                    canonical.setAutoCommit(false); int canonicalPid;
                    try (var statement = canonical.prepareStatement("select id from sandbox.marketplace_resource where id=? for update")) {
                        statement.setObject(1, reference.resourceId()); statement.executeQuery().close();
                    }
                    try (var statement = canonical.createStatement(); var row = statement.executeQuery("select pg_backend_pid()")) { row.next(); canonicalPid = row.getInt(1); }
                    var author = pool.submit(() -> { runAuthorPhase(provider, reference, phase, true); return true; });
                    int authorPid = awaitBlocked(canonicalPid, "marketplace_resource");
                    var withdrawal = pool.submit(() -> { withdraw("GUARDIAN", provider, relationship, enrollment); return true; });
                    awaitBlocked(authorPid, "identity_account");
                    assertThat(jdbc.queryForObject("select status from identity_guardian_link where id=?", String.class, relationship)).isEqualTo("VERIFIED");
                    canonical.commit(); assertThat(author.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(withdrawal.get(5, TimeUnit.SECONDS)).isTrue();
                }
                if (phase.equals("ACTIVATE")) assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?", UUID.class, reference.resourceId())).isEqualTo(reference.revisionId());
            }
            assertThat(jdbc.queryForObject("select status from identity_guardian_link where id=?", String.class, relationship)).isEqualTo("REVOKED");
            assertThat(store.charges(billingBinding(enrollment.enrollment().orElseThrow())).getFirst().state()).isEqualTo("SUCCESS");
            conservation(); System.out.println("Actual author guardian/canonical order: " + phase + "/" + (guardianFirst ? "guardian-first" : "author-first"));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private String submissionState(ContentReference reference) {
        return jdbc.queryForObject("select s.state from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, reference.revisionId());
    }

    private void runAuthorPhase(Provider provider, ContentReference reference, String phase, boolean allowed) throws Exception {
        if (phase.equals("ACTIVATE")) {
            for (int count = 0; count < 100 && moderation.processOne(); count++) {}
            assertThat(submissionState(reference)).isEqualTo(allowed ? "APPROVED" : "AWAITING_ELIGIBILITY");
            return;
        }
        var request = post(phase.equals("SUBMIT") ? "/marketplace/submit" : "/marketplace/retry").session(provider.session()).with(csrf())
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString());
        if (phase.equals("RETRY")) {
            long generation = jdbc.queryForObject("select s.generation from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", Long.class, reference.revisionId());
            request.param("command", UUID.randomUUID().toString()).param("generation", Long.toString(generation));
        }
        mvc.perform(request).andExpect(allowed ? status().is3xxRedirection() : status().isForbidden());
    }

    @Test void newGuardianAndChildNeverInheritOriginalBillingHistoryCancellationOrWallet() throws Exception {
        var originalGuardian = provider(); var child = minorProvider(); UUID relationship = link(originalGuardian, child);
        var original = new Provider(child.id(), originalGuardian.session()); enroll(original, "FREE", false); enroll(original, "PAID", true);
        assertThat(worker.processOne()).isTrue(); var old = view(original); UUID oldEnrollment = old.enrollment().orElseThrow();
        var before = billingBinding(oldEnrollment); assertThat(old.history()).hasSize(1); assertThat(old.validPaid()).isTrue();
        long linkVersion = jdbc.queryForObject("select version from identity_guardian_link where id=?", Long.class, relationship);
        mvc.perform(post("/account/guardian/" + relationship + "/revoke").session(originalGuardian.session()).with(csrf()).param("version", Long.toString(linkVersion))).andExpect(status().is3xxRedirection());
        var newGuardian = provider(); link(newGuardian, child); var current = new Provider(child.id(), newGuardian.session());
        for (var session : List.of(newGuardian.session(), child.session())) {
            mvc.perform(get("/subscriptions").session(session).param("subject", child.id().toString()).param("enrollment", oldEnrollment.toString()))
                    .andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(originalGuardian.id().toString()))));
            mvc.perform(post("/subscriptions/cancel").session(session).with(csrf()).param("command", UUID.randomUUID().toString())
                    .param("enrollment", oldEnrollment.toString()).param("version", Long.toString(old.version())).param("subject", child.id().toString())).andExpect(status().isConflict());
        }
        var empty = view(current); assertThat(empty.enrollment()).isEmpty(); assertThat(empty.history()).isEmpty(); assertThat(empty.creditMinor()).isZero();
        assertThat(empty.foundingAvailable()).isFalse();
        mvc.perform(post("/subscriptions/enroll").session(newGuardian.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("subject", child.id().toString()).param("version", "0").param("mode", "FREE").param("policyDigest", empty.policy().digest())).andExpect(status().isConflict());
        enroll(current, "TRIAL", true); var fresh = view(current); assertThat(fresh.enrollment()).hasValueSatisfying(id -> assertThat(id).isNotEqualTo(oldEnrollment));
        assertThat(fresh.history()).isEmpty(); assertThat(fresh.recurringAuthorized()).isTrue(); assertThat(fresh.state()).isEqualTo("TRIAL");
        assertThat(fresh.creditMinor()).isZero(); assertThat(billingBinding(oldEnrollment)).isEqualTo(before);
        var freshBinding = billingBinding(fresh.enrollment().orElseThrow()); assertThat(freshBinding.originalPayer().value()).isEqualTo(newGuardian.id());
        assertThat(freshBinding.generation()).isEqualTo(before.generation() + 1);
        var retained = (SubscriptionService.View) mvc.perform(get("/subscriptions").session(originalGuardian.session()).param("subject", child.id().toString())
                .param("enrollment", oldEnrollment.toString())).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("subscription");
        assertThat(retained.canEnroll()).isFalse(); assertThat(retained.history()).isEqualTo(old.history());
        mvc.perform(post("/subscriptions/cancel").session(originalGuardian.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("enrollment", oldEnrollment.toString()).param("version", Long.toString(retained.version())).param("subject", child.id().toString())).andExpect(status().is3xxRedirection());
        assertThat(view(current).enrollment()).isEqualTo(fresh.enrollment());
        mvc.perform(post("/subscriptions/cancel").session(newGuardian.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("enrollment", fresh.enrollment().orElseThrow().toString()).param("version", Long.toString(fresh.version())).param("subject", child.id().toString())).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, child.id())).isZero(); conservation();
    }

    private BillingBinding billingBinding(UUID enrollment) {
        return jdbc.queryForObject("select generation,provider_id,original_actor,original_payer from subscriptions_enrollment where id=?", (row, index) ->
                new BillingBinding(enrollment, row.getLong(1), new SubjectId(row.getObject(2, UUID.class)), new ActorId(row.getObject(3, UUID.class)), new AccountId(row.getObject(4, UUID.class))), enrollment);
    }

    @ParameterizedTest @CsvSource({"CANCEL,true", "CANCEL,false", "RESTRICT,true", "RESTRICT,false", "GUARDIAN,true", "GUARDIAN,false"})
    void actualDispatchSerializesCurrentWithdrawalThroughItsIrreversibleCommit(String mutation, boolean changeFirst) throws Exception {
        Provider provider; UUID relationship;
        if (mutation.equals("GUARDIAN")) {
            var guardian = provider(); var child = minorProvider(); relationship = link(guardian, child); provider = new Provider(child.id(), guardian.session());
        } else { provider = provider(); relationship = null; }
        enroll(provider, "PAID", true); var enrollment = view(provider); operatorProof(); var claim = worker.claim().orElseThrow();
        var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        try {
            if (changeFirst) {
                var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    try { withdraw(mutation, provider, relationship, enrollment); } catch (Exception failure) { throw new IllegalStateException(failure); }
                    ready.countDown(); await(release); return true;
                }));
                await(ready); var second = pool.submit(() -> { worker.process(claim); return true; });
                awaitBlocked(firstPid.get(), "identity_account"); assertThat(store.charge(claim.operationId()).dispatchedAt()).isEmpty();
                release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(store.charge(claim.operationId()).state()).isEqualTo("CANCELLED");
                assertThat(store.charge(claim.operationId()).collected()).isZero();
                assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=?", Integer.class, claim.rootId())).isZero();
            } else {
                try (var blocker = dataSource.getConnection()) {
                    blocker.setAutoCommit(false); int blockerPid;
                    try (var statement = blocker.prepareStatement("select id from sandbox.payments_promotion_wallet where id=? for update")) {
                        statement.setObject(1, BillingStore.walletId(claim.request().binding())); statement.executeQuery().close();
                    }
                    try (var statement = blocker.createStatement(); var row = statement.executeQuery("select pg_backend_pid()")) { row.next(); blockerPid = row.getInt(1); }
                    var first = pool.submit(() -> { dispatch.authorize(claim); return true; });
                    int authorizerPid = awaitBlocked(blockerPid, "payments_promotion_wallet");
                    var second = pool.submit(() -> { withdraw(mutation, provider, relationship, enrollment); return true; });
                    awaitBlocked(authorizerPid, "identity_account"); assertThat(store.charge(claim.operationId()).dispatchedAt()).isEmpty();
                    blocker.commit(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
                }
                var charge = store.charge(claim.operationId()); assertThat(charge.dispatchedAt()).isPresent();
                outcomes.record(gateway.execute(charge.gatewayRequest()));
                assertThat(store.charge(claim.operationId()).state()).isEqualTo("SUCCESS");
                assertThat(store.charge(claim.operationId()).collected()).isEqualTo(10000);
            }
            if (mutation.equals("GUARDIAN")) {
                var replacement = provider(); var child = new Provider(provider.id(), provider.session()); link(replacement, child);
                mvc.perform(get("/subscriptions").session(replacement.session()).param("subject", provider.id().toString()).param("enrollment", enrollment.enrollment().orElseThrow().toString())).andExpect(status().isConflict());
            }
            conservation(); System.out.println("Actual PostgreSQL dispatch authority order: " + mutation + "/" + (changeFirst ? "withdrawal-first" : "dispatch-first"));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private void withdraw(String mutation, Provider provider, UUID relationship, SubscriptionService.View enrollment) throws Exception {
        switch (mutation) {
            case "CANCEL" -> mvc.perform(post("/subscriptions/cancel").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                    .param("enrollment", enrollment.enrollment().orElseThrow().toString()).param("version", Long.toString(enrollment.version())).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
            case "RESTRICT" -> mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", "true")).andExpect(status().is3xxRedirection());
            case "GUARDIAN" -> mvc.perform(post("/account/guardian/" + relationship + "/revoke").session(provider.session()).with(csrf())
                    .param("version", Long.toString(jdbc.queryForObject("select version from identity_guardian_link where id=?", Long.class, relationship)))).andExpect(status().is3xxRedirection());
            default -> throw new IllegalArgumentException("Mutación de autoridad desconocida");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"QUEUED", "FAILED_NO_EFFECT", "REVERSED", "SUCCESS"})
    void invalidQualificationRestoresUnusedCreditOrRecordsConsumedDebtFromSavedTruth(String terminal) throws Exception {
        var provider = provider(); enroll(provider, "FREE", false);
        var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job()); recheck(provider, second.job()); enrollCredit(provider);
        var claim = worker.claim().orElseThrow();
        if (!terminal.equals("QUEUED")) {
            dispatch.authorize(claim); outcomes.record(gateway.query(store.charge(claim.operationId()).gatewayRequest()));
            assertThat(store.charge(claim.operationId()).state()).isEqualTo("UNKNOWN");
        }
        recall(first.finalDelivery()); recheck(provider, first.job());
        if (terminal.equals("QUEUED")) {
            worker.process(claim); assertThat(store.charge(claim.operationId()).state()).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where stable_key=?", Integer.class, claim.stableKey())).isZero();
        } else {
            assertThat(jdbc.queryForObject("select reserved_minor from payments_promotion_wallet where provider_id=?", Long.class, provider.id())).isEqualTo(100);
            var request = store.charge(claim.operationId()).gatewayRequest();
            if (terminal.equals("REVERSED")) { outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.PARTIAL, 2500)); }
            long amount = terminal.equals("SUCCESS") ? 9900 : terminal.equals("REVERSED") ? 2500 : 0;
            var truth = gateway.recordOutcome(request, GatewayObservation.Outcome.valueOf(terminal), amount); outcomes.record(truth); outcomes.record(truth);
        }
        assertThat(jdbc.queryForObject("select reserved_minor from payments_promotion_wallet where provider_id=?", Long.class, provider.id())).isZero();
        var current = progress(provider); assertThat(current.availableCreditMinor()).isZero();
        assertThat(current.debtMinor()).isEqualTo(terminal.equals("SUCCESS") ? 100 : 0);
        assertThat(jdbc.queryForObject("select returned_minor from payments_promotion_wallet where provider_id=?", Long.class, provider.id())).isEqualTo(terminal.equals("SUCCESS") ? 0 : 100);
        assertThat(view(provider).entitlement()).isTrue(); assertThat(view(provider).validUntil()).isPresent();
        conservation();
    }

    @Test void billingReversalKeepsConsumedCreditWithoutDebtAndIndependentFoundingTime() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false); var foundingEnd = view(provider).validUntil();
        var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job()); recheck(provider, second.job()); enrollCredit(provider); assertThat(worker.processOne()).isTrue();
        var charge = store.charge(view(provider).history().getFirst().operationId());
        var reversed = gateway.recordOutcome(charge.gatewayRequest(), GatewayObservation.Outcome.REVERSED, 9900); outcomes.record(reversed); outcomes.record(reversed);
        assertThat(view(provider).entitlement()).isTrue(); assertThat(view(provider).validUntil()).isEqualTo(foundingEnd);
        assertThat(progress(provider).debtMinor()).isZero(); assertThat(progress(provider).availableCreditMinor()).isZero();
        assertThat(jdbc.queryForObject("select redeemed_minor from payments_promotion_wallet where provider_id=?", Long.class, provider.id())).isEqualTo(100);
        assertThat(view(provider).returnedPurchase()).contains(charge.id()); conservation();
    }

    @Test void financialCallbackUsesOnlyBillingLocksAndFreshEntitlementSeesAppliedTruthAfterIdentityProtection() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var period = view(provider).history().getFirst(); var charge = store.charge(period.operationId());
        var discovery = entitlements.discover(Set.of(new SubjectId(provider.id())));
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> protectedCall = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                participants.requireForUpdate(new PermissionRequest(Optional.of(new ActorId(provider.id())), Optional.of(new SubjectId(provider.id())), Capability.NEW_PROVIDER_PARTICIPATION, Optional.empty()), discovery.requiredAccountIds());
                held.countDown(); await(release);
                assertThatThrownBy(() -> entitlements.requireNewParticipationForUpdate(discovery, discovery.requiredAccountIds())).isInstanceOf(SubscriptionFailure.class);
            }));
            await(held);
            outcomes.record(gateway.recordOutcome(charge.gatewayRequest(), GatewayObservation.Outcome.REVERSED, 10000));
            release.countDown(); assertThatThrownBy(() -> protectedCall.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(view(provider).entitlement()).isFalse(); conservation();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void cancelBeforeDispatchTombstonesAndRestoresUnusedFee() throws Exception {
        var provider = provider(); long before = fee("available_minor"); enroll(provider, "PAID", true);
        var claim = worker.claim().orElseThrow(); var pending = view(provider);
        mvc.perform(post("/subscriptions/cancel").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("enrollment", pending.enrollment().orElseThrow().toString()).param("version", Long.toString(pending.version())).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
        worker.process(claim);
        assertThat(store.charge(claim.operationId()).state()).isEqualTo("CANCELLED"); assertThat(fee("available_minor")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where stable_key=?", Integer.class, claim.stableKey())).isZero();
        assertThat(view(provider).entitlement()).isFalse();
    }

    @Test void actualPartialThenSuccessChargesMissingCashAndSpendsFeeOnce() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow(); dispatch.authorize(claim);
        var request = store.charge(claim.operationId()).gatewayRequest();
        var partial = gateway.recordOutcome(request, GatewayObservation.Outcome.PARTIAL, 2500); outcomes.record(partial);
        assertThat(view(provider).entitlement()).isFalse(); assertThat(store.charge(claim.operationId()).collected()).isEqualTo(2500);
        var success = gateway.recordOutcome(request, GatewayObservation.Outcome.SUCCESS, 10000); outcomes.record(success); outcomes.record(partial); outcomes.record(success);
        var charge = store.charge(claim.operationId()); assertThat(charge.collected()).isEqualTo(10000); assertThat(charge.feeSpent()).isTrue();
        assertThat(view(provider).entitlement()).isTrue();
        assertThat(jdbc.queryForObject("select coalesce(sum(signed_minor),0) from payments_subscription_ledger where operation_id=? and account='FEE_EXPENSE'", Long.class, claim.operationId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("select coalesce(sum(signed_minor),0) from payments_subscription_ledger where operation_id=? and account='BILLING_CASH'", Long.class, claim.operationId())).isEqualTo(10000);
    }

    @Test void actualTerminalReturnBeforeSuccessKeepsSpentFeeAndNoPaidPeriod() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow(); dispatch.authorize(claim);
        var request = store.charge(claim.operationId()).gatewayRequest();
        outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.PARTIAL, 2500));
        var returned = gateway.recordOutcome(request, GatewayObservation.Outcome.REVERSED, 2500); outcomes.record(returned); outcomes.record(returned);
        var charge = store.charge(claim.operationId()); assertThat(charge.state()).isEqualTo("REVERSED"); assertThat(charge.collected()).isEqualTo(2500); assertThat(charge.returned()).isEqualTo(2500);
        assertThat(charge.feeSpent()).isTrue(); assertThat(charge.restored()).isTrue(); assertThat(view(provider).entitlement()).isFalse();
        assertThat(jdbc.queryForObject("select coalesce(sum(signed_minor),0) from payments_subscription_ledger where operation_id=? and account='BILLING_CASH'", Long.class, claim.operationId())).isZero();
    }

    @Test void actualGatewayRejectsLowerReturnAndLeavesAllMoneyUnchanged() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var period = view(provider).history().getFirst(); var before = store.charge(period.operationId());
        long walletVersion = store.wallet(BillingStore.walletId(before.request().binding()), false).version();
        long feeSpent = fee("spent_minor"); int journal = jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class);
        assertThatThrownBy(() -> gateway.recordOutcome(before.gatewayRequest(), GatewayObservation.Outcome.REVERSED, 9000)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.charge(before.id())).isEqualTo(before); assertThat(store.wallet(BillingStore.walletId(before.request().binding()), false).version()).isEqualTo(walletVersion);
        assertThat(fee("spent_minor")).isEqualTo(feeSpent); assertThat(jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class)).isEqualTo(journal);
        var reversed = gateway.recordOutcome(before.gatewayRequest(), GatewayObservation.Outcome.REVERSED, 10000); outcomes.record(reversed);
        assertThat(view(provider).entitlement()).isFalse(); assertThat(store.charge(before.id()).returned()).isEqualTo(10000);
    }

    @Test void noEffectRestoresOnceAndUnknownNeverRestores() throws Exception {
        var provider = provider(); long available = fee("available_minor"); enroll(provider, "PAID", true);
        var claim = worker.claim().orElseThrow(); dispatch.authorize(claim);
        var request = store.charge(claim.operationId()).gatewayRequest(); outcomes.record(gateway.query(request));
        assertThat(store.charge(claim.operationId()).state()).isEqualTo("UNKNOWN"); assertThat(fee("available_minor")).isEqualTo(available - 10);
        var noEffect = gateway.recordOutcome(request, GatewayObservation.Outcome.FAILED_NO_EFFECT, 0); outcomes.record(noEffect); outcomes.record(noEffect);
        assertThat(fee("available_minor")).isEqualTo(available); assertThat(store.charge(claim.operationId()).state()).isEqualTo("FAILED_NO_EFFECT");
        assertThat(view(provider).entitlement()).isFalse();
    }

    @Test void financialObservationRejectsOuterTransaction() {
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.execute(status -> { worker.claim(); return null; })).isInstanceOf(IllegalStateException.class);
    }

    @Test void twoGenuineCompletedReleasedPaidJobsAwardThenRedeemOnlySubscriptionCredit() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false);
        var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job()); assertThat(progress(provider).verifiedMilestone()).isEqualTo(1);
        assertThat(view(provider).creditMinor()).isZero();
        recheck(provider, second.job()); var earned = progress(provider);
        assertThat(earned.verifiedMilestone()).isEqualTo(2); assertThat(earned.availableCreditMinor()).isEqualTo(100);
        long walletVersion = jdbc.queryForObject("select version from payments_promotion_wallet where provider_id=?", Long.class, provider.id());
        long principal = jdbc.queryForObject("select sum(funded_minor) from payments_root where provider_id=?", Long.class, provider.id());
        enrollCredit(provider); assertThat(worker.processOne()).isTrue();
        var paid = view(provider); assertThat(paid.entitlement()).isTrue(); assertThat(paid.creditMinor()).isZero();
        assertThat(paid.history()).singleElement().satisfies(period -> { assertThat(period.grossMinor()).isEqualTo(10000); assertThat(period.promotionMinor()).isEqualTo(100); assertThat(period.collectedMinor()).isEqualTo(9900); });
        assertThat(jdbc.queryForObject("select sum(funded_minor) from payments_root where provider_id=?", Long.class, provider.id())).isEqualTo(principal);
        assertThat(jdbc.queryForObject("select redeemed_minor from payments_promotion_wallet where provider_id=?", Long.class, provider.id())).isEqualTo(100);
        assertThat(jdbc.queryForObject("select version from payments_promotion_wallet where provider_id=?", Long.class, provider.id())).isEqualTo(walletVersion + 2);
        recheck(provider, second.job()); assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, provider.id())).isEqualTo(1);
    }

    @Test void retainedOriginalGuardianCorrectsRedeemedAwardAndRestoresSameFactsWithoutNewCredit() throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var original = new Provider(child.id(), guardian.session()); enroll(original, "FREE", false);
        var first = paidJob(provider(), original); var second = paidJob(provider(), original);
        recheck(original, first.job()); recheck(original, second.job());
        UUID enrollment = view(original).enrollment().orElseThrow();
        assertThat(ownProgress(original, enrollment).availableCreditMinor()).isEqualTo(100);
        mvc.perform(post("/subscriptions/achievements").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("visible", "true")).andExpect(status().is3xxRedirection());
        enrollCredit(original); assertThat(worker.processOne()).isTrue();
        cancelOwnedEnrollment(original, enrollment);
        long backingSpent = jdbc.queryForObject("select spent_minor from payments_subscription_budget where kind='PROMOTION'", Long.class);
        var oldBinding = billingBinding(enrollment); var oldMoney = view(original).history();
        revoke(guardian, relationship);
        var successor = provider(); UUID replacement = link(successor, child); var current = new Provider(child.id(), successor.session()); enroll(current, "TRIAL", true);
        UUID currentEnrollment = view(current).enrollment().orElseThrow();
        cancelOwnedEnrollment(current, currentEnrollment);
        assertThat(jdbc.queryForObject("select public_achievements from subscriptions_provider where provider_id=?", Boolean.class, child.id())).isFalse();
        var observed = ownProgress(original, enrollment);
        assertThat(observed.retained()).isTrue(); assertThat(observed.verifiedMilestone()).isZero(); assertThat(observed.pendingCorrection()).isTrue();
        assertThat(observed.history().redeemedMinor()).isEqualTo(100); assertThat(observed.history().debtMinor()).isZero(); assertThat(observed.candidates().jobIds()).isEmpty();
        String page = mvc.perform(get("/subscriptions/progress").session(guardian.session()).param("subject", child.id().toString()).param("enrollment", enrollment.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains(enrollment.toString()).doesNotContain("Revisar trabajos propios", "Visibilidad opcional", first.job().toString(), second.job().toString(), successor.id().toString());
        for (var session : List.of(successor.session(), child.session(), provider().session())) {
            mvc.perform(get("/subscriptions/progress").session(session).param("subject", child.id().toString()).param("enrollment", enrollment.toString())).andExpect(status().isConflict());
            mvc.perform(post("/subscriptions/recheck").session(session).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString())).andExpect(status().isConflict());
            mvc.perform(post("/subscriptions/appeal").session(session).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("reason", "PROMOTION_DEBT")).andExpect(status().isConflict());
        }
        mvc.perform(get("/subscriptions/progress").param("subject", child.id().toString()).param("enrollment", enrollment.toString())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/subscriptions/recheck").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("candidate", first.job().toString())).andExpect(status().isConflict());
        mvc.perform(get("/subscriptions/progress").session(guardian.session()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("after", first.job().toString()).param("cohortVersion", "0")).andExpect(status().isConflict());
        mvc.perform(post("/subscriptions/achievements").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("visible", "true")).andExpect(status().isForbidden());
        mvc.perform(post("/subscriptions/enroll").session(guardian.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", child.id().toString()).param("version", "0").param("mode", "PAID").param("policyDigest", view(current).policy().digest()).param("recurringConsent", "true")).andExpect(status().isForbidden());
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(post("/subscriptions/appeal").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("reason", "PROMOTION_DEBT")).andExpect(redirectedUrl("/subscriptions/progress?subject=" + child.id() + "&enrollment=" + enrollment));
        }
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_appeal where enrollment_id=?", Integer.class, enrollment)).isEqualTo(1);
        retainedRecheck(original, enrollment); var corrected = ownProgress(original, enrollment);
        assertThat(corrected.history().debtMinor()).isEqualTo(100); assertThat(corrected.history().redeemedMinor()).isEqualTo(100); assertThat(corrected.pendingCorrection()).isFalse();
        assertThat(corrected.history().corrections()).singleElement().satisfies(event -> assertThat(event.kind()).isEqualTo(PromotionBudgetAccess.CorrectionKind.DEBT));
        long version = jdbc.queryForObject("select version from payments_promotion_wallet where id=?", Long.class, BillingStore.walletId(oldBinding));
        retainedRecheck(original, enrollment);
        assertThat(jdbc.queryForObject("select version from payments_promotion_wallet where id=?", Long.class, BillingStore.walletId(oldBinding))).isEqualTo(version);
        assertThat(ownProgress(original, enrollment).appeals().events()).hasSize(2);
        mvc.perform(get("/jobs/" + first.job()).session(guardian.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
        assertThat(ownProgress(current, currentEnrollment).history().award()).isEmpty();
        var successorFirst = paidJob(provider(), current); var successorSecond = paidJob(provider(), current);
        recheck(current, successorFirst.job()); recheck(current, successorSecond.job());
        assertThat(ownProgress(current, currentEnrollment).reason()).isEqualTo("CAP_USED");
        assertThat(ownProgress(current, currentEnrollment).history().award()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_evidence where provider_id=?", Integer.class, child.id())).isEqualTo(2);
        Set<UUID> originalJobs = jdbc.queryForObject("select job_ids from subscriptions_evidence where provider_id=? and billing_payer=?", (row, index) -> Set.of((UUID[]) row.getArray(1).getArray()), child.id(), guardian.id());
        assertThat(originalJobs).containsExactlyInAnyOrder(first.job(), second.job());
        revoke(successor, replacement); link(guardian, child);
        var restoredObservation = ownProgress(original, enrollment); assertThat(restoredObservation.retained()).isTrue(); assertThat(restoredObservation.verifiedMilestone()).isEqualTo(2); assertThat(restoredObservation.pendingCorrection()).isTrue();
        retainedRecheck(original, enrollment); var restored = ownProgress(original, enrollment);
        assertThat(restored.history().debtMinor()).isZero(); assertThat(restored.history().redeemedMinor()).isEqualTo(100); assertThat(restored.history().outstandingMinor()).isZero(); assertThat(restored.availableCreditMinor()).isZero();
        assertThat(restored.history().corrections()).extracting(PromotionBudgetAccess.Correction::kind).containsExactly(PromotionBudgetAccess.CorrectionKind.DEBT_CLEARED, PromotionBudgetAccess.CorrectionKind.DEBT);
        assertThat(jdbc.queryForObject("select spent_minor from payments_subscription_budget where kind='PROMOTION'", Long.class)).isEqualTo(backingSpent);
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, child.id())).isEqualTo(1);
        var retained = (SubscriptionService.View) mvc.perform(get("/subscriptions").session(guardian.session()).param("subject", child.id().toString()).param("enrollment", enrollment.toString())).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("subscription");
        assertThat(retained.history()).isEqualTo(oldMoney); assertThat(billingBinding(enrollment)).isEqualTo(oldBinding); assertThat(retained.canEnroll()).isFalse(); conservation();
    }

    @Test void retainedEmptyEnrollmentRecheckAndAppealNeverBootstrapEvidenceAwardOrFinancialState() throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var original = new Provider(child.id(), guardian.session()); enroll(original, "FREE", false); UUID enrollment = view(original).enrollment().orElseThrow();
        revoke(guardian, relationship);
        int wallets = jdbc.queryForObject("select count(*) from payments_promotion_wallet", Integer.class);
        int roots = jdbc.queryForObject("select count(*) from payments_billing_root", Integer.class);
        int journal = jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class);
        long available = jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class);
        retainedRecheck(original, enrollment); retainedRecheck(original, enrollment);
        var result = ownProgress(original, enrollment); assertThat(result.retained()).isTrue(); assertThat(result.history().award()).isEmpty(); assertThat(result.history().corrections()).isEmpty();
        mvc.perform(post("/subscriptions/appeal").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("reason", "QUALIFICATION")).andExpect(status().is3xxRedirection());
        retainedRecheck(original, enrollment); assertThat(ownProgress(original, enrollment).appeals().events()).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_wallet", Integer.class)).isEqualTo(wallets);
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_root", Integer.class)).isEqualTo(roots);
        assertThat(jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class)).isEqualTo(journal);
        assertThat(jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class)).isEqualTo(available);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_evidence where provider_id=? and billing_payer=?", Integer.class, child.id(), guardian.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, child.id())).isZero(); conservation();
    }

    @Test void projectedCorrectionCursorSupportsImmediateContinuationAndRejectsStaleOrOtherWallet() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false);
        var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job()); recheck(provider, second.job()); enrollCredit(provider); assertThat(worker.processOne()).isTrue();
        UUID enrollment = view(provider).enrollment().orElseThrow();
        for (int count = 0; count < 22; count++) {
            clock.advance(Duration.ofSeconds(61)); boolean restricted = count % 2 == 0;
            operatorProof();
            mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", Boolean.toString(restricted))).andExpect(status().is3xxRedirection());
            retainedRecheck(provider, enrollment);
        }
        var history = new AtomicReference<PromotionBudgetAccess.PromotionHistory>();
        PromotionBudgetAdapter ownerTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(promotionOwner);
        doAnswer(invocation -> { var value = (PromotionBudgetAccess.PromotionHistory) invocation.callRealMethod(); history.set(value); return value; })
                .when(ownerTarget).requireHistoryForUpdate(any(), any());
        try {
            clock.advance(Duration.ofSeconds(61));
            operatorProof();
            mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", "true")).andExpect(status().is3xxRedirection());
            retainedRecheck(provider, enrollment);
        } finally { reset(ownerTarget); }
        var cursor = history.get().next().orElseThrow(); assertThat(history.get().corrections()).hasSize(20);
        assertThat(cursor.walletVersion()).isEqualTo(jdbc.queryForObject("select version from payments_promotion_wallet where id=?", Long.class, cursor.walletId()));
        var next = correctionPage(provider, enrollment, cursor, 200);
        assertThat(next.history().corrections()).hasSize(3); assertThat(next.history().next()).isEmpty();
        assertThat(next.history().corrections()).extracting(PromotionBudgetAccess.Correction::eventId).doesNotContainAnyElementsOf(history.get().corrections().stream().map(PromotionBudgetAccess.Correction::eventId).toList());
        operatorProof(); var stranger = provider(); enroll(stranger, "FREE", false); UUID strangerEnrollment = view(stranger).enrollment().orElseThrow();
        correctionPage(stranger, strangerEnrollment, cursor, 409);
        clock.advance(Duration.ofSeconds(61));
        operatorProof();
        mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", "false")).andExpect(status().is3xxRedirection());
        retainedRecheck(provider, enrollment); correctionPage(provider, enrollment, cursor, 409); conservation();
    }

    private SubscriptionLoyaltyService.Progress correctionPage(Provider provider, UUID enrollment, PromotionBudgetAccess.HistoryCursor cursor, int expected) throws Exception {
        var result = mvc.perform(get("/subscriptions/progress").session(provider.session()).param("subject", provider.id().toString()).param("enrollment", enrollment.toString())
                .param("wallet", cursor.walletId().toString()).param("walletVersion", Long.toString(cursor.walletVersion())).param("correctionAfterTime", cursor.afterTime().toString()).param("correctionAfter", cursor.afterId().toString()))
                .andExpect(status().is(expected)).andReturn();
        return expected == 200 ? (SubscriptionLoyaltyService.Progress) result.getModelAndView().getModel().get("progress") : null;
    }

    @Test void supportHistoryPagesOnlyExactEnrollmentAndRejectsStaleObservation() throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var original = new Provider(child.id(), guardian.session()); enroll(original, "FREE", false); UUID enrollment = view(original).enrollment().orElseThrow(); revoke(guardian, relationship);
        for (int count = 0; count < 11; count++) {
            clock.advance(Duration.ofSeconds(61));
            mvc.perform(post("/subscriptions/appeal").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("reason", "QUALIFICATION")).andExpect(status().is3xxRedirection());
            retainedRecheck(original, enrollment);
        }
        var history = ownProgress(original, enrollment).appeals(); assertThat(history.events()).hasSize(20);
        var cursor = history.next().orElseThrow();
        var next = supportPage(original, cursor, 200); assertThat(next.appeals().events()).hasSize(2); assertThat(next.appeals().next()).isEmpty();
        assertThat(next.appeals().events()).extracting(SubscriptionLoyaltyService.AppealEvent::eventId).doesNotContainAnyElementsOf(history.events().stream().map(SubscriptionLoyaltyService.AppealEvent::eventId).toList());
        mvc.perform(post("/subscriptions/appeal").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("reason", "PROMOTION_DEBT")).andExpect(status().is3xxRedirection());
        supportPage(original, cursor, 409);
        operatorProof(); var stranger = provider(); enroll(stranger, "FREE", false);
        var foreign = new SubscriptionLoyaltyService.AppealCursor(view(stranger).enrollment().orElseThrow(), cursor.observation(), cursor.afterTime(), cursor.afterId());
        supportPage(stranger, foreign, 409);
    }

    private SubscriptionLoyaltyService.Progress supportPage(Provider provider, SubscriptionLoyaltyService.AppealCursor cursor, int expected) throws Exception {
        var result = mvc.perform(get("/subscriptions/progress").session(provider.session()).param("subject", provider.id().toString()).param("enrollment", cursor.enrollmentId().toString())
                .param("appealObservation", cursor.observation()).param("appealAfterTime", cursor.afterTime().toString()).param("appealAfter", cursor.afterId().toString())).andExpect(status().is(expected)).andReturn();
        return expected == 200 ? (SubscriptionLoyaltyService.Progress) result.getModelAndView().getModel().get("progress") : null;
    }

    @Test void factualAudienceValidationPrecedesDenialAndCaughtFailureRollsBackOuterReceipt() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false); var completed = paidJob(provider(), provider);
        UUID enrollment = view(provider).enrollment().orElseThrow();
        var jobs = context.getBean(ar.changeo.jobs.api.JobLoyaltyAccess.class);
        var conversations = context.getBean(ar.changeo.conversations.api.ConversationAccess.class);
        mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", "true")).andExpect(status().is3xxRedirection());
        var discovery = entitlements.discover(Set.of(new SubjectId(provider.id())));
        var evidence = jobs.discoverEvidence(new SubjectId(provider.id()), Set.of(completed.job()));
        Set<AccountId> accounts = new HashSet<>(discovery.requiredAccountIds()); accounts.addAll(evidence.requiredAccountIds());
        UUID receipt = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            participants.requireForUpdate(PermissionRequest.self(new AccountId(provider.id()), Capability.ACCESS_SUPPORT), Set.copyOf(accounts));
            jdbc.queryForList("select slot from subscriptions_policy_current where slot=1 for update");
            jdbc.queryForList("select version from subscriptions_policy where version=? for share", discovery.currentPolicyVersion());
            jdbc.queryForList("select code from subscriptions_campaign where code='SL1B-LOYALTY-2' for update");
            jdbc.queryForList("select provider_id from subscriptions_provider where provider_id=? for update", provider.id());
            jdbc.queryForList("select id from subscriptions_enrollment where id=? for update", enrollment);
            billing.requireStatusesForUpdate(List.of(billingBinding(enrollment)));
            jdbc.update("insert into subscriptions_event values(?,?,?,?,?)", receipt, enrollment, "CAUGHT_FACT_FAILURE", discovery.currentPolicyVersion(), store.now());
            var result = jobs.requireQualificationForUpdate(evidence).get(completed.job());
            assertThat(result.qualified()).isFalse(); assertThat(result.reason()).isEqualTo("CURRENT_PARTICIPATION_INELIGIBLE");
            var protectedContext = conversations.discover(evidence.jobs().get(completed.job()).threadId());
            assertThat(conversations.eligibleForLoyaltyFacts(protectedContext, Set.of("GENERAL", "SENSITIVE"))).isFalse();
            assertThatThrownBy(() -> conversations.eligibleForLoyaltyFacts(protectedContext, Set.of("GENERAL", "UNKNOWN_LABEL"))).isInstanceOf(ar.changeo.conversations.ConversationFailure.class);
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_event where id=?", Integer.class, receipt)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, provider.id())).isZero(); conservation();
    }

    @ParameterizedTest @CsvSource({"PROGRESS,GUARDIAN,true", "PROGRESS,GUARDIAN,false", "RECHECK,GUARDIAN,true", "RECHECK,GUARDIAN,false", "APPEAL,GUARDIAN,true", "APPEAL,GUARDIAN,false", "PROGRESS,RESTRICTION,true", "PROGRESS,RESTRICTION,false", "RECHECK,RESTRICTION,true", "RECHECK,RESTRICTION,false", "APPEAL,RESTRICTION,true", "APPEAL,RESTRICTION,false"})
    void retainedMetadataAndCorrectionProtectFullIdentityThroughBothPhysicalOrders(String action, String mutation, boolean mutationFirst) throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var provider = new Provider(child.id(), guardian.session()); enroll(provider, "FREE", false);
        var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job()); recheck(provider, second.job()); enrollCredit(provider); assertThat(worker.processOne()).isTrue();
        UUID enrollment = view(provider).enrollment().orElseThrow(); UUID wallet = BillingStore.walletId(billingBinding(enrollment));
        cancelOwnedEnrollment(provider, enrollment);
        operatorProof(); var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var changingPid = new AtomicInteger();
        try {
            if (mutationFirst) {
                var changing = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    changingPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    try { retainedMutation(mutation, guardian, provider, relationship); } catch (Exception failure) { throw new IllegalStateException(failure); }
                    held.countDown(); await(release); return true;
                }));
                int initialStatus = mutation.equals("GUARDIAN") ? 403 : action.equals("APPEAL") ? 302 : 409;
                await(held); var observing = pool.submit(() -> { retainedRequest(action, provider, enrollment, initialStatus); return true; });
                awaitBlocked(changingPid.get(), "identity_account");
                assertThat(jdbc.queryForObject("select debt_minor from payments_promotion_wallet where id=?", Long.class, wallet)).isZero();
                release.countDown(); assertThat(changing.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(observing.get(5, TimeUnit.SECONDS)).isTrue();
                retainedRequest(action, provider, enrollment, action.equals("PROGRESS") ? 200 : 302);
            } else {
                try (var canonical = dataSource.getConnection()) {
                    canonical.setAutoCommit(false); int canonicalPid;
                    String statement = action.equals("APPEAL") ? "select id from sandbox.subscriptions_enrollment where id=? for update" : "select id from sandbox.jobs_job where id=? for update";
                    UUID target = action.equals("APPEAL") ? enrollment : java.util.stream.Stream.of(first.job(), second.job()).min(Comparator.comparing(UUID::toString)).orElseThrow();
                    try (var lock = canonical.prepareStatement(statement)) { lock.setObject(1, target); lock.executeQuery().close(); }
                    try (var query = canonical.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) { row.next(); canonicalPid = row.getInt(1); }
                    var observing = pool.submit(() -> { retainedRequest(action, provider, enrollment, action.equals("PROGRESS") ? 200 : 302); return true; });
                    int observingPid = awaitBlocked(canonicalPid, action.equals("APPEAL") ? "subscriptions_enrollment" : "jobs_job");
                    var changing = pool.submit(() -> { retainedMutation(mutation, guardian, provider, relationship); return true; });
                    awaitBlocked(observingPid, "identity_account");
                    assertThat(jdbc.queryForObject("select status from identity_guardian_link where id=?", String.class, relationship)).isEqualTo("VERIFIED");
                    canonical.commit(); assertThat(observing.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(changing.get(5, TimeUnit.SECONDS)).isTrue();
                }
            }
            var observed = ownProgress(provider, enrollment); assertThat(observed.retained()).isTrue(); assertThat(observed.verifiedMilestone()).isZero();
            retainedRecheck(provider, enrollment); var corrected = ownProgress(provider, enrollment);
            assertThat(corrected.debtMinor()).isEqualTo(100); assertThat(corrected.history().redeemedMinor()).isEqualTo(100); assertThat(corrected.availableCreditMinor()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where wallet_id=?", Integer.class, wallet)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payments_promotion_adjustment where wallet_id=? and kind='DEBT'", Integer.class, wallet)).isEqualTo(1);
            conservation(); System.out.println("Retained real identity order: " + action + "/" + mutation + "/" + (mutationFirst ? "mutation-first" : "observation-first"));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private void retainedMutation(String mutation, Provider guardian, Provider provider, UUID relationship) throws Exception {
        if (mutation.equals("GUARDIAN")) { revoke(guardian, relationship); }
        else { mvc.perform(post("/admin/accounts/" + provider.id() + "/restriction").session(operator).with(csrf()).param("active", "true")).andExpect(status().is3xxRedirection()); }
    }

    private void retainedRequest(String action, Provider provider, UUID enrollment, int expected) throws Exception {
        var request = action.equals("PROGRESS") ? get("/subscriptions/progress") : post(action.equals("RECHECK") ? "/subscriptions/recheck" : "/subscriptions/appeal").with(csrf());
        request.session(provider.session()).param("subject", provider.id().toString()).param("enrollment", enrollment.toString());
        if (action.equals("APPEAL")) { request.param("reason", "QUALIFICATION"); }
        mvc.perform(request).andExpect(status().is(expected));
    }

    @ParameterizedTest @CsvSource({"ORIGINAL,true", "ORIGINAL,false", "CURRENT,true", "CURRENT,false"})
    void retainedCorrectionDeclaresBothCompleteFinancialBindingsBeforeSingleWalletCallbacks(String target, boolean callbackFirst) throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var original = new Provider(child.id(), guardian.session()); enroll(original, "FREE", false);
        var first = paidJob(provider(), original); var second = paidJob(provider(), original);
        recheck(original, first.job()); recheck(original, second.job()); enrollCredit(original); assertThat(worker.processOne()).isTrue();
        UUID originalEnrollment = view(original).enrollment().orElseThrow(); var originalBinding = billingBinding(originalEnrollment);
        cancelOwnedEnrollment(original, originalEnrollment);
        revoke(guardian, relationship); var successor = provider(); link(successor, child); var current = new Provider(child.id(), successor.session());
        enroll(current, "PAID", true); assertThat(worker.processOne()).isTrue(); var currentBinding = billingBinding(view(current).enrollment().orElseThrow());
        cancelOwnedEnrollment(current, currentBinding.enrollmentId());
        var oldCharge = store.charges(originalBinding).getFirst(); var currentCharge = store.charges(currentBinding).getFirst();
        var charge = target.equals("ORIGINAL") ? oldCharge : currentCharge;
        var observation = gateway.recordOutcome(charge.gatewayRequest(), GatewayObservation.Outcome.REVERSED, charge.gatewayRequest().amountMinor());
        var declared = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod(); List<BillingBinding> bindings = invocation.getArgument(0);
            if (bindings.size() == 2) {
                assertThat(new HashSet<>(bindings)).containsExactlyInAnyOrder(originalBinding, currentBinding);
                store.requireHeld(oldCharge); store.requireHeld(currentCharge); declared.incrementAndGet();
            }
            return result;
        }).when(store).protectAll(anyList(), eq(false));
        var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var callbackPid = new AtomicInteger();
        try {
            if (callbackFirst) {
                var holdOnce = new java.util.concurrent.atomic.AtomicBoolean();
                doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    if (holdOnce.compareAndSet(false, true)) { callbackPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); held.countDown(); await(release); }
                    return result;
                }).when(store).protect(eq(charge.request().binding()), eq(false));
                var callback = pool.submit(() -> { outcomes.record(observation); return true; }); await(held);
                var correction = pool.submit(() -> { retainedRecheck(original, originalEnrollment); return true; });
                awaitBlocked(callbackPid.get(), "payments_subscription_budget");
                assertThat(jdbc.queryForObject("select debt_minor from payments_promotion_wallet where id=?", Long.class, BillingStore.walletId(originalBinding))).isZero();
                release.countDown(); assertThat(callback.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(correction.get(5, TimeUnit.SECONDS)).isTrue();
            } else {
                try (var canonical = dataSource.getConnection()) {
                    canonical.setAutoCommit(false); int canonicalPid;
                    UUID job = java.util.stream.Stream.of(first.job(), second.job()).min(Comparator.comparing(UUID::toString)).orElseThrow();
                    try (var lock = canonical.prepareStatement("select id from sandbox.jobs_job where id=? for update")) { lock.setObject(1, job); lock.executeQuery().close(); }
                    try (var query = canonical.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) { row.next(); canonicalPid = row.getInt(1); }
                    var correction = pool.submit(() -> { retainedRecheck(original, originalEnrollment); return true; });
                    int correctionPid = awaitBlocked(canonicalPid, "jobs_job");
                    var callback = pool.submit(() -> { outcomes.record(observation); return true; }); awaitBlocked(correctionPid, "payments_subscription_budget");
                    assertThat(store.charge(charge.id()).state()).isEqualTo("SUCCESS");
                    canonical.commit(); assertThat(correction.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(callback.get(5, TimeUnit.SECONDS)).isTrue();
                }
            }
            assertThat(declared.get()).isPositive(); assertThat(store.charge(charge.id()).state()).isEqualTo("REVERSED");
            var history = ownProgress(original, originalEnrollment).history(); assertThat(history.debtMinor()).isEqualTo(100); assertThat(history.redeemedMinor()).isEqualTo(100); assertThat(history.availableMinor()).isZero();
            assertThat(jdbc.queryForObject("select debt_minor from payments_promotion_wallet where id=?", Long.class, BillingStore.walletId(currentBinding))).isZero();
            assertThat(store.charge(oldCharge.id()).request()).isEqualTo(oldCharge.request()); assertThat(store.charge(currentCharge.id()).request()).isEqualTo(currentCharge.request());
            assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, child.id())).isEqualTo(1);
            conservation(); System.out.println("Retained full financial union order: " + target + "/" + (callbackFirst ? "callback-first" : "correction-first"));
        } finally { release.countDown(); reset(store); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void unexpectedParticipatingHistoryFailureRollsBackRealRetainedCorrectionEvidenceAppealAndReservation() throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var original = new Provider(child.id(), guardian.session()); enroll(original, "FREE", false);
        var first = paidJob(provider(), original); var second = paidJob(provider(), original);
        recheck(original, first.job()); recheck(original, second.job()); enrollCredit(original);
        UUID enrollment = view(original).enrollment().orElseThrow(); var binding = billingBinding(enrollment); UUID wallet = BillingStore.walletId(binding);
        revoke(guardian, relationship);
        mvc.perform(post("/subscriptions/appeal").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", enrollment.toString()).param("reason", "QUALIFICATION")).andExpect(status().is3xxRedirection());
        var loyalty = context.getBean(SubscriptionLoyaltyService.class);
        var discovery = entitlements.discover(Set.of(binding.provider()));
        Object observation = ReflectionTestUtils.invokeMethod(loyalty, "discover", binding, Optional.empty());
        Set<AccountId> accounts = ReflectionTestUtils.invokeMethod(loyalty, "requiredAccounts", discovery, observation, binding.originalPayer());
        var evidenceBefore = jdbc.queryForMap("select provider_id,billing_payer,job_ids::text as job_ids,evidence_digest,valid,reason,milestone,checked_at,version from subscriptions_evidence where provider_id=? and billing_payer=?", child.id(), guardian.id());
        var walletBefore = jdbc.queryForMap("select * from payments_promotion_wallet where id=?", wallet);
        var operationBefore = jdbc.queryForMap("select * from payments_billing_operation where id=?", store.charges(binding).getFirst().id());
        var budgetBefore = jdbc.queryForList("select * from payments_subscription_budget order by kind");
        int journal = jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class);
        int corrections = jdbc.queryForObject("select count(*) from payments_promotion_adjustment where wallet_id=?", Integer.class, wallet);
        int support = jdbc.queryForObject("select count(*) from subscriptions_appeal_event e join subscriptions_appeal a on a.id=e.appeal_id where a.enrollment_id=?", Integer.class, enrollment);
        UUID receipt = UUID.randomUUID(); var calls = new AtomicInteger();
        PromotionBudgetAdapter ownerTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(promotionOwner);
        doAnswer(invocation -> { Object result = invocation.callRealMethod(); if (calls.incrementAndGet() == 2) { throw new IllegalStateException("Unexpected retained history owner failure"); } return result; }).when(ownerTarget).requireHistoryForUpdate(any(), any());
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                participants.requireForUpdate(PermissionRequest.self(binding.originalPayer(), Capability.ACCESS_SUPPORT), accounts);
                ReflectionTestUtils.invokeMethod(context.getBean("providerEntitlementService"), "protectPolicies", discovery, accounts);
                ReflectionTestUtils.invokeMethod(loyalty, "protectCampaign", observation);
                ReflectionTestUtils.invokeMethod(context.getBean("providerEntitlementService"), "protectEnrollments", discovery, Set.of(enrollment));
                ReflectionTestUtils.invokeMethod(loyalty, "protectEvidence", observation);
                billing.requireStatusesForUpdate(List.of(binding));
                jdbc.update("insert into subscriptions_event values(?,?,?,?,?)", receipt, enrollment, "CAUGHT_HISTORY_FAILURE", discovery.currentPolicyVersion(), store.now());
                ReflectionTestUtils.invokeMethod(loyalty, "applyRetained", observation);
                assertThat(jdbc.queryForObject("select count(*) from payments_promotion_adjustment where wallet_id=?", Integer.class, wallet)).isEqualTo(corrections + 1);
                assertThat(jdbc.queryForObject("select state from payments_billing_operation where id=?", String.class, store.charges(binding).getFirst().id())).isEqualTo("CANCELLED");
                assertThat(jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class)).isGreaterThan(journal);
                assertThat(jdbc.queryForObject("select count(*) from subscriptions_appeal_event e join subscriptions_appeal a on a.id=e.appeal_id where a.enrollment_id=?", Integer.class, enrollment)).isEqualTo(support + 1);
                assertThatThrownBy(() -> promotionOwner.requireHistoryForUpdate(binding, Optional.empty())).isInstanceOf(IllegalStateException.class).hasMessage("Unexpected retained history owner failure");
                return null;
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        } finally { reset(ownerTarget); }
        assertThat(calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForMap("select provider_id,billing_payer,job_ids::text as job_ids,evidence_digest,valid,reason,milestone,checked_at,version from subscriptions_evidence where provider_id=? and billing_payer=?", child.id(), guardian.id()).equals(evidenceBefore)).as("Exact retained evidence after rollback").isTrue();
        assertThat(jdbc.queryForMap("select * from payments_promotion_wallet where id=?", wallet).equals(walletBefore)).as("Exact wallet after rollback").isTrue();
        assertThat(jdbc.queryForMap("select * from payments_billing_operation where id=?", store.charges(binding).getFirst().id()).equals(operationBefore)).as("Exact reservation operation after rollback").isTrue();
        assertThat(jdbc.queryForList("select * from payments_subscription_budget order by kind").equals(budgetBefore)).as("Exact independent budgets after rollback").isTrue();
        assertThat(jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class)).isEqualTo(journal);
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_adjustment where wallet_id=?", Integer.class, wallet)).isEqualTo(corrections);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_appeal_event e join subscriptions_appeal a on a.id=e.appeal_id where a.enrollment_id=?", Integer.class, enrollment)).isEqualTo(support);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_event where id=?", Integer.class, receipt)).isZero(); conservation();
        cancelOwnedEnrollment(original, enrollment);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void currentOptInAndSuccessorGenerationSerializeProviderOwnershipWithoutPreferenceInheritance(boolean generationFirst) throws Exception {
        var guardian = provider(); var child = minorProvider(); UUID relationship = link(guardian, child);
        var original = new Provider(child.id(), guardian.session()); enroll(original, "FREE", false); UUID oldEnrollment = view(original).enrollment().orElseThrow();
        mvc.perform(post("/subscriptions/achievements").session(guardian.session()).with(csrf()).param("subject", child.id().toString()).param("enrollment", oldEnrollment.toString()).param("visible", "true")).andExpect(status().is3xxRedirection());
        revoke(guardian, relationship); var successor = provider(); link(successor, child); var current = new Provider(child.id(), successor.session());
        var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var generationPid = new AtomicInteger();
        try {
            if (generationFirst) {
                var generation = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    generationPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    try { enroll(current, "TRIAL", true); } catch (Exception failure) { throw new IllegalStateException(failure); }
                    held.countDown(); await(release); return true;
                }));
                await(held); var optIn = pool.submit(() -> { successorOptIn(current, Optional.empty(), 409); return true; }); awaitBlocked(generationPid.get(), "identity_account");
                assertThat(jdbc.queryForObject("select public_achievements from subscriptions_provider where provider_id=?", Boolean.class, child.id())).isTrue();
                release.countDown(); assertThat(generation.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(optIn.get(5, TimeUnit.SECONDS)).isTrue();
            } else {
                try (var parent = dataSource.getConnection()) {
                    parent.setAutoCommit(false); int parentPid;
                    try (var lock = parent.prepareStatement("select provider_id from sandbox.subscriptions_provider where provider_id=? for update")) { lock.setObject(1, child.id()); lock.executeQuery().close(); }
                    try (var query = parent.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) { row.next(); parentPid = row.getInt(1); }
                    var optIn = pool.submit(() -> { successorOptIn(current, Optional.empty(), 409); return true; }); int optInPid = awaitBlocked(parentPid, "subscriptions_provider");
                    var generation = pool.submit(() -> { enroll(current, "TRIAL", true); return true; }); awaitBlocked(optInPid, "identity_account");
                    parent.commit(); assertThat(optIn.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(generation.get(5, TimeUnit.SECONDS)).isTrue();
                }
            }
            UUID enrollment = view(current).enrollment().orElseThrow(); assertThat(enrollment).isNotEqualTo(oldEnrollment);
            assertThat(jdbc.queryForObject("select public_achievements from subscriptions_provider where provider_id=?", Boolean.class, child.id())).isFalse();
            successorOptIn(current, Optional.of(enrollment), 302); enroll(current, "PAID", true);
            assertThat(view(current).enrollment()).contains(enrollment);
            assertThat(jdbc.queryForObject("select public_achievements from subscriptions_provider where provider_id=?", Boolean.class, child.id())).isTrue();
            assertThat(worker.processOne()).isTrue(); assertThat(view(current).entitlement()).isTrue();
            assertThat(view(current).history()).singleElement().satisfies(period -> assertThat(period.state()).isEqualTo("SUCCESS"));
            cancelOwnedEnrollment(current, enrollment);
            assertThat(ownProgress(original, oldEnrollment).retained()).isTrue(); conservation();
            System.out.println("Actual opt-in/new generation order: " + (generationFirst ? "generation-first" : "opt-in-first"));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private void successorOptIn(Provider provider, Optional<UUID> enrollment, int expected) throws Exception {
        var request = post("/subscriptions/achievements").session(provider.session()).with(csrf()).param("subject", provider.id().toString()).param("visible", "true");
        enrollment.ifPresent(id -> request.param("enrollment", id.toString())); mvc.perform(request).andExpect(status().is(expected));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void evidenceAbsentInsertionProtectsActualPayerFkBeforeCampaignInBothPhysicalOrders(boolean payerFirst) throws Exception {
        var guardian = provider(); var child = minorProvider(); link(guardian, child);
        var provider = new Provider(child.id(), guardian.session()); enroll(provider, "FREE", false); var completed = paidJob(provider(), provider);
        var pool = Executors.newFixedThreadPool(2);
        try (var parent = dataSource.getConnection()) {
            parent.setAutoCommit(false); int parentPid;
            String sql = payerFirst ? "select id from sandbox.identity_account where id=? for update" : "select code from sandbox.subscriptions_campaign where code=? for update";
            try (var lock = parent.prepareStatement(sql)) { lock.setObject(1, payerFirst ? guardian.id() : "SL1B-LOYALTY-2"); lock.executeQuery().close(); }
            try (var query = parent.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) { row.next(); parentPid = row.getInt(1); }
            var insertion = pool.submit(() -> { recheck(provider, completed.job()); return true; });
            int insertionPid = awaitBlocked(parentPid, payerFirst ? "identity_account" : "subscriptions_campaign");
            Future<Boolean> payerProbe = null;
            if (payerFirst) {
                try (var probe = dataSource.getConnection(); var query = probe.createStatement()) {
                    probe.setAutoCommit(false); query.executeQuery("select code from sandbox.subscriptions_campaign where code='SL1B-LOYALTY-2' for update nowait").close(); probe.rollback();
                }
            } else {
                payerProbe = pool.submit(() -> {
                    try (var probe = dataSource.getConnection(); var lock = probe.prepareStatement("select id from sandbox.identity_account where id=? for update")) {
                        probe.setAutoCommit(false); lock.setObject(1, guardian.id()); lock.executeQuery().close(); probe.rollback(); return true;
                    }
                });
                awaitBlocked(insertionPid, "identity_account");
            }
            assertThat(jdbc.queryForObject("select count(*) from subscriptions_evidence where provider_id=? and billing_payer=?", Integer.class, child.id(), guardian.id())).isZero();
            parent.commit(); assertThat(insertion.get(5, TimeUnit.SECONDS)).isTrue(); if (payerProbe != null) { assertThat(payerProbe.get(5, TimeUnit.SECONDS)).isTrue(); }
            assertThat(jdbc.queryForObject("select count(*) from subscriptions_evidence where provider_id=? and billing_payer=?", Integer.class, child.id(), guardian.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from subscriptions_evidence where provider_id=? and billing_payer<>?", Integer.class, child.id(), guardian.id())).isZero();
            conservation(); System.out.println("Actual evidence payer FK order: " + (payerFirst ? "payer-first" : "campaign-after-full-payer"));
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void qualificationHoldsBothPhysicalRevisionsOneCompleteOldMoneyBatchAndAllApprovalParents(boolean moneyFirst) throws Exception {
        var provider = provider(); enroll(provider, "FREE", false); var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job());
        List<UUID> roots = java.util.stream.Stream.of(first.job(), second.job()).map(job -> jdbc.queryForObject("select id from payments_root where job_id=?", UUID.class, job)).sorted(Comparator.comparing(UUID::toString)).toList();
        String resources = java.util.stream.Stream.of(first.quote(), first.finalDelivery(), second.quote(), second.finalDelivery()).map(reference -> reference.resourceId().toString()).sorted().collect(java.util.stream.Collectors.joining(",", "{", "}"));
        UUID approval = jdbc.query("select id from moderation_reference where resource_id=any(?::uuid[]) order by id::text", (row, index) -> row.getObject(1, UUID.class), resources).getLast();
        JobLoyaltyMoneyAccess ownerTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(loyaltyMoneyOwner); var batches = new AtomicInteger();
        doAnswer(invocation -> {
            List<ar.changeo.jobs.api.FinancialAgreement> agreements = invocation.getArgument(0); var discovery = (LoyaltyMoneyDiscovery) invocation.getArgument(1);
            assertThat(agreements).extracting(ar.changeo.jobs.api.FinancialAgreement::jobId).containsExactlyInAnyOrder(first.job(), second.job());
            assertThat(discovery.roots()).containsAll(roots); batches.incrementAndGet(); return invocation.callRealMethod();
        }).when(ownerTarget).requireForUpdate(anyList(), any());
        var pool = Executors.newFixedThreadPool(5);
        try (var money = dataSource.getConnection(); var approvals = dataSource.getConnection()) {
            money.setAutoCommit(false); approvals.setAutoCommit(false); int moneyPid, approvalsPid;
            if (moneyFirst) { try (var lock = money.prepareStatement("select id from sandbox.payments_root where id=? for update")) { lock.setObject(1, roots.getFirst()); lock.executeQuery().close(); } }
            try (var query = money.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) { row.next(); moneyPid = row.getInt(1); }
            try (var lock = approvals.prepareStatement("select id from sandbox.moderation_reference where id=? for update")) { lock.setObject(1, approval); lock.executeQuery().close(); }
            try (var query = approvals.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) { row.next(); approvalsPid = row.getInt(1); }
            var qualification = pool.submit(() -> { recheck(provider, second.job()); return true; });
            int qualificationPid = awaitBlocked(moneyFirst ? moneyPid : approvalsPid, moneyFirst ? "payments_root" : "moderation_reference");
            var revisionOne = physicalProbe(pool, "jobs_revision", first.finalDelivery().revisionId()); var revisionTwo = physicalProbe(pool, "jobs_revision", second.finalDelivery().revisionId());
            awaitBlockedCount(qualificationPid, "jobs_revision", 2);
            if (moneyFirst) { money.commit(); assertThat(awaitBlocked(approvalsPid, "moderation_reference")).isEqualTo(qualificationPid); }
            var rootOne = physicalProbe(pool, "payments_root", roots.getFirst()); var rootTwo = physicalProbe(pool, "payments_root", roots.getLast());
            awaitBlockedCount(qualificationPid, "payments_root", 2); assertThat(batches.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, provider.id())).isZero();
            approvals.commit(); assertThat(qualification.get(5, TimeUnit.SECONDS)).isTrue();
            for (var probe : List.of(revisionOne, revisionTwo, rootOne, rootTwo)) { assertThat(probe.get(5, TimeUnit.SECONDS)).isTrue(); }
            assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, provider.id())).isEqualTo(1); conservation();
            System.out.println("Actual combined C/key-share/one-old-M/all-A: " + (moneyFirst ? "money-first" : "qualification-first"));
        } finally { reset(ownerTarget); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private Future<Boolean> physicalProbe(ExecutorService pool, String table, UUID id) {
        assertThat(Set.of("jobs_revision", "payments_root")).contains(table);
        return pool.submit(() -> {
            try (var probe = dataSource.getConnection(); var lock = probe.prepareStatement("select id from sandbox." + table + " where id=? for update")) {
                probe.setAutoCommit(false); lock.setObject(1, id); lock.executeQuery().close(); probe.rollback(); return true;
            }
        });
    }

    private void awaitBlockedCount(int blocker, String statementOwner, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            int count = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid)) and position(? in query)>0", Integer.class, blocker, statementOwner);
            if (count == expected) { return; } Thread.sleep(10);
        }
        throw new AssertionError("El conjunto completo no bloqueó en el propietario PostgreSQL declarado: " + statementOwner);
    }

    private SubscriptionLoyaltyService.Progress ownProgress(Provider provider, UUID enrollment) throws Exception {
        return (SubscriptionLoyaltyService.Progress) mvc.perform(get("/subscriptions/progress").session(provider.session()).param("subject", provider.id().toString()).param("enrollment", enrollment.toString())).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("progress");
    }

    private void retainedRecheck(Provider provider, UUID enrollment) throws Exception {
        mvc.perform(post("/subscriptions/recheck").session(provider.session()).with(csrf()).param("subject", provider.id().toString()).param("enrollment", enrollment.toString())).andExpect(redirectedUrl("/subscriptions/progress?subject=" + provider.id() + "&enrollment=" + enrollment));
    }

    private void cancelOwnedEnrollment(Provider provider, UUID enrollment) throws Exception {
        var selected = (SubscriptionService.View) mvc.perform(get("/subscriptions").session(provider.session()).param("subject", provider.id().toString()).param("enrollment", enrollment.toString()))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("subscription");
        assertThat(selected.enrollment()).contains(enrollment);
        mvc.perform(post("/subscriptions/cancel").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("subject", provider.id().toString()).param("enrollment", enrollment.toString()).param("version", Long.toString(selected.version()))).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select stop_renewal from subscriptions_enrollment where id=?", Boolean.class, enrollment)).isTrue();
    }

    private void revoke(Provider guardian, UUID relationship) throws Exception {
        long version = jdbc.queryForObject("select version from identity_guardian_link where id=?", Long.class, relationship);
        mvc.perform(post("/account/guardian/" + relationship + "/revoke").session(guardian.session()).with(csrf()).param("version", Long.toString(version))).andExpect(status().is3xxRedirection());
    }

    @Test void selectedUnfundedJobHasNoQualificationAndReadOnlyMoneyBatchDoesNotBootstrap() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false); var journey = negotiate(provider(), provider);
        int roots = jdbc.queryForObject("select count(*) from payments_root", Integer.class);
        int balances = jdbc.queryForObject("select count(*) from payments_balance", Integer.class);
        int journal = jdbc.queryForObject("select count(*) from payments_ledger", Integer.class);
        recheck(provider, journey.job()); assertThat(progress(provider).verifiedMilestone()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_root", Integer.class)).isEqualTo(roots);
        assertThat(jdbc.queryForObject("select count(*) from payments_balance", Integer.class)).isEqualTo(balances);
        assertThat(jdbc.queryForObject("select count(*) from payments_ledger", Integer.class)).isEqualTo(journal);
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, provider.id())).isZero();
    }

    @Test void consumedInvalidEvidenceRecordsOneDebtAndSameAwardReplacementNeverCreatesCredit() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false);
        var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job()); recheck(provider, second.job()); enrollCredit(provider); assertThat(worker.processOne()).isTrue();
        long spent = jdbc.queryForObject("select spent_minor from payments_subscription_budget where kind='PROMOTION'", Long.class);
        recall(first.finalDelivery()); recheck(provider, first.job()); recheck(provider, first.job());
        assertThat(progress(provider).debtMinor()).isEqualTo(100); assertThat(view(provider).creditMinor()).isZero();
        assertThat(jdbc.queryForObject("select spent_minor from payments_subscription_budget where kind='PROMOTION'", Long.class)).isEqualTo(spent);
        var replacement = paidJob(provider(), provider); recheck(provider, replacement.job());
        var restored = progress(provider); assertThat(restored.verifiedMilestone()).isEqualTo(2); assertThat(restored.debtMinor()).isZero(); assertThat(restored.availableCreditMinor()).isZero();
        assertThat(jdbc.queryForObject("select redeemed_minor from payments_promotion_wallet where provider_id=?", Long.class, provider.id())).isEqualTo(100);
        assertThat(jdbc.queryForObject("select spent_minor from payments_subscription_budget where kind='PROMOTION'", Long.class)).isEqualTo(spent);
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, provider.id())).isEqualTo(1);
    }

    @Test void separateAchievementOptInShowsOnlyCurrentlyQualifiedPublicMilestones() throws Exception {
        var provider = provider(); enroll(provider, "FREE", false);
        var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
        recheck(provider, first.job()); recheck(provider, second.job());
        var profile = publication(provider, ar.changeo.marketplace.PresenceType.PROVIDER_PROFILE);
        String path = "/profiles/" + profile.resourceId();
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Dos clientes con trabajos verificados"))));
        mvc.perform(post("/subscriptions/achievements").session(provider.session()).with(csrf()).param("subject", provider.id().toString()).param("visible", "true")).andExpect(status().is3xxRedirection());
        var publicResult = mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Dos clientes con trabajos verificados"))).andReturn().getResponse().getContentAsString();
        for (String privateValue : List.of(contacts.get(provider.id()), first.job().toString(), second.job().toString(), view(provider).enrollment().orElseThrow().toString(), "payments_promotion", "evidenceDigest")) {
            assertThat(publicResult).doesNotContain(privateValue);
            for (String surface : List.of("preview", "share")) { mvc.perform(get(path + "/" + surface)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(privateValue)))); }
        }
        recall(first.finalDelivery());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Dos clientes con trabajos verificados"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Primer cliente con trabajo verificado")));
        mvc.perform(post("/subscriptions/achievements").session(provider.session()).with(csrf()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Primer cliente con trabajo verificado"))));
        recall(profile);
        for (String suffix : List.of("", "/preview", "/share")) { mvc.perform(get(path + suffix)).andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Diseño e impresión sintética")))); }
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> context.getBean(LoyaltyPublicAccess.class).current(new SubjectId(provider.id())))).isInstanceOf(SubscriptionFailure.class);
        conservation();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void twoAbsentReciprocalDiscoveriesSerializeFullIdentityAndCampaignThenOnlyFirstDirectionWins(boolean firstDirection) throws Exception {
        var one = provider(); var two = provider(); enroll(one, "FREE", false); enroll(two, "FREE", false);
        var forward = paidJob(two, one); var reverse = paidJob(one, two);
        var winner = firstDirection ? one : two; var loser = firstDirection ? two : one;
        var winningJob = firstDirection ? forward : reverse; var losingJob = firstDirection ? reverse : forward;
        var pool = Executors.newFixedThreadPool(2);
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false); int blockerPid;
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("select code from sandbox.subscriptions_campaign where code='SL1B-LOYALTY-2' for update").close();
                try (var row = statement.executeQuery("select pg_backend_pid()")) { row.next(); blockerPid = row.getInt(1); }
            }
            var first = pool.submit(() -> { recheck(winner, winningJob.job()); return true; });
            int firstPid = awaitBlocked(blockerPid, "subscriptions_campaign");
            var second = pool.submit(() -> mvc.perform(post("/subscriptions/recheck").session(loser.session()).with(csrf()).param("subject", loser.id().toString()).param("candidate", losingJob.job().toString()))
                    .andExpect(status().isConflict()).andReturn());
            awaitBlocked(firstPid, "identity_account");
            assertThat(jdbc.queryForObject("select count(*) from subscriptions_pair_claim where provider_payer in (?,?) and customer_payer in (?,?)", Integer.class, one.id(), two.id(), one.id(), two.id())).isZero();
            blocker.commit(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); second.get(5, TimeUnit.SECONDS);
            recheck(loser, losingJob.job()); assertThat(progress(winner).verifiedMilestone()).isEqualTo(1);
            assertThat(progress(loser).verifiedMilestone()).isZero(); assertThat(progress(loser).reason()).isEqualTo("RECIPROCAL_HOLD");
            assertThat(jdbc.queryForObject("select count(*) from subscriptions_pair_claim where provider_payer in (?,?) and customer_payer in (?,?)", Integer.class, one.id(), two.id(), one.id(), two.id())).isEqualTo(1);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        conservation();
    }

    @Test void reciprocalHistoricalClaimSurvivesRecallWithoutRefreshingThirtyDayTimer() throws Exception {
        var firstProvider = provider(); var secondProvider = provider();
        enroll(firstProvider, "FREE", false); enroll(secondProvider, "FREE", false);
        var first = paidJob(secondProvider, firstProvider); recheck(firstProvider, first.job());
        Instant recorded = jdbc.queryForObject("select completed_at from subscriptions_pair_claim where provider_payer=? and customer_payer=?", (row, index) -> row.getTimestamp(1).toInstant(), firstProvider.id(), secondProvider.id());
        var inverse = paidJob(firstProvider, secondProvider); recheck(secondProvider, inverse.job());
        assertThat(progress(secondProvider).verifiedMilestone()).isZero(); recall(first.finalDelivery()); recheck(firstProvider, first.job());
        recheck(secondProvider, inverse.job()); assertThat(progress(secondProvider).reason()).isEqualTo("RECIPROCAL_HOLD");
        Instant after = jdbc.queryForObject("select completed_at from subscriptions_pair_claim where provider_payer=? and customer_payer=?", (row, index) -> row.getTimestamp(1).toInstant(), firstProvider.id(), secondProvider.id());
        assertThat(after).isEqualTo(recorded);
        clock.advance(Duration.ofDays(30).plusSeconds(1));
        operator = login(operatorEnrollment.getProperty("contact"), operatorEnrollment.getProperty("password")); operatorProof();
        for (var participant : List.of(firstProvider, secondProvider)) {
            mvc.perform(post("/admin/accounts/" + participant.id() + "/eligibility").session(operator).with(csrf()).param("kind", "AGE").param("age", "30")).andExpect(status().is3xxRedirection());
            mvc.perform(post("/admin/accounts/" + participant.id() + "/eligibility").session(operator).with(csrf()).param("kind", "PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection());
        }
        var fresh = new Provider(secondProvider.id(), login(contacts.get(secondProvider.id()), "Synthetic-password-12345"));
        recheck(fresh, inverse.job()); assertThat(progress(fresh).verifiedMilestone()).isEqualTo(1);
    }

    @Test void changedPolicyBlocksOldQueuedConsentThenFreshConsentUsesExactCents() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow();
        var old = store.charge(claim.operationId()); publish(12050, 20); worker.process(claim);
        assertThat(store.charge(claim.operationId()).state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where stable_key=?", Integer.class, claim.stableKey())).isZero();
        clock.advance(Duration.ofSeconds(1)); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var current = view(provider); assertThat(current.entitlement()).isTrue(); assertThat(current.policy().grossMinor()).isEqualTo(12050);
        assertThat(current.history()).hasSize(2); assertThat(store.charge(old.id()).request()).isEqualTo(old.request());
        assertThat(current.history().getLast()).satisfies(period -> { assertThat(period.grossMinor()).isEqualTo(12050); assertThat(period.externalMinor()).isEqualTo(12050); assertThat(period.feeSpentMinor()).isEqualTo(20); });
        mvc.perform(get("/subscriptions").session(provider.session())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("120,50")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0,20")));
        conservation();
    }

    @Test void freshChangedConsentRetainsOldPaidPeriodAndQueuesOnlyItsNextCalendarPeriod() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); assertThat(worker.processOne()).isTrue();
        var old = view(provider).history().getFirst(); publish(12050, 20);
        var changed = view(provider); assertThat(changed.entitlement()).isTrue(); assertThat(changed.recurringAuthorized()).isFalse();
        enroll(provider, "RESUME", true); var renewed = view(provider);
        assertThat(renewed.recurringAuthorized()).isTrue(); assertThat(renewed.history()).containsExactly(old); assertThat(renewed.nextDate()).contains(old.end());
        assertThat(worker.processOne()).isFalse();
        clock.advance(Duration.between(clock.instant(), old.end()).plusSeconds(1));
        provider = renewIdentity(provider);
        boolean prepared = false; for (int count = 0; count < 10 && !prepared; count++) { prepared = subscriptions.renewOne(); }
        assertThat(prepared).isTrue(); assertThat(worker.processOne()).isTrue();
        var current = view(provider); assertThat(current.history()).hasSize(2); assertThat(current.history().getFirst()).isEqualTo(old);
        assertThat(current.history().getLast()).satisfies(period -> { assertThat(period.start()).isEqualTo(old.end()); assertThat(period.grossMinor()).isEqualTo(12050); assertThat(period.feeSpentMinor()).isEqualTo(20); });
        conservation();
    }

    @Test void zeroFeeHasNoReservationExpenseOrZeroLedgerAndSavedOutcomeUsesExactChangedGross() throws Exception {
        publish(12050, 0); var provider = provider(); long available = fee("available_minor"), spent = fee("spent_minor");
        enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow(); dispatch.authorize(claim);
        var request = store.charge(claim.operationId()).gatewayRequest(); publish(12575, 20);
        outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.PARTIAL, 3050));
        outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.SUCCESS, 12050));
        var current = store.charge(claim.operationId()); assertThat(current.request().grossMinor()).isEqualTo(12050); assertThat(current.request().feeMinor()).isZero();
        assertThat(current.collected()).isEqualTo(12050); assertThat(current.feeSpent()).isFalse(); assertThat(fee("available_minor")).isEqualTo(available); assertThat(fee("spent_minor")).isEqualTo(spent);
        assertThat(jdbc.queryForObject("select count(*) from payments_subscription_ledger where operation_id=? and (signed_minor=0 or account in ('FEE_RESERVED','FEE_EXPENSE','FEE_AVAILABLE'))", Integer.class, current.id())).isZero();
        outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.REVERSED, 12050)); assertThat(view(provider).entitlement()).isFalse();
        assertThat(store.charge(current.id()).returned()).isEqualTo(12050); conservation();
    }

    @Test void feeBeyondIndependentBackingRollsBackConsentRootAndReservation() throws Exception {
        publish(12050, Math.addExact(fee("available_minor"), 1)); var provider = provider(); var current = view(provider);
        int roots = jdbc.queryForObject("select count(*) from payments_billing_root", Integer.class), consents = jdbc.queryForObject("select count(*) from subscriptions_consent", Integer.class);
        long available = fee("available_minor"), reserved = fee("reserved_minor");
        mvc.perform(post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", provider.id().toString())
                .param("version", Long.toString(current.version())).param("mode", "PAID").param("policyDigest", current.policy().digest()).param("recurringConsent", "true")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_root", Integer.class)).isEqualTo(roots); assertThat(jdbc.queryForObject("select count(*) from subscriptions_consent", Integer.class)).isEqualTo(consents);
        assertThat(fee("available_minor")).isEqualTo(available); assertThat(fee("reserved_minor")).isEqualTo(reserved); assertThat(view(provider).enrollment()).isEmpty(); conservation();
    }

    @Test void sameRootRetryRejectsEveryOriginalAmountAndTermsSubstitutionAndCaughtFailureRollsBack() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow(); dispatch.authorize(claim);
        outcomes.record(gateway.recordOutcome(store.charge(claim.operationId()).gatewayRequest(), GatewayObservation.Outcome.FAILED_NO_EFFECT, 0));
        var original = store.charge(claim.operationId()).request(); var transaction = new TransactionTemplate(transactions);
        long available = fee("available_minor"), reserved = fee("reserved_minor"); int count = jdbc.queryForObject("select count(*) from payments_billing_operation", Integer.class);
        List<BillingChargeRequest> invalid = List.of(
                retry(original, original.consentId(), original.policyVersion(), original.policyDigest(), original.grossMinor() + 50, original.feeMinor(), 0, original.periodEnd()),
                retry(original, original.consentId(), original.policyVersion(), original.policyDigest(), original.grossMinor(), original.feeMinor() + 1, 0, original.periodEnd()),
                retry(original, original.consentId(), original.policyVersion(), original.policyDigest(), original.grossMinor(), original.feeMinor(), 100, original.periodEnd()),
                retry(original, UUID.randomUUID(), original.policyVersion(), original.policyDigest(), original.grossMinor(), original.feeMinor(), 0, original.periodEnd()),
                retry(original, original.consentId(), "OTHER-POLICY", original.policyDigest(), original.grossMinor(), original.feeMinor(), 0, original.periodEnd()),
                retry(original, original.consentId(), original.policyVersion(), "f".repeat(64), original.grossMinor(), original.feeMinor(), 0, original.periodEnd()),
                retry(original, original.consentId(), original.policyVersion(), original.policyDigest(), original.grossMinor(), original.feeMinor(), 0, original.periodEnd().plusSeconds(1)));
        for (var request : invalid) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                try { billing.prepareForUpdate(request); } catch (IllegalStateException denied) {}
                jdbc.update("insert into subscriptions_event values(?,?,?, ?,?)", UUID.randomUUID(), original.binding().enrollmentId(), "MUST_ROLL_BACK", original.policyVersion(), store.now());
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_event where kind='MUST_ROLL_BACK'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_operation", Integer.class)).isEqualTo(count); assertThat(fee("available_minor")).isEqualTo(available); assertThat(fee("reserved_minor")).isEqualTo(reserved);
        var exact = retry(original, original.consentId(), original.policyVersion(), original.policyDigest(), original.grossMinor(), original.feeMinor(), 0, original.periodEnd());
        var next = transaction.execute(status -> billing.prepareForUpdate(exact)); assertThat(next.request().predecessor()).contains(claim.operationId());
        assertThat(worker.processOne()).isTrue(); conservation();
    }

    @Test void persistedNetPlusSelectedCreditAndOriginalRootAmountsCannotBeTampered() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow(); var original = store.charge(claim.operationId());
        assertThatThrownBy(() -> jdbc.update("insert into payments_billing_operation(id,root_id,command_id,stable_key,permitted_promotion_minor,promotion_minor,external_minor,fee_minor,state,available_at,reason,created_at) values(?,?,?,?,0,0,10001,10,'QUEUED',?,'QUEUED',?)",
                UUID.randomUUID(), original.rootId(), UUID.randomUUID(), UUID.randomUUID(), store.now(), store.now())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update payments_billing_root set gross_minor=gross_minor+1 where id=?", original.rootId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update payments_billing_operation set fee_minor=fee_minor+1 where id=?", original.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.charge(original.id())).isEqualTo(original); worker.process(claim); conservation();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void heldPolicyAndDispatchOrdersUseOnlyTheExactCommittedConsent(boolean policyFirst) throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow();
        var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var publisherPid = new AtomicInteger();
        try {
            if (policyFirst) {
                Future<?> publisher = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.queryForList("select slot from subscriptions_policy_current where slot=1 for update");
                    publisherPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    publish(12050, 20); held.countDown(); await(release);
                }));
                await(held); Future<?> authorization = pool.submit(() -> worker.process(claim));
                awaitBlocked(publisherPid.get(), "subscriptions_policy_current");
                assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where stable_key=?", Integer.class, claim.stableKey())).isZero();
                release.countDown(); publisher.get(5, TimeUnit.SECONDS); authorization.get(5, TimeUnit.SECONDS);
                assertThat(store.charge(claim.operationId()).state()).isEqualTo("CANCELLED");
                assertThat(store.charge(claim.operationId()).dispatchedAt()).isEmpty();
            } else {
                try (var blocker = dataSource.getConnection()) {
                    blocker.setAutoCommit(false); int blockerPid;
                    try (var statement = blocker.createStatement()) {
                        statement.executeQuery("select kind from sandbox.payments_subscription_budget where kind='FEE' for update").close();
                        try (var result = statement.executeQuery("select pg_backend_pid()")) { result.next(); blockerPid = result.getInt(1); }
                    }
                    Future<?> authorization = pool.submit(() -> dispatch.authorize(claim));
                    int dispatchPid = awaitBlocked(blockerPid, "payments_subscription_budget");
                    Future<?> publisher = pool.submit(() -> publish(12050, 20));
                    awaitBlocked(dispatchPid, "subscriptions_policy_current");
                    assertThat(store.charge(claim.operationId()).dispatchedAt()).isEmpty();
                    blocker.commit(); authorization.get(5, TimeUnit.SECONDS); publisher.get(5, TimeUnit.SECONDS);
                }
                var charge = store.charge(claim.operationId()); assertThat(charge.state()).isEqualTo("DISPATCHING");
                assertThat(charge.request().grossMinor()).isEqualTo(10000); assertThat(charge.request().feeMinor()).isEqualTo(10);
                outcomes.record(gateway.recordOutcome(charge.gatewayRequest(), GatewayObservation.Outcome.SUCCESS, 10000));
                var paid = view(provider); assertThat(paid.entitlement()).isTrue(); assertThat(paid.policy().grossMinor()).isEqualTo(12050);
                assertThat(paid.history()).singleElement().satisfies(period -> { assertThat(period.grossMinor()).isEqualTo(10000); assertThat(period.feeSpentMinor()).isEqualTo(10); });
            }
            conservation(); System.out.println("SL1C held PostgreSQL order verified: " + (policyFirst ? "policy-first/no-dispatch" : "dispatch-first/saved-old-outcome"));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void checkedLedgerOverflowCannotInsertEvenOnePartialLine() throws Exception {
        var provider = provider(); enroll(provider, "PAID", true); var claim = worker.claim().orElseThrow();
        var transaction = new TransactionTemplate(transactions); int before = jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            billing.requireStatusForUpdate(claim.request().binding());
            store.ledger(UUID.randomUUID(), BillingStore.walletId(claim.request().binding()), claim.operationId(),
                    new BillingStore.Entry("CHECKED_OVERFLOW", Long.MAX_VALUE), new BillingStore.Entry("CHECKED_OVERFLOW", 1),
                    new BillingStore.Entry("CHECKED_OVERFLOW", -Long.MAX_VALUE), new BillingStore.Entry("CHECKED_OVERFLOW", -1));
        })).isInstanceOf(ArithmeticException.class);
        assertThat(jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class)).isEqualTo(before);
        worker.process(claim); conservation();
    }

    @Test void boundedBillingHistoryRejectsTheNextRealConsentAndRollsBackEveryNewFinancialParent() throws Exception {
        var provider = provider();
        for (int count = 0; count < 200; count++) {
            clock.advance(Duration.ofSeconds(61)); enroll(provider, "PAID", true);
            var claim = worker.claim().orElseThrow(); dispatch.authorize(claim);
            outcomes.record(gateway.recordOutcome(store.charge(claim.operationId()).gatewayRequest(), GatewayObservation.Outcome.FAILED_NO_EFFECT, 0));
        }
        var current = view(provider); assertThat(current.history()).hasSize(200);
        long available = fee("available_minor"), reserved = fee("reserved_minor");
        int consents = jdbc.queryForObject("select count(*) from subscriptions_consent where enrollment_id=?", Integer.class, current.enrollment().orElseThrow());
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", provider.id().toString())
                .param("version", Long.toString(current.version())).param("mode", "PAID").param("policyDigest", current.policy().digest()).param("recurringConsent", "true")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_root where provider_id=?", Integer.class, provider.id())).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_consent where enrollment_id=?", Integer.class, current.enrollment().orElseThrow())).isEqualTo(consents);
        assertThat(fee("available_minor")).isEqualTo(available); assertThat(fee("reserved_minor")).isEqualTo(reserved);
        assertThat(view(provider).history()).hasSize(200); conservation();
    }

    @Test void duplicateOriginalPayerDoesNotBecomeTwoClientsOrAnAdditionalAward() throws Exception {
        var provider = provider(); var customer = provider(); enroll(provider, "FREE", false);
        var first = paidJob(customer, provider); var second = paidJob(customer, provider);
        recheck(provider, first.job()); recheck(provider, second.job());
        var current = progress(provider); assertThat(current.verifiedMilestone()).isEqualTo(1); assertThat(current.reason()).isEqualTo("DUPLICATE_PAYER");
        assertThat(current.availableCreditMinor()).isZero(); assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, provider.id())).isZero();
        conservation();
    }

    @Test void sourceRateLimitReturnsUsefulErrorWithoutChangingEnrollmentOrBlockingItsRead() throws Exception {
        var provider = provider(); var current = view(provider); clock.advance(Duration.ofSeconds(61));
        for (int count = 0; count < 20; count++) {
            mvc.perform(post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", provider.id().toString())
                    .param("version", "0").param("mode", "INVALID").param("policyDigest", current.policy().digest())).andExpect(status().isConflict());
        }
        mvc.perform(post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", provider.id().toString())
                .param("version", "0").param("mode", "FREE").param("policyDigest", current.policy().digest())).andExpect(status().isTooManyRequests())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reintentá en un minuto")));
        assertThat(view(provider).enrollment()).isEmpty(); clock.advance(Duration.ofSeconds(61)); enroll(provider, "FREE", false);
    }

    @Test @Order(Integer.MAX_VALUE)
    void finiteCampaignExhaustionAndSameAwardReplacementUseOnlyActuallyReturnedBacking() throws Exception {
        int remaining = Math.toIntExact(jdbc.queryForObject("select available_minor/100 from payments_subscription_budget where kind='PROMOTION'", Long.class));
        assertThat(remaining).isPositive(); var awarded = new ArrayList<Journey>();
        for (int count = 0; count < remaining; count++) {
            var provider = provider(); enroll(provider, "FREE", false);
            var first = paidJob(provider(), provider); var second = paidJob(provider(), provider);
            recheck(provider, first.job()); recheck(provider, second.job());
            assertThat(progress(provider).availableCreditMinor()).isEqualTo(100); awarded.add(first);
        }
        assertThat(jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class)).isZero();
        var pending = provider(); enroll(pending, "FREE", false);
        var pendingFirst = paidJob(provider(), pending); var pendingSecond = paidJob(provider(), pending);
        recheck(pending, pendingFirst.job()); recheck(pending, pendingSecond.job());
        assertThat(progress(pending).reason()).isEqualTo("BACKING_PENDING"); assertThat(progress(pending).verifiedMilestone()).isEqualTo(2);
        assertThat(progress(pending).availableCreditMinor()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, pending.id())).isZero();
        var original = awarded.getFirst(); recall(original.finalDelivery()); recheck(original.provider(), original.job());
        assertThat(jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class)).isEqualTo(100);
        recheck(pending, pendingSecond.job()); assertThat(progress(pending).availableCreditMinor()).isEqualTo(100);
        var replacement = paidJob(provider(), original.provider()); recheck(original.provider(), replacement.job());
        assertThat(progress(original.provider()).verifiedMilestone()).isEqualTo(2); assertThat(progress(original.provider()).reason()).isEqualTo("BACKING_PENDING");
        assertThat(progress(original.provider()).availableCreditMinor()).isZero();
        recall(pendingFirst.finalDelivery()); recheck(pending, pendingFirst.job());
        recheck(original.provider(), replacement.job()); assertThat(progress(original.provider()).availableCreditMinor()).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award where provider_id=?", Integer.class, original.provider().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select endowed_minor from payments_subscription_budget where kind='PROMOTION'", Long.class)).isEqualTo(10000);
        conservation(); System.out.println("Finite campaign exhausted by genuine paid jobs; replacement reused only returned original backing");
    }

    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private int awaitBlocked(int blocker, String statementOwner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var backends = jdbc.query("select pid from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid)) and position(? in query)>0",
                    (row, index) -> row.getInt(1), blocker, statementOwner);
            if (backends.size() == 1) { return backends.getFirst(); }
            Thread.sleep(10);
        }
        throw new AssertionError("El comando competidor no bloqueó en el propietario PostgreSQL declarado: " + statementOwner);
    }

    private BillingChargeRequest retry(BillingChargeRequest original, UUID consent, String policy, String digest, long gross, long fee, long credit, Instant end) {
        UUID previous = store.charges(original.binding()).stream().filter(charge -> charge.request().equals(original)).findFirst().orElseThrow().id();
        return new BillingChargeRequest(UUID.randomUUID(), original.binding(), consent, policy, digest, original.periodStart(), end, gross, original.currency(), credit, fee, Optional.of(previous));
    }

    private void publish(long gross, long fee) {
        ReflectionTestUtils.invokeMethod(context.getBean("subscriptionPublication"), "publish", new SubscriptionPolicy("SL1C-" + UUID.randomUUID(), gross, 30, 7, 2, 100, fee, SubscriptionPolicy.example().zone()));
        changedPolicy = true;
    }

    private Provider renewIdentity(Provider provider) throws Exception {
        operator = login(operatorEnrollment.getProperty("contact"), operatorEnrollment.getProperty("password")); operatorProof();
        mvc.perform(post("/admin/accounts/" + provider.id() + "/eligibility").session(operator).with(csrf()).param("kind", "AGE").param("age", "30")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/accounts/" + provider.id() + "/eligibility").session(operator).with(csrf()).param("kind", "PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection());
        return new Provider(provider.id(), login(contacts.get(provider.id()), "Synthetic-password-12345"));
    }

    private void conservation() {
        assertThat(jdbc.queryForObject("select count(*) from payments_subscription_budget where available_minor+reserved_minor+spent_minor<>endowed_minor", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_promotion_award a join payments_promotion_wallet w on w.id=a.wallet_id where w.outstanding_minor+w.redeemed_minor+w.returned_minor<>100", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_billing_operation o join payments_billing_root r on r.id=o.root_id where o.external_minor::numeric+o.promotion_minor::numeric<>r.gross_minor", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from (select batch_id from payments_subscription_ledger group by batch_id having sum(signed_minor::numeric)<>0) invalid", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select b.available_minor+coalesce(sum(w.outstanding_minor+w.redeemed_minor),0) from payments_subscription_budget b left join payments_promotion_wallet w on b.kind='PROMOTION' where b.kind='PROMOTION' group by b.available_minor", Long.class)).isEqualTo(10000);
    }

    private SubscriptionLoyaltyService.Progress progress(Provider provider) throws Exception {
        return (SubscriptionLoyaltyService.Progress) mvc.perform(get("/subscriptions/progress").session(provider.session())).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("progress");
    }

    private void recheck(Provider provider, UUID job) throws Exception {
        mvc.perform(post("/subscriptions/recheck").session(provider.session()).with(csrf()).param("subject", provider.id().toString()).param("candidate", job.toString())).andExpect(status().is3xxRedirection());
    }

    private void enrollCredit(Provider provider) throws Exception {
        var current = view(provider);
        mvc.perform(post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", provider.id().toString())
                .param("version", Long.toString(current.version())).param("mode", "PAID").param("policyDigest", current.policy().digest()).param("recurringConsent", "true").param("useCredit", "true")).andExpect(status().is3xxRedirection());
    }

    private ContentReference offer(Provider provider) throws Exception { return publication(provider, ar.changeo.marketplace.PresenceType.OFFER); }

    private ContentReference publication(Provider provider, ar.changeo.marketplace.PresenceType type) throws Exception {
        return publication(provider, type, true);
    }

    private ContentReference publication(Provider provider, ar.changeo.marketplace.PresenceType type, boolean activate) throws Exception {
        return publication(provider, type, activate, true);
    }

    private ContentReference publication(Provider provider, ar.changeo.marketplace.PresenceType type, boolean activate, boolean submit) throws Exception {
        mvc.perform(post("/marketplace/compose").session(provider.session()).with(csrf()).param("type", type.name()).param("subject", provider.id().toString())
                .param("title", "Diseño e impresión sintética").param("body", "Servicio privado para completar la prueba real").param("label", "GENERAL")).andExpect(status().isOk());
        var composition = (MarketplaceController.Composition) provider.session().getAttribute("marketplaceComposition");
        String location = mvc.perform(multipart("/marketplace/drafts").session(provider.session()).with(csrf()).param("stage", composition.stage().toString())
                .param("province", "Buenos Aires").param("area", "La Plata").param("modes", "REMOTE", "LOCAL", "SHIPPED")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        long version = jdbc.queryForObject("select version from marketplace_resource where id=?", Long.class, resource);
        UUID draft = jdbc.queryForObject("select current_revision from marketplace_resource where id=?", UUID.class, resource);
        mvc.perform(multipart("/marketplace/prepare").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", resource.toString())
                .param("revision", draft.toString()).param("version", Long.toString(version)).param("omitPreviousMedia", "true")).andExpect(status().is3xxRedirection());
        UUID revision = jdbc.queryForObject("select current_revision from marketplace_resource where id=?", UUID.class, resource);
        if (submit) mvc.perform(post("/marketplace/submit").session(provider.session()).with(csrf()).param("resource", resource.toString()).param("revision", revision.toString())).andExpect(status().is3xxRedirection());
        var reference = new ContentReference(type.surface(), resource, revision); if (activate) { approve(reference); } return reference;
    }

    private Journey negotiate(Provider customer, Provider provider) throws Exception {
        return negotiate(customer, provider, 2);
    }

    private Journey negotiate(Provider customer, Provider provider, int acceptedSides) throws Exception {
        var listing = offer(provider);
        String location = mvc.perform(post("/conversations/respond").session(customer.session()).with(csrf()).param("listing", listing.resourceId().toString())
                .param("subject", customer.id().toString()).param("mode", "REMOTE")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID thread = UUID.fromString(location.substring("/conversations/".length(), location.indexOf('?')));
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString())
                .param("subject", provider.id().toString()).param("kind", "INITIAL").param("scope", "Trabajo privado válido para la calificación").param("serviceAmount", "123.45")
                .param("deliveryAmount", "0").param("deliverables", "Una pieza completa")).andExpect(status().isOk());
        var composition = (AgreementController.Composition) provider.session().getAttribute("agreementComposition");
        String deadline = LocalDateTime.ofInstant(clock.instant().plusSeconds(86400), ZoneOffset.UTC).withSecond(0).withNano(0).toString();
        mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString()).param("arrangement", "REMOTE")
                .param("arrangementDescription", "Coordinación privada").param("completionDeadline", deadline).param("feedbackRounds", "2")
                .param("completionEvidence", "TEXT").param("evidenceDescription", "Una entrega completa").param("reviewWindowMinutes", "60").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var quote = jdbc.queryForObject("select id,current_revision from agreements_quote where job_id=?", (row, index) -> new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), job);
        mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", quote.resourceId().toString()).param("revision", quote.revisionId().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection()); approve(quote);
        long version = jdbc.queryForObject("select version from agreements_quote where id=?", Long.class, quote.resourceId());
        for (var side : List.of(customer, provider).subList(0, acceptedSides)) {
            mvc.perform(post("/agreements/accept").session(side.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", quote.resourceId().toString())
                    .param("revision", quote.revisionId().toString()).param("version", Long.toString(version)).param("subject", side.id().toString())).andExpect(status().is3xxRedirection());
        }
        return new Journey(customer, provider, job, quote, null);
    }

    private Journey paidJob(Provider customer, Provider provider) throws Exception {
        return paySelected(negotiate(customer, provider));
    }

    private Journey paySelected(Journey journey) throws Exception {
        var customer = journey.customer(); var provider = journey.provider();
        long version = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        mvc.perform(post("/payments/fund").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", customer.id().toString()).param("jobVersion", Long.toString(version)).param("creditMinor", "0")).andExpect(status().is3xxRedirection()); drainJobPayments();
        version = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        mvc.perform(post("/jobs/start").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("version", Long.toString(version)).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/jobs/content").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("subject", provider.id().toString()).param("kind", "FINAL_DELIVERY").param("body", "Entrega privada real completa").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var reference = jdbc.queryForObject("select id,current_revision from jobs_authored where job_id=? and kind='FINAL_DELIVERY'", (row, index) -> new ContentReference(SurfaceKind.JOB_FEEDBACK, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), journey.job());
        mvc.perform(post("/jobs/submit").session(provider.session()).with(csrf()).param("job", journey.job().toString()).param("subject", provider.id().toString())
                .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection()); approve(reference);
        version = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        mvc.perform(post("/jobs/accept-final").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("subject", customer.id().toString()).param("version", Long.toString(version)).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
        long jobVersion = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        long moneyVersion = jdbc.queryForObject("select version from payments_root where job_id=?", Long.class, journey.job());
        mvc.perform(post("/payments/release").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", provider.id().toString()).param("jobVersion", Long.toString(jobVersion)).param("moneyVersion", Long.toString(moneyVersion))).andExpect(status().is3xxRedirection()); drainJobPayments();
        assertThat(jdbc.queryForObject("select paid_minor from payments_root where job_id=?", Long.class, journey.job())).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select count(*) from payments_payout_origin p join payments_operation o on o.id=p.payout_id join payments_root r on r.id=o.root_id where r.job_id=? and p.payout_id<>p.release_id", Integer.class, journey.job())).isEqualTo(1);
        return new Journey(customer, provider, journey.job(), journey.quote(), reference);
    }

    private void approve(ContentReference reference) throws Exception { decision(reference, "APPROVE"); for (int count = 0; count < 100 && moderation.processOne(); count++) {} }
    private void recall(ContentReference reference) throws Exception { decision(reference, "RECALL"); }
    private void decision(ContentReference reference, String action) throws Exception {
        operatorProof(); UUID command = UUID.randomUUID();
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command", command.toString()).param("kind", reference.ownerKind().name())
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
        UUID caseId = jdbc.queryForObject("select outcome_id from moderation_receipt where command_id=?", UUID.class, command);
        mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("caseId", caseId.toString())
                .param("version", "0").param("action", action).param("label", "GENERAL").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
    }
    private void drainJobPayments() { for (int count = 0; count < 100; count++) { if (!jobPayments.processOne()) { return; } } throw new AssertionError("El trabajador financiero no alcanzó inactividad acotada"); }
    record Journey(Provider customer, Provider provider, UUID job, ContentReference quote, ContentReference finalDelivery) {}

    private long fee(String column) {
        assertThat(Set.of("available_minor", "reserved_minor", "spent_minor")).contains(column);
        return jdbc.queryForObject("select " + column + " from payments_subscription_budget where kind='FEE'", Long.class);
    }

    private SubscriptionService.View view(Provider provider) throws Exception {
        return (SubscriptionService.View) mvc.perform(get("/subscriptions").session(provider.session()).param("subject", provider.id().toString())).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("subscription");
    }

    private void enroll(Provider provider, String mode, boolean consent) throws Exception {
        var current = view(provider);
        var request = post("/subscriptions/enroll").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", provider.id().toString())
                .param("version", Long.toString(current.version())).param("mode", mode).param("policyDigest", current.policy().digest());
        if (consent) { request.param("recurringConsent", "true"); }
        mvc.perform(request).andExpect(status().is3xxRedirection());
    }

    private Provider provider() throws Exception {
        clock.advance(Duration.ofSeconds(61)); String contact = "sl" + UUID.randomUUID() + "@example.test", password = "Synthetic-password-12345";
        UUID id = accounts.register(contact, password).accountId().value(); accounts.verifyContact(id, tokens.get(id + ":CONTACT"));
        contacts.put(id, contact);
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "AGE").param("age", "30")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection());
        accounts.preferences(id, true, true); return new Provider(id, login(contact, password));
    }

    private Provider minorProvider() throws Exception {
        clock.advance(Duration.ofSeconds(61)); String contact = "slminor" + UUID.randomUUID() + "@example.test", password = "Synthetic-password-12345";
        UUID id = accounts.register(contact, password).accountId().value(); accounts.verifyContact(id, tokens.get(id + ":CONTACT")); contacts.put(id, contact);
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "AGE").param("age", "17")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "MINOR_DRAFT")).andExpect(status().is3xxRedirection());
        return new Provider(id, login(contact, password));
    }

    private UUID link(Provider guardian, Provider child) throws Exception {
        mvc.perform(post("/account/guardian/request").session(guardian.session()).with(csrf()).param("other", child.id().toString()).param("actingAsGuardian", "true")).andExpect(status().is3xxRedirection());
        UUID id = jdbc.queryForObject("select id from identity_guardian_link where guardian_id=? and minor_id=? and status='PENDING'", UUID.class, guardian.id(), child.id());
        operatorProof(); mvc.perform(post("/admin/guardian/" + id + "/review").session(operator).with(csrf()).param("version", "0").param("verified", "true")).andExpect(status().is3xxRedirection()); return id;
    }

    private MockHttpSession login(String contact, String password) throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", contact).param("password", password)).andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
        sessions.add(session); return session;
    }

    private void operatorProof() throws Exception {
        clock.advance(Duration.ofSeconds(61));
        mvc.perform(post("/moderation/operator/mfa").session(operator).with(csrf()).param("code", totp(operatorEnrollment.getProperty("totpBase32")))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/moderation/operator/reauth").session(operator).with(csrf()).param("password", operatorEnrollment.getProperty("password"))).andExpect(status().is3xxRedirection());
    }

    private String totp(String encoded) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; java.io.ByteArrayOutputStream key = new java.io.ByteArrayOutputStream(); int accumulator = 0, bits = 0;
        for (char character : encoded.replace("=", "").toUpperCase(Locale.ROOT).toCharArray()) {
            int value = alphabet.indexOf(character); if (value < 0) { throw new IllegalArgumentException("Factor inválido"); }
            accumulator = (accumulator << 5) | value; bits += 5;
            if (bits >= 8) { bits -= 8; key.write((accumulator >> bits) & 255); }
        }
        Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key.toByteArray(), "HmacSHA1"));
        byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond() / 30).array()); int offset = digest[digest.length - 1] & 15;
        int code = ((digest[offset] & 127) << 24) | ((digest[offset + 1] & 255) << 16) | ((digest[offset + 2] & 255) << 8) | (digest[offset + 3] & 255);
        Arrays.fill(digest, (byte) 0); return String.format(Locale.ROOT, "%06d", code % 1000000);
    }

    record Provider(UUID id, MockHttpSession session) {}
}
