package ar.changeo.payments;

import ar.changeo.ChangeoApplication;
import ar.changeo.agreements.AgreementController;
import ar.changeo.identity.*;
import ar.changeo.jobs.api.JobSnapshot;
import ar.changeo.marketplace.MarketplaceController;
import ar.changeo.moderation.ModerationWorker;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.api.*;
import ar.changeo.payments.gateway.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {ChangeoApplication.class, PaymentPostgresTest.Configuration.class}, properties = {
        "spring.flyway.schemas=sandbox", "spring.flyway.default-schema=sandbox", "spring.datasource.hikari.schema=sandbox",
        "spring.jpa.properties.hibernate.default_schema=sandbox", "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true",
        "changeo.synthetic-payments=true", "changeo.payments-worker=false", "changeo.moderation-worker=false", "changeo.notification-worker=false",
        "changeo.mfa-key=/tmp/changeo-payments-01a11313/mfa.key", "changeo.files-root=/tmp/changeo-payments-01a11313/artifacts",
        "changeo.payments-auth-key=/tmp/changeo-payments-01a11313/payments/auth.key"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PaymentPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired ar.changeo.identity.api.IdentityAccess identity;
    @Autowired SecurityProof proof;
    @Autowired AdminService admin;
    @Autowired GuardianService guardians;
    @Autowired ModerationWorker moderation;
    @Autowired PaymentWorker worker;
    @Autowired PaymentOutcomeService outcomes;
    @MockitoSpyBean SyntheticGateway gateway;
    @MockitoSpyBean PaymentStore store;
    @Autowired PaymentOperations operations;
    @Autowired ar.changeo.jobs.api.JobFundingAccess fundingAccess;
    @Autowired ar.changeo.jobs.api.JobFinancialAccess financialAccess;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean TokenDelivery delivery;
    @MockitoSpyBean PaymentFinancialAdapter financialAdapter;
    @MockitoSpyBean ar.changeo.jobs.JobService jobs;
    @Autowired PaymentQueryService queries;
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    final List<MockHttpSession> sessions = new ArrayList<>();
    Properties enrollment;
    MockHttpSession operator;

    @TestConfiguration static class Configuration { @Bean @Primary TestClock paymentClock() { return new TestClock(); } }
    static class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(System.getProperty("payments.test.database", "changeo_payments_01a11313"));
        Path existing = Path.of(".runtime/cfr-enrollment-20261006-1559.private");
        assertThat((Integer) Files.getAttribute(existing, "unix:uid")).isEqualTo(1000);
        assertThat((Integer) Files.getAttribute(existing, "unix:mode") & 0777).isEqualTo(0600);
        clock.advance(61); tokens.clear();
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true;
        });
        enrollment = new Properties();
        try (var input = Files.newBufferedReader(existing)) { enrollment.load(input); }
        operator = login(enrollment.getProperty("contact"), enrollment.getProperty("password")); operatorProof();
    }
    @AfterEach void cleanup() {
        if (enrollment != null) { enrollment.clear(); }
        for (var session : sessions) { if (!session.isInvalid()) { session.invalidate(); } }
        sessions.clear(); tokens.clear();
    }
    MockHttpSession login(String contact, String password) throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", contact).param("password", password))
                .andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
        sessions.add(session); return session;
    }
    void operatorProof() throws Exception {
        clock.advance(61);
        mvc.perform(post("/moderation/operator/mfa").session(operator).with(csrf()).param("code", totp(enrollment.getProperty("totpBase32")))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/moderation/operator/reauth").session(operator).with(csrf()).param("password", enrollment.getProperty("password"))).andExpect(status().is3xxRedirection());
    }
    AccountFixture author(boolean provider) throws Exception {
        String contact = "p5" + UUID.randomUUID() + "@example.test", password = "Synthetic-password-12345";
        UUID id = accounts.register(contact, password).accountId().value(); accounts.verifyContact(id, tokens.get(id + ":CONTACT"));
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "AGE").param("age", "30")).andExpect(status().is3xxRedirection());
        if (provider) { mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection()); }
        accounts.preferences(id, true, true); return new AccountFixture(id, login(contact, password));
    }
    ContentReference offer(AccountFixture provider) throws Exception {
        mvc.perform(post("/marketplace/compose").session(provider.session()).with(csrf()).param("type", "OFFER").param("subject", provider.id().toString())
                .param("title", "Impresión 3D sintética").param("body", "Pieza privada de verificación financiera").param("label", "GENERAL")).andExpect(status().isOk());
        var composition = (MarketplaceController.Composition) provider.session().getAttribute("marketplaceComposition");
        String location = mvc.perform(multipart("/marketplace/drafts").session(provider.session()).with(csrf()).param("stage", composition.stage().toString())
                .param("province", "Buenos Aires").param("area", "La Plata").param("modes", "REMOTE", "LOCAL", "SHIPPED")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        long version = jdbc.queryForObject("select version from marketplace_resource where id=?", Long.class, resource);
        UUID draft = jdbc.queryForObject("select current_revision from marketplace_resource where id=?", UUID.class, resource);
        mvc.perform(multipart("/marketplace/prepare").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", resource.toString())
                .param("revision", draft.toString()).param("version", Long.toString(version)).param("omitPreviousMedia", "true")).andExpect(status().is3xxRedirection());
        UUID revision = jdbc.queryForObject("select current_revision from marketplace_resource where id=?", UUID.class, resource);
        mvc.perform(post("/marketplace/submit").session(provider.session()).with(csrf()).param("resource", resource.toString()).param("revision", revision.toString())).andExpect(status().is3xxRedirection());
        var reference = new ContentReference(SurfaceKind.OFFER_BODY, resource, revision); approve(reference); return reference;
    }
    void approve(ContentReference reference) throws Exception {
        operatorProof();
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("kind", reference.ownerKind().name())
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
        UUID caseId = jdbc.queryForObject("select c.id from moderation_case c join moderation_snapshot p on p.id=c.submission_id where p.revision_id=?", UUID.class, reference.revisionId());
        mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("caseId", caseId.toString())
                .param("version", "0").param("action", "APPROVE").param("label", "GENERAL").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
        for (int count = 0; count < 100; count++) { if (!moderation.processOne()) { break; } }
        assertThat(jdbc.queryForObject("select s.state from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, reference.revisionId())).isEqualTo("APPROVED");
    }
    Journey negotiate() throws Exception { return negotiate(author(false), author(true)); }
    Journey negotiate(AccountFixture customer, AccountFixture provider) throws Exception {
        var listing = offer(provider);
        String location = mvc.perform(post("/conversations/respond").session(customer.session()).with(csrf()).param("listing", listing.resourceId().toString())
                .param("subject", customer.id().toString()).param("mode", "REMOTE")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID thread = UUID.fromString(location.substring("/conversations/".length(), location.indexOf('?')));
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString())
                .param("subject", provider.id().toString()).param("kind", "INITIAL").param("scope", "Alcance privado sin exposición monetaria <privado>").param("serviceAmount", "123.45")
                .param("deliveryAmount", "0").param("deliverables", "Una pieza completa")).andExpect(status().isOk());
        var composition = (AgreementController.Composition) provider.session().getAttribute("agreementComposition");
        String deadline = LocalDateTime.ofInstant(clock.instant().plusSeconds(86400), ZoneOffset.UTC).withSecond(0).withNano(0).toString();
        mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString()).param("arrangement", "REMOTE")
                .param("arrangementDescription", "Coordinación privada").param("completionDeadline", deadline).param("feedbackRounds", "2")
                .param("completionEvidence", "TEXT").param("evidenceDescription", "Descripción privada de la entrega").param("reviewWindowMinutes", "60").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var quote = jdbc.queryForObject("select id,current_revision from agreements_quote where job_id=?", (row, index) ->
                new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), job);
        mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", quote.resourceId().toString()).param("revision", quote.revisionId().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection()); approve(quote);
        long version = jdbc.queryForObject("select version from agreements_quote where id=?", Long.class, quote.resourceId());
        for (var side : List.of(customer, provider)) {
            mvc.perform(post("/agreements/accept").session(side.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", quote.resourceId().toString())
                    .param("revision", quote.revisionId().toString()).param("version", Long.toString(version)).param("subject", side.id().toString())).andExpect(status().is3xxRedirection());
        }
        assertThat(jdbc.queryForObject("select state from jobs_job where id=?", String.class, job)).isEqualTo("AGREED_AWAITING_FUNDING");
        return new Journey(customer, provider, job, quote);
    }
    ContentReference change(Journey journey) throws Exception {
        UUID thread = jdbc.queryForObject("select conversation_id from jobs_job where id=?", UUID.class, journey.job());
        var provider = journey.provider();
        mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("subject", provider.id().toString()).param("kind", "CHANGE").param("scope", "Nuevo alcance privado").param("serviceAmount", "124.45")
                .param("deliveryAmount", "0").param("deliverables", "Una pieza con alcance nuevo")).andExpect(status().isOk());
        var composition = (AgreementController.Composition) provider.session().getAttribute("agreementComposition");
        String deadline = LocalDateTime.ofInstant(clock.instant().plusSeconds(86400), ZoneOffset.UTC).withSecond(0).withNano(0).toString();
        mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString()).param("arrangement", "REMOTE")
                .param("arrangementDescription", "Coordinación privada nueva").param("completionDeadline", deadline).param("feedbackRounds", "2")
                .param("completionEvidence", "TEXT").param("evidenceDescription", "Entrega nueva completa").param("reviewWindowMinutes", "60").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var reference = jdbc.queryForObject("select id,current_revision from agreements_quote where job_id=? and kind='CHANGE' order by id::text limit 1", (row, index) ->
                new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), journey.job());
        mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection()); approve(reference); return reference;
    }
    void assentChange(Journey journey, ContentReference reference, AccountFixture side) throws Exception {
        long version = jdbc.queryForObject("select version from agreements_quote where id=?", Long.class, reference.resourceId());
        mvc.perform(post("/agreements/accept").session(side.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", reference.resourceId().toString())
                .param("revision", reference.revisionId().toString()).param("version", Long.toString(version)).param("subject", side.id().toString())).andExpect(status().is3xxRedirection());
    }
    AccountFixture minor() throws Exception {
        String contact = "p5minor" + UUID.randomUUID() + "@example.test", password = "Synthetic-password-12345";
        UUID id = accounts.register(contact, password).accountId().value(); accounts.verifyContact(id, tokens.get(id + ":CONTACT"));
        operatorProof();
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "AGE").param("age", "14")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "MINOR_DRAFT")).andExpect(status().is3xxRedirection());
        return new AccountFixture(id, login(contact, password));
    }
    UUID link(AccountFixture guardian, AccountFixture child) throws Exception {
        mvc.perform(post("/account/guardian/request").session(guardian.session()).with(csrf()).param("other", child.id().toString()).param("actingAsGuardian", "true")).andExpect(status().is3xxRedirection());
        UUID link = jdbc.queryForObject("select id from identity_guardian_link where guardian_id=? and minor_id=? and status='PENDING'", UUID.class, guardian.id(), child.id());
        operatorProof();
        mvc.perform(post("/admin/guardian/" + link + "/review").session(operator).with(csrf()).param("version", "0").param("verified", "true")).andExpect(status().is3xxRedirection());
        return link;
    }
    PaymentQueryService.JobView view(Journey journey, AccountFixture side) throws Exception {
        var result = mvc.perform(get("/payments/jobs/" + journey.job()).session(side.session()).param("subject", side.id().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(not(containsString("Alcance privado sin exposición")))).andReturn();
        return (PaymentQueryService.JobView) result.getModelAndView().getModel().get("money");
    }
    MockHttpServletRequestBuilder fund(Journey journey, UUID command, long credit) {
        long version = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        return post("/payments/fund").session(journey.customer().session()).with(csrf()).param("command", command.toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", journey.customer().id().toString()).param("jobVersion", Long.toString(version)).param("creditMinor", Long.toString(credit));
    }
    UUID queueFund(Journey journey, long credit) throws Exception {
        UUID command = UUID.randomUUID(); mvc.perform(fund(journey, command, credit)).andExpect(status().is3xxRedirection());
        return jdbc.queryForObject("select id from payments_operation where command_id=?", UUID.class, command);
    }
    void drain() { for (int count = 0; count < 100; count++) { if (!worker.processOne()) { return; } } throw new AssertionError("Payment worker did not reach bounded idle"); }
    void complete(Journey journey) throws Exception {
        long version = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        mvc.perform(post("/jobs/start").session(journey.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("version", Long.toString(version)).param("subject", journey.provider().id().toString())).andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/jobs/content").session(journey.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("subject", journey.provider().id().toString()).param("kind", "FINAL_DELIVERY").param("body", "Entrega privada real del trabajo sintético").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var reference = jdbc.queryForObject("select id,current_revision from jobs_authored where job_id=? and kind='FINAL_DELIVERY'", (row, index) ->
                new ContentReference(SurfaceKind.JOB_FEEDBACK, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), journey.job());
        mvc.perform(post("/jobs/submit").session(journey.provider().session()).with(csrf()).param("job", journey.job().toString()).param("subject", journey.provider().id().toString())
                .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection()); approve(reference);
        version = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        mvc.perform(post("/jobs/accept-final").session(journey.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("subject", journey.customer().id().toString()).param("version", Long.toString(version)).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
    }
    void release(Journey journey) throws Exception {
        release(journey, journey.provider());
    }
    void release(Journey journey, AccountFixture side) throws Exception {
        var current = view(journey, side);
        mvc.perform(post("/payments/release").session(side.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", side.id().toString()).param("jobVersion", Long.toString(current.jobVersion()))
                .param("moneyVersion", Long.toString(current.moneyVersion()))).andExpect(status().is3xxRedirection());
    }
    UUID settle(Journey journey, long amount, RefundDestination destination, boolean continuation) throws Exception {
        var current = view(journey, journey.customer()); UUID command = UUID.randomUUID();
        mvc.perform(proposal("/payments/propose", journey, journey.customer(), current, command, null, amount, destination, continuation)).andExpect(status().is3xxRedirection());
        UUID proposal = jdbc.queryForObject("select outcome_id from payments_receipt where command_id=?", UUID.class, command);
        for (var side : List.of(journey.customer(), journey.provider())) {
            mvc.perform(proposal("/payments/assent", journey, side, current, UUID.randomUUID(), proposal, amount, destination, continuation)).andExpect(status().is3xxRedirection());
        }
        return proposal;
    }
    void pause(Journey journey) throws Exception {
        var current = view(journey, journey.customer());
        mvc.perform(post("/jobs/support").session(journey.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("version", Long.toString(current.jobVersion())).param("subject", journey.customer().id().toString()).param("dispute", "true")).andExpect(status().is3xxRedirection());
    }
    void retryPayout(Journey journey) throws Exception {
        var current = view(journey, journey.provider());
        mvc.perform(post("/payments/payout-retry").session(journey.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", journey.provider().id().toString()).param("jobVersion", Long.toString(current.jobVersion()))
                .param("moneyVersion", Long.toString(current.moneyVersion()))).andExpect(status().is3xxRedirection());
    }
    <T> T as(AccountFixture fixture, java.util.function.Supplier<T> operation) {
        var previousSecurity = SecurityContextHolder.getContext(); var previousRequest = RequestContextHolder.getRequestAttributes();
        var request = new MockHttpServletRequest(); request.setSession(fixture.session());
        SecurityContextHolder.setContext((SecurityContext) fixture.session().getAttribute("SPRING_SECURITY_CONTEXT"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { return operation.get(); }
        finally { SecurityContextHolder.setContext(previousSecurity); RequestContextHolder.setRequestAttributes(previousRequest); }
    }
    MockHttpServletRequestBuilder proposal(String route, Journey journey, AccountFixture side, PaymentQueryService.JobView current, UUID command, UUID proposal,
            long amount, RefundDestination destination, boolean continuation) {
        var request = post(route).session(side.session()).with(csrf()).param("command", command.toString()).param("job", journey.job().toString()).param("revision", journey.quote().revisionId().toString())
                .param("subject", side.id().toString()).param("jobVersion", Long.toString(current.jobVersion())).param("moneyVersion", Long.toString(current.moneyVersion()))
                .param("refundMinor", Long.toString(amount)).param("remainingMinor", Long.toString(Math.subtractExact(current.remainingMinor(), amount))).param("election", destination.name()).param("continueJob", Boolean.toString(continuation));
        return proposal == null ? request : request.param("settlement", proposal.toString());
    }

    @Test void shouldRenderLatestMoneyAfterOutcomeCommitsBetweenDiscoveryAndProtection() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0);
        var before = store.root(store.operation(funding, false).rootId(), false);
        long payerVersion = jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id());
        var intercepted = new java.util.concurrent.atomic.AtomicBoolean();
        var target = org.springframework.test.util.AopTestUtils.<ar.changeo.jobs.JobService>getUltimateTargetObject(jobs);
        var pool = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                var found = invocation.callRealMethod();
                if (intercepted.compareAndSet(false, true)) {
                    assertThat(pool.submit(() -> worker.processOne()).get(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(store.operation(funding, false).state()).isEqualTo("SUCCESS");
                }
                return found;
            }).when(target).discover(eq(journey.job()));
            var current = view(journey, journey.customer());
            assertThat(current.money().funding()).isEqualTo(JobMoneyStatus.Funding.CONFIRMED_FULL);
            assertThat(current.moneyVersion()).isEqualTo(before.version() + 1);
            assertThat(current.fundedMinor()).isEqualTo(12345); assertThat(current.remainingMinor()).isEqualTo(12345);
            assertThat(current.operations()).singleElement().satisfies(operation -> { assertThat(operation.id()).isEqualTo(funding); assertThat(operation.state()).isEqualTo("SUCCESS"); });
            assertThat(jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id())).isEqualTo(payerVersion + 1);
            assertFundingBacking(journey, funding);
            assertThat(intercepted).isTrue();
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void shouldHoldOneConsistentMoneyViewWhileWorkerWaitsThenShowItsSingleCommittedOutcome() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0);
        var before = store.root(store.operation(funding, false).rootId(), false);
        long payerVersion = jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id());
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var holderPid = new AtomicInteger();
        var target = org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter);
        var intercepted = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            var status = invocation.callRealMethod();
            if (intercepted.compareAndSet(false, true)) { holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); held.countDown(); await(release); }
            return status;
        }).when(target).prepareViewForUpdate(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var query = pool.submit(() -> view(journey, journey.customer())); await(held);
            var processing = pool.submit(() -> worker.processOne()); awaitKnownBlocker(holderPid.get());
            assertThat(processing.isDone()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=?", Integer.class, before.id())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_ledger where root_id=?", Integer.class, before.id())).isZero();
            release.countDown(); var observed = query.get(5, TimeUnit.SECONDS);
            assertThat(observed.money().funding()).isEqualTo(JobMoneyStatus.Funding.PENDING); assertThat(observed.moneyVersion()).isEqualTo(before.version());
            assertThat(observed.fundedMinor()).isZero(); assertThat(observed.operations()).singleElement().satisfies(operation -> assertThat(operation.state()).isEqualTo("QUEUED"));
            assertThat(processing.get(5, TimeUnit.SECONDS)).isTrue();
            var current = view(journey, journey.provider()); assertThat(current.money().funding()).isEqualTo(JobMoneyStatus.Funding.CONFIRMED_FULL);
            assertThat(current.moneyVersion()).isEqualTo(before.version() + 1);
            assertThat(jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id())).isEqualTo(payerVersion + 1);
            assertFundingBacking(journey, funding);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void shouldRegisterOnlySelectedZeroIntentRootAndPayerWithoutEffectsFromRepeatedViews() throws Exception {
        var journey = negotiate(); var before = financialCounts();
        mvc.perform(get("/payments/jobs/" + journey.job()).session(journey.customer().session()).param("subject", journey.provider().id().toString())).andExpect(status().isForbidden());
        assertThat(financialCounts()).isEqualTo(before);
        var first = view(journey, journey.customer());
        assertThat(first.money().funding()).isEqualTo(JobMoneyStatus.Funding.UNFUNDED); assertThat(first.moneyVersion()).isZero();
        assertThat(first.fundedMinor()).isZero(); assertThat(first.operations()).isEmpty(); assertThat(first.proposals()).isEmpty();
        var after = financialCounts();
        for (var table : before.keySet()) { assertThat(after.get(table)).as(table).isEqualTo(before.get(table) + (Set.of("payments_root", "payments_balance").contains(table) ? 1L : 0L)); }
        assertThat(jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id())).isZero();
        String registered = financialFingerprint();
        assertThat(view(journey, journey.customer()).moneyVersion()).isZero(); assertThat(view(journey, journey.provider()).moneyVersion()).isZero();
        assertThat(financialFingerprint()).isEqualTo(registered);
        UUID funding = queueFund(journey, 0); drain();
        long payerVersion = jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id());
        clock.advance(61); var additional = negotiate(journey.customer(), author(true)); view(additional, additional.customer());
        assertThat(jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id())).isEqualTo(payerVersion);
        assertFundingBacking(journey, funding);
    }

    @Test void shouldRollbackNewViewRegistrationAndCallerSentinelAfterCaughtLatePreparationDenial() throws Exception {
        var journey = negotiate(); String before = financialFingerprint();
        var target = org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new PaymentFailure("Preparación financiera denegada después de proteger la selección exacta"); })
                .when(target).prepareViewForUpdate(any());
        jdbc.execute("create table payments_test_view_sentinel(id uuid primary key)"); UUID sentinel = UUID.randomUUID();
        try {
            assertThatThrownBy(() -> as(journey.customer(), () -> new TransactionTemplate(transactions).execute(status -> {
                try { queries.job(journey.job(), new ar.changeo.identity.api.SubjectId(journey.customer().id())); }
                catch (PaymentFailure denied) { jdbc.update("insert into payments_test_view_sentinel values(?)", sentinel); }
                return null;
            }))).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(jdbc.queryForObject("select count(*) from payments_test_view_sentinel", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_root where job_id=?", Integer.class, journey.job())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_balance where payer_id=?", Integer.class, journey.customer().id())).isZero();
        } finally { jdbc.execute("drop table payments_test_view_sentinel"); }
        assertThat(financialFingerprint()).isEqualTo(before);
    }

    @Test void shouldDenyAnIncompatiblePreparedViewBindingWithoutChangingRegisteredFacts() throws Exception {
        var journey = negotiate(); view(journey, journey.customer()); String before = financialFingerprint();
        var target = org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter);
        doAnswer(invocation -> {
            var facts = invocation.<ar.changeo.jobs.api.JobFinancialFacts>getArgument(0); var original = facts.agreement();
            var incompatible = new ar.changeo.jobs.api.FinancialAgreement(original.jobId(), original.agreement(), original.customer(), original.provider(),
                    original.customerActor(), original.providerActor(), original.legalPayer(), original.serviceMinor() + 100, original.deliveryMinor(), original.totalMinor() + 100,
                    original.currency(), original.policyScope(), original.policyVersion(), original.finalRule(), original.canonicalVersion());
            invocation.getArguments()[0] = new ar.changeo.jobs.api.JobFinancialFacts(incompatible, facts.state(), facts.finalDelivery(), facts.finalAcceptedBy());
            return invocation.callRealMethod();
        }).when(target).prepareViewForUpdate(any());
        mvc.perform(get("/payments/jobs/" + journey.job()).session(journey.customer().session()).param("subject", journey.customer().id().toString())).andExpect(status().isForbidden());
        assertThat(financialFingerprint()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldViewTheHeldSelectedRevisionAcrossZeroHistoryChangeInBothOrders(boolean changeFirst) throws Exception {
        var journey = negotiate(); var replacement = change(journey); assentChange(journey, replacement, journey.provider());
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var holderPid = new AtomicInteger();
        var target = org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter);
        var intercepted = new java.util.concurrent.atomic.AtomicBoolean();
        if (changeFirst) {
            doAnswer(invocation -> { var result = invocation.callRealMethod(); holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); held.countDown(); await(release); return result; })
                    .when(target).prepareReplacementForUpdate(any(), any());
        } else {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod();
                if (intercepted.compareAndSet(false, true)) { holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); held.countDown(); await(release); }
                return result;
            }).when(target).prepareViewForUpdate(any());
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                if (changeFirst) { assentChange(journey, replacement, journey.customer()); return null; }
                return view(journey, journey.customer());
            }); await(held);
            Future<?> second = pool.submit(() -> {
                if (changeFirst) { return view(journey, journey.customer()); }
                assentChange(journey, replacement, journey.customer()); return null;
            }); awaitKnownBlocker(holderPid.get());
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            release.countDown(); Object firstValue = first.get(5, TimeUnit.SECONDS), secondValue = second.get(5, TimeUnit.SECONDS);
            var observed = (PaymentQueryService.JobView) (changeFirst ? secondValue : firstValue);
            assertThat(observed.revisionId()).isEqualTo(changeFirst ? replacement.revisionId() : journey.quote().revisionId());
            assertThat(observed.totalMinor()).isEqualTo(changeFirst ? 12445 : 12345); assertThat(observed.money().funding()).isEqualTo(JobMoneyStatus.Funding.UNFUNDED);
            var selected = view(journey, journey.customer()); assertThat(selected.revisionId()).isEqualTo(replacement.revisionId()); assertThat(selected.totalMinor()).isEqualTo(12445);
            assertThat(jdbc.queryForObject("select count(*) from jobs_selection_history where job_id=?", Integer.class, journey.job())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select legal_payer from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(journey.customer().id());
            assertThat(jdbc.queryForObject("select count(*) from payments_root where job_id=? and lifecycle='ACTIVE' and original_revision=?", Integer.class, journey.job(), replacement.revisionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payments_root where job_id=? and lifecycle='PROSPECTIVE'", Integer.class, journey.job())).isZero();
            String unchanged = financialFingerprint(); UUID command = UUID.randomUUID();
            mvc.perform(fund(journey, command, 0)).andExpect(status().isForbidden());
            assertThat(financialFingerprint()).isEqualTo(unchanged);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void shouldRejectCapturedStaleMutationAfterCurrentViewRaceWithoutChangingFinancialFacts() throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey);
        var captured = view(journey, journey.customer()); release(journey); drain();
        String before = financialFingerprint();
        mvc.perform(post("/payments/release").session(journey.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", journey.customer().id().toString())
                .param("jobVersion", Long.toString(captured.jobVersion())).param("moneyVersion", Long.toString(captured.moneyVersion()))).andExpect(status().isForbidden());
        mvc.perform(proposal("/payments/propose", journey, journey.customer(), captured, UUID.randomUUID(), null, 345, RefundDestination.CREDIT, true)).andExpect(status().isForbidden());
        assertThat(financialFingerprint()).isEqualTo(before);
        assertThat(view(journey, journey.provider()).paidMinor()).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select destination from payments_operation where root_id=? and kind='PAYOUT'", String.class,
                jdbc.queryForObject("select id from payments_root where job_id=? and lifecycle='ACTIVE'", UUID.class, journey.job()))).isEqualTo("PROVIDER:" + journey.provider().id());
    }

    void assertFundingBacking(Journey journey, UUID funding) {
        UUID root = store.operation(funding, false).rootId();
        assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=? and kind='FUND'", Integer.class, root)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=?", Long.class, root)).isZero();
        assertThat(jdbc.queryForObject("select sum(confirmed_minor)::bigint from payments_allocation where root_id=?", Long.class, root)).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select sum(reserved_credit_minor)::bigint from payments_source where payer_id=?", Long.class, journey.customer().id())).isZero();
        assertThat(jdbc.queryForObject("select sum(confirmed_minor)::bigint from payments_source where origin_root=?", Long.class, root)).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=? and account='PLATFORM_EXPENSE'", Long.class, root)).isEqualTo(35);
    }

    Map<String, Long> financialCounts() {
        Map<String, Long> result = new TreeMap<>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename like 'payments_%' order by tablename", String.class)) {
            assertThat(table).matches("[a-z0-9_]+"); result.put(table, jdbc.queryForObject("select count(*) from \"" + table + "\"", Long.class));
        }
        return result;
    }

    String financialFingerprint() {
        StringBuilder state = new StringBuilder();
        for (String table : financialCounts().keySet()) {
            state.append(table).append(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text,'[]') from \"" + table + "\" t", String.class));
        }
        return PaymentStore.digest(state.toString());
    }

    @Test void shouldFundCompleteAndReleaseOnlyActualCustomerAcceptedFinalToSeparatePayout() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0);
        assertThat(view(journey, journey.customer()).money().funding()).isEqualTo(JobMoneyStatus.Funding.PENDING);
        var before = view(journey, journey.provider());
        mvc.perform(post("/payments/release").session(journey.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", journey.provider().id().toString()).param("jobVersion", Long.toString(before.jobVersion())).param("moneyVersion", Long.toString(before.moneyVersion()))).andExpect(status().isForbidden());
        drain(); assertThat(jdbc.queryForObject("select state from payments_operation where id=?", String.class, funding)).as("Committed funding state").isEqualTo("SUCCESS");
        assertThat(store.operation(funding, false).state()).isEqualTo("SUCCESS");
        assertThat(view(journey, journey.customer()).money().funding()).isEqualTo(JobMoneyStatus.Funding.CONFIRMED_FULL);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=? and account='PLATFORM_EXPENSE'", Long.class, store.operation(funding, false).rootId())).isEqualTo(35);
        complete(journey); assertThat(view(journey, journey.customer()).jobState()).isEqualTo(JobSnapshot.State.COMPLETED);
        assertThat(view(journey, journey.customer()).money().release()).isEqualTo(JobMoneyStatus.Release.NOT_RELEASED);
        release(journey); assertThat(worker.processOne()).isTrue();
        var released = view(journey, journey.customer()); assertThat(released.money().release()).isEqualTo(JobMoneyStatus.Release.RELEASED);
        assertThat(released.money().bankPayout()).isEqualTo(JobMoneyStatus.BankPayout.PENDING); assertThat(released.paidMinor()).isZero();
        drain(); var paid = view(journey, journey.provider()); assertThat(paid.paidMinor()).isEqualTo(12345); assertThat(paid.money().bankPayout()).isEqualTo(JobMoneyStatus.BankPayout.PAID);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=?", Long.class, store.operation(funding, false).rootId())).isZero();
        assertThat(jdbc.queryForObject("select fee_minor from payments_capital where currency='ARS'", Long.class)).isGreaterThanOrEqualTo(55);
    }

    @Test void shouldKeepSameCommandAndRejectOtherActorCsrfAndPrivateMetadataLeak() throws Exception {
        var journey = negotiate(); var stranger = author(false); UUID command = UUID.randomUUID();
        mvc.perform(fund(journey, command, 0)).andExpect(status().is3xxRedirection());
        mvc.perform(fund(journey, command, 0)).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select count(*) from payments_operation where command_id=?", Integer.class, command)).isEqualTo(1);
        mvc.perform(fund(journey, command, 1)).andExpect(status().isForbidden());
        mvc.perform(get("/payments/jobs/" + journey.job()).session(stranger.session()).param("subject", journey.customer().id().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/payments/credits").session(stranger.session()).param("payer", journey.customer().id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 0,00")));
        mvc.perform(post("/payments/fund").session(journey.customer().session()).param("job", journey.job().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/payments/jobs/" + journey.job()).param("subject", journey.customer().id().toString())).andExpect(status().is3xxRedirection());
        drain();
    }

    @Test void shouldPreserveSourceBackedCreditAcrossMixedFundingAndRepeatedCentRefunds() throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); settle(journey, 345, RefundDestination.CREDIT, true); drain();
        assertThat(view(journey, journey.customer()).money().funding()).isEqualTo(JobMoneyStatus.Funding.CONFIRMED_REMAINING);
        mvc.perform(get("/payments/credits").session(journey.customer().session())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 3,45")));
        clock.advance(61); var mixed = negotiate(journey.customer(), author(true)); UUID funding = queueFund(mixed, 345); drain();
        assertThat(store.operation(funding, false).creditMinor()).isEqualTo(345); assertThat(store.operation(funding, false).externalMinor()).isEqualTo(12000);
        for (int cent = 0; cent < 4; cent++) { settle(mixed, 1, RefundDestination.ORIGINAL_SOURCE, true); drain(); }
        var current = view(mixed, mixed.customer()); assertThat(current.refundedMinor()).isEqualTo(4); assertThat(current.remainingMinor()).isEqualTo(12341);
        assertThat(current.money().funding()).isEqualTo(JobMoneyStatus.Funding.CONFIRMED_REMAINING);
        assertThat(jdbc.queryForObject("select coalesce(sum(refunded_minor),0)::bigint from payments_allocation where root_id=?", Long.class, store.operation(funding, false).rootId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from payments_allocation where refunded_minor+reserved_refund_minor>confirmed_minor", Integer.class)).isZero();
    }

    @Test void shouldHoldUnknownReservationAcrossAbsenceDisputeAndLateExecution() throws Exception {
        var source = negotiate(); queueFund(source, 0); drain(); settle(source, 345, RefundDestination.CREDIT, true); drain();
        clock.advance(61); var journey = negotiate(source.customer(), author(true)); UUID funding = queueFund(journey, 345);
        var claim = worker.claim().orElseThrow(); assertThat(claim.operationId()).isEqualTo(funding);
        var request = worker.dispatch(claim).orElseThrow(); assertThat(store.operation(funding, false).state()).isEqualTo("DISPATCHING");
        assertThat(gateway.query(request).outcome()).isEqualTo(GatewayObservation.Outcome.ABSENT);
        clock.advance(31); var inquiry = worker.claim().orElseThrow(); assertThat(inquiry.inquiry()).isTrue(); worker.process(inquiry);
        assertThat(store.operation(funding, false).state()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select sum(reserved_credit_minor)::bigint from payments_source where payer_id=?", Long.class, journey.customer().id())).isEqualTo(345);
        mvc.perform(fund(journey, UUID.randomUUID(), 345)).andExpect(status().isForbidden());
        var current = view(journey, journey.customer());
        mvc.perform(post("/jobs/support").session(journey.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("version", Long.toString(current.jobVersion())).param("subject", journey.customer().id().toString()).param("dispute", "true")).andExpect(status().is3xxRedirection());
        var paused = view(journey, journey.customer());
        mvc.perform(post("/payments/propose").session(journey.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", journey.customer().id().toString()).param("jobVersion", Long.toString(paused.jobVersion()))
                .param("moneyVersion", Long.toString(paused.moneyVersion())).param("refundMinor", "1").param("remainingMinor", "0").param("election", "CREDIT").param("continueJob", "false")).andExpect(status().isForbidden());
        var observed = gateway.execute(request); assertThat(outcomes.record(observed)).isTrue(); assertThat(outcomes.record(observed)).isFalse();
        assertThat(store.operation(funding, false).state()).isEqualTo("SUCCESS");
        assertThat(view(journey, journey.customer()).money().funding()).isEqualTo(JobMoneyStatus.Funding.UNKNOWN);
        assertThat(jdbc.queryForObject("select sum(reserved_credit_minor)::bigint from payments_source where payer_id=?", Long.class, journey.customer().id())).isZero();
    }

    @Test void shouldPersistPermanentNoEffectAndAllowOnlyFreshLinkedFundingRetry() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); var claim = worker.claim().orElseThrow(); var request = worker.dispatch(claim).orElseThrow();
        var failed = gateway.recordOutcome(request, GatewayObservation.Outcome.FAILED_NO_EFFECT, 0);
        assertThat(outcomes.record(failed)).isTrue(); assertThat(outcomes.record(failed)).isFalse();
        assertThat(gateway.execute(request).outcome()).isEqualTo(GatewayObservation.Outcome.FAILED_NO_EFFECT);
        assertThat(store.operation(funding, false).state()).isEqualTo("FAILED_NO_EFFECT");
        UUID retry = queueFund(journey, 0); assertThat(store.operation(retry, false).predecessor()).contains(funding); drain();
        assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where stable_key=? and outcome='FAILED_NO_EFFECT'", Integer.class, request.stableKey())).isEqualTo(1);
    }

    @Test void shouldRejectUnauthenticatedWrongBindingAndGatewayInsideTransaction() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); var request = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        var success = gateway.execute(request);
        var invalid = new GatewayObservation(success.eventId(), request, success.outcome(), success.observedMinor(), success.observedAt(), "invalid");
        assertThatThrownBy(() -> outcomes.record(invalid)).isInstanceOf(PaymentFailure.class);
        var wrong = new GatewayRequest(request.operationId(), request.rootId(), request.stableKey(), request.kind(), request.amountMinor(), "PAYER:" + UUID.randomUUID(), request.currency(), request.fingerprint());
        var forged = new GatewayObservation(success.eventId(), wrong, success.outcome(), success.observedMinor(), success.observedAt(), success.signature());
        assertThatThrownBy(() -> outcomes.record(forged)).isInstanceOf(PaymentFailure.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> gateway.authenticate(success))).isInstanceOf(PaymentFailure.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> gateway.execute(request))).isInstanceOf(PaymentFailure.class);
        assertThat(outcomes.record(success)).isTrue(); assertThat(store.operation(funding, false).state()).isEqualTo("SUCCESS");
    }

    @Test void shouldPauseQueuedReleaseAndRestoreTheSameActuallyCompletedStateOnlyAfterBilateralContinuation() throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey); release(journey); pause(journey);
        assertThat(view(journey, journey.customer()).jobState()).isEqualTo(JobSnapshot.State.SUPPORT_HOLD);
        assertThat(jdbc.queryForObject("select pre_hold_state from jobs_job where id=?", String.class, journey.job())).isEqualTo("COMPLETED");
        drain(); assertThat(view(journey, journey.customer()).releasedMinor()).isZero();
        UUID settlement = settle(journey, 345, RefundDestination.ORIGINAL_SOURCE, true); drain();
        var current = view(journey, journey.customer()); assertThat(current.money().funding()).isEqualTo(JobMoneyStatus.Funding.CONFIRMED_REMAINING);
        mvc.perform(post("/jobs/resume").session(journey.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("version", Long.toString(current.jobVersion())).param("subject", journey.customer().id().toString()).param("settlement", settlement.toString())).andExpect(status().is3xxRedirection());
        assertThat(view(journey, journey.customer()).jobState()).isEqualTo(JobSnapshot.State.COMPLETED);
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='COMPLETED'", Integer.class, journey.job())).isEqualTo(1);
        release(journey); drain(); assertThat(view(journey, journey.customer()).paidMinor()).isEqualTo(12000);
    }

    @Test void shouldKeepFailedPayoutSeparateAndRetryOnlyTheOriginalProviderDestination() throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey); release(journey); assertThat(worker.processOne()).isTrue();
        var claim = worker.claim().orElseThrow(); var request = worker.dispatch(claim).orElseThrow(); assertThat(request.kind()).isEqualTo("PAYOUT");
        assertThat(outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.FAILED_NO_EFFECT, 0))).isTrue();
        var current = view(journey, journey.customer()); assertThat(current.releasedMinor()).isEqualTo(12345); assertThat(current.paidMinor()).isZero();
        mvc.perform(post("/payments/payout-retry").session(journey.customer().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", journey.customer().id().toString()).param("jobVersion", Long.toString(current.jobVersion()))
                .param("moneyVersion", Long.toString(current.moneyVersion()))).andExpect(status().isForbidden());
        retryPayout(journey); drain(); assertThat(view(journey, journey.customer()).paidMinor()).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select count(*) from payments_operation where predecessor=? and kind='PAYOUT' and state='SUCCESS'", Integer.class, request.operationId())).isEqualTo(1);
        assertThat(gateway.execute(request).outcome()).isEqualTo(GatewayObservation.Outcome.FAILED_NO_EFFECT);
    }

    @Test void shouldRefundAnUnpaidReleasedLiabilityWithoutInventingRecoveryCapitalAndPayOnlyReducedEntitlement() throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey); release(journey); assertThat(worker.processOne()).isTrue(); pause(journey);
        settle(journey, 345, RefundDestination.CREDIT, true); drain();
        var current = view(journey, journey.customer()); assertThat(current.releasedMinor()).isEqualTo(12345); assertThat(current.paidMinor()).isZero(); assertThat(current.remainingMinor()).isEqualTo(12000);
        UUID root = jdbc.queryForObject("select id from payments_root where job_id=?", UUID.class, journey.job());
        assertThat(jdbc.queryForObject("select coalesce(sum(signed_minor),0)::bigint from payments_ledger where root_id=? and account='PLATFORM_RECOVERY_EXPENSE'", Long.class, root)).isZero();
        retryPayout(journey); drain(); assertThat(view(journey, journey.customer()).paidMinor()).isEqualTo(12000);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=? and account='PROVIDER_LIABILITY'", Long.class, root)).isZero();
    }

    @Test void shouldBackPostpaidCreditsWithActualSeparateCapitalAndPreserveHistoricalPayout() throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey); release(journey); drain(); pause(journey);
        settle(journey, 345, RefundDestination.CREDIT, true); drain(); var current = view(journey, journey.customer());
        assertThat(current.refundedMinor()).isEqualTo(345); assertThat(current.paidMinor()).isEqualTo(12345); assertThat(current.money().bankPayout()).isEqualTo(JobMoneyStatus.BankPayout.PAID);
        UUID root = jdbc.queryForObject("select id from payments_root where job_id=?", UUID.class, journey.job());
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=? and account='PLATFORM_RECOVERY_EXPENSE'", Long.class, root)).isEqualTo(345);
        assertThat(jdbc.queryForObject("select sum(backing_minor)::bigint from payments_source where origin_root=?", Long.class, root)).isEqualTo(345);
        assertThat(jdbc.queryForObject("select sum(credit_minor)::bigint from payments_source where origin_root=?", Long.class, root)).isEqualTo(345);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=?", Long.class, root)).isZero();
    }

    @Test void shouldNotTreatPartialExternalFundingOrReorderedObservationAsFullBacking() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); var request = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        var partial = gateway.recordOutcome(request, GatewayObservation.Outcome.PARTIAL, 6000); assertThat(outcomes.record(partial)).isTrue();
        var current = view(journey, journey.customer()); assertThat(current.fundedMinor()).isEqualTo(6000); assertThat(current.money().funding()).isEqualTo(JobMoneyStatus.Funding.UNKNOWN);
        mvc.perform(post("/jobs/start").session(journey.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("version", Long.toString(current.jobVersion())).param("subject", journey.provider().id().toString())).andExpect(status().isForbidden());
        var success = gateway.recordOutcome(request, GatewayObservation.Outcome.SUCCESS, 12345); assertThat(outcomes.record(success)).isTrue();
        assertThat(outcomes.record(partial)).isFalse(); assertThat(outcomes.record(success)).isFalse();
        assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select sum(confirmed_minor)::bigint from payments_allocation where root_id=?", Long.class, store.operation(funding, false).rootId())).isEqualTo(12345);
    }

    @Test void shouldFreezeEverySourceDescendantAndRecordDeficitsWithoutDuplicateCreditOrReversal() throws Exception {
        var origin = negotiate(); UUID funding = queueFund(origin, 0); drain(); settle(origin, 345, RefundDestination.CREDIT, true); drain();
        clock.advance(61); var descendant = negotiate(origin.customer(), author(true)); queueFund(descendant, 345); drain();
        var request = operations.request(store.operation(funding, false));
        var reversed = gateway.recordOutcome(request, GatewayObservation.Outcome.REVERSED, 12345); assertThat(outcomes.record(reversed)).isTrue(); assertThat(outcomes.record(reversed)).isFalse();
        assertThat(view(origin, origin.customer()).money().funding()).isEqualTo(JobMoneyStatus.Funding.REVERSED);
        assertThat(view(descendant, descendant.customer()).money().funding()).isEqualTo(JobMoneyStatus.Funding.REVERSED);
        assertThat(jdbc.queryForObject("select sum(deficit_minor)::bigint from payments_root where payer_id=?", Long.class, origin.customer().id())).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select sum(reversed_minor)::bigint from payments_source where payer_id=?", Long.class, origin.customer().id())).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select count(*) from payments_source where payer_id=? and frozen", Integer.class, origin.customer().id())).isEqualTo(1);
        mvc.perform(get("/payments/credits").session(origin.customer().session())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 0,00")));
    }

    @Test void shouldRollbackBootstrapAndCallerSentinelAfterCaughtRecallDenial() throws Exception {
        var journey = negotiate(); operatorProof();
        UUID caseCommand = UUID.randomUUID();
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command", caseCommand.toString()).param("kind", journey.quote().ownerKind().name())
                .param("resource", journey.quote().resourceId().toString()).param("revision", journey.quote().revisionId().toString())).andExpect(status().is3xxRedirection());
        UUID caseId = jdbc.queryForObject("select outcome_id from moderation_receipt where command_id=?", UUID.class, caseCommand);
        long version = jdbc.queryForObject("select version from moderation_case where id=?", Long.class, caseId);
        mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("caseId", caseId.toString())
                .param("version", Long.toString(version)).param("action", "RECALL").param("label", "GENERAL").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
        long jobVersion = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        jdbc.execute("create table if not exists payments_test_sentinel(id uuid primary key)"); UUID sentinel = UUID.randomUUID();
        assertThatThrownBy(() -> as(journey.customer(), () -> new TransactionTemplate(transactions).execute(status -> {
            try { fundingAccess.requireFundable(journey.job(), journey.quote().revisionId(), new FundingSelection(UUID.randomUUID(), jobVersion, 0)); }
            catch (RuntimeException denied) { jdbc.update("insert into payments_test_sentinel values(?)", sentinel); }
            return null;
        }))).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from payments_test_sentinel where id=?", Integer.class, sentinel)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_root where job_id=?", Integer.class, journey.job())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_source where payer_id=?", Integer.class, journey.customer().id())).isZero();
        jdbc.execute("drop table payments_test_sentinel");
    }

    @Test void shouldRejectRawHistoryRetirementLedgerMutationAndUnbalancedCommit() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); drain(); UUID root = store.operation(funding, false).rootId();
        assertThatThrownBy(() -> jdbc.update("update payments_root set lifecycle='RETIRED',active_job=null,active_quote=null,active_revision=null,active_payer=null where id=?", root)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from payments_root where id=?", root)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update payments_ledger set signed_minor=signed_minor+1 where root_id=?", root)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            jdbc.update("insert into payments_ledger values(?,0,?,null,'JOB_LIABILITY',1,'ARS',?)", UUID.randomUUID(), root, java.sql.Timestamp.from(clock.instant())); return null;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("unbalanced financial batch");
        assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345);
    }

    @Test void shouldReplaceOnlyNeverFundedTermsAndKeepOriginalHistoryImmutable() throws Exception {
        var journey = negotiate(); var original = view(journey, journey.customer()); var replacement = change(journey);
        assentChange(journey, replacement, journey.provider());
        assertThat(jdbc.queryForObject("select accepted_revision from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(journey.quote().revisionId());
        assertThat(jdbc.queryForObject("select lifecycle from payments_root where original_revision=?", String.class, replacement.revisionId())).isEqualTo("PROSPECTIVE");
        assentChange(journey, replacement, journey.customer());
        assertThat(jdbc.queryForObject("select accepted_revision from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(replacement.revisionId());
        assertThat(jdbc.queryForObject("select lifecycle from payments_root where original_revision=?", String.class, journey.quote().revisionId())).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("select count(*) from payments_root where original_revision=? and active_job is null and active_revision is null", Integer.class, journey.quote().revisionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from jobs_selection_history where job_id=? and old_revision=? and new_revision=? and legal_payer=?", Integer.class,
                journey.job(), journey.quote().revisionId(), replacement.revisionId(), journey.customer().id())).isEqualTo(1);
        var next = new Journey(journey.customer(), journey.provider(), journey.job(), replacement);
        assertThat(view(next, next.customer()).totalMinor()).isEqualTo(12445); assertThat(original.totalMinor()).isEqualTo(12345);
        assertThatThrownBy(() -> jdbc.update("delete from jobs_selection_history where job_id=?", journey.job())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update agreements_revision set digest=? where id=?", "0".repeat(64), journey.quote().revisionId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        queueFund(next, 0); drain(); assertThat(view(next, next.customer()).fundedMinor()).isEqualTo(12445);
    }

    @Test void shouldPreserveFailedFinancialHistoryAndRequireSettlementThenAnotherJobForChange() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); var claim = worker.claim().orElseThrow(); var request = worker.dispatch(claim).orElseThrow();
        outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.FAILED_NO_EFFECT, 0));
        var replacement = change(journey); assentChange(journey, replacement, journey.provider()); assentChange(journey, replacement, journey.customer());
        assertThat(jdbc.queryForObject("select accepted_revision from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(journey.quote().revisionId());
        assertThat(store.operation(funding, false).state()).isEqualTo("FAILED_NO_EFFECT");
        assertThat(jdbc.queryForObject("select count(*) from jobs_selection_history where job_id=?", Integer.class, journey.job())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='CHANGE_REQUIRES_SETTLEMENT_AND_NEW_JOB'", Integer.class, journey.job())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select lifecycle from payments_root where original_revision=?", String.class, journey.quote().revisionId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select lifecycle from payments_root where original_revision=?", String.class, replacement.revisionId())).isEqualTo("PROSPECTIVE");
    }

    @ParameterizedTest @EnumSource(HigherTarget.class)
    void shouldApplyPartialFundingReleasePayoutAndPostpaidRefundWhileEarlierTargetsStayHeld(HigherTarget target) throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); UUID root = store.operation(funding, false).rootId();
        var fundingRequest = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        var partial = gateway.recordOutcome(fundingRequest, GatewayObservation.Outcome.PARTIAL, 6000);
        recordHeld(journey, target, partial, true, 1, List.of(root));
        assertThat(store.root(root, false).fundedMinor()).isEqualTo(6000); assertThat(store.operation(funding, false).state()).isEqualTo("UNKNOWN");
        var funded = gateway.recordOutcome(fundingRequest, GatewayObservation.Outcome.SUCCESS, fundingRequest.amountMinor());
        recordHeld(journey, target, funded, true, 1, List.of(root));
        recordHeld(journey, target, partial, false, 0, List.of(root)); recordHeld(journey, target, funded, false, 0, List.of(root));
        complete(journey); var stale = view(journey, journey.provider()); release(journey);
        mvc.perform(post("/payments/release").session(journey.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                .param("revision", journey.quote().revisionId().toString()).param("subject", journey.provider().id().toString()).param("jobVersion", Long.toString(stale.jobVersion()))
                .param("moneyVersion", Long.toString(stale.moneyVersion()))).andExpect(status().isForbidden());
        var releaseRequest = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        recordHeld(journey, target, gateway.recordOutcome(releaseRequest, GatewayObservation.Outcome.PARTIAL, 3000), true, 1, List.of(root));
        assertThat(store.root(root, false).releasedMinor()).isEqualTo(3000);
        recordHeld(journey, target, gateway.recordOutcome(releaseRequest, GatewayObservation.Outcome.SUCCESS, releaseRequest.amountMinor()), true, 1, List.of(root));
        assertThat(store.root(root, false).releasedMinor()).isEqualTo(12345); assertThat(store.root(root, false).paidMinor()).isZero();
        var payoutRequest = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        recordHeld(journey, target, gateway.recordOutcome(payoutRequest, GatewayObservation.Outcome.PARTIAL, 3000), true, 1, List.of(root));
        recordHeld(journey, target, gateway.recordOutcome(payoutRequest, GatewayObservation.Outcome.SUCCESS, payoutRequest.amountMinor()), true, 1, List.of(root));
        assertThat(store.root(root, false).paidMinor()).isEqualTo(12345); pause(journey);
        settle(journey, 600, RefundDestination.ORIGINAL_SOURCE, true);
        var cash = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        recordHeld(journey, target, gateway.recordOutcome(cash, GatewayObservation.Outcome.PARTIAL, 300), true, 1, List.of(root));
        assertThat(store.root(root, false).refundedMinor()).isEqualTo(300); assertThat(store.root(root, false).paused()).isTrue();
        recordHeld(journey, target, gateway.recordOutcome(cash, GatewayObservation.Outcome.SUCCESS, cash.amountMinor()), true, 1, List.of(root));
        settle(journey, 600, RefundDestination.CREDIT, true);
        var credit = worker.dispatch(worker.claim().orElseThrow()).orElseThrow(); assertThat(credit.amountMinor()).isZero();
        recordHeld(journey, target, gateway.execute(credit), true, 1, List.of(root));
        var current = store.root(root, false); assertThat(current.refundedMinor()).isEqualTo(1200); assertThat(current.paidMinor()).isEqualTo(12345);
        assertThat(current.bilateralContinuation()).isTrue(); assertThat(current.paused()).isFalse();
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=? and account='PLATFORM_RECOVERY_EXPENSE'", Long.class, root)).isEqualTo(1200);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=?", Long.class, root)).isZero();
    }

    @ParameterizedTest @EnumSource(HigherTarget.class)
    void shouldApplyMixedCreditAbsenceTombstoneRetryAndRefundWithoutEarlierLocks(HigherTarget target) throws Exception {
        var origin = negotiate(); queueFund(origin, 0); drain(); settle(origin, 345, RefundDestination.CREDIT, true); drain();
        var journey = negotiate(origin.customer(), author(true)); UUID failed = queueFund(journey, 345); UUID root = store.operation(failed, false).rootId();
        var request = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        recordHeld(journey, target, gateway.query(request), true, 1, List.of(root));
        assertThat(jdbc.queryForObject("select sum(reserved_credit_minor)::bigint from payments_source where payer_id=?", Long.class, origin.customer().id())).isEqualTo(345);
        var terminal = gateway.recordOutcome(request, GatewayObservation.Outcome.FAILED_NO_EFFECT, 0);
        recordHeld(journey, target, terminal, true, 1, List.of(root)); recordHeld(journey, target, gateway.execute(request), false, 0, List.of(root));
        assertThat(jdbc.queryForObject("select sum(reserved_credit_minor)::bigint from payments_source where payer_id=?", Long.class, origin.customer().id())).isZero();
        UUID retry = queueFund(journey, 345); assertThat(store.operation(retry, false).predecessor()).contains(failed);
        var retried = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        recordHeld(journey, target, gateway.recordOutcome(retried, GatewayObservation.Outcome.PARTIAL, 6000), true, 1, List.of(root));
        recordHeld(journey, target, gateway.recordOutcome(retried, GatewayObservation.Outcome.SUCCESS, retried.amountMinor()), true, 1, List.of(root));
        assertThat(store.root(root, false).fundedMinor()).isEqualTo(12345);
        settle(journey, 1000, RefundDestination.ORIGINAL_SOURCE, true);
        var refund = worker.dispatch(worker.claim().orElseThrow()).orElseThrow(); assertThat(refund.amountMinor()).isBetween(1L, 999L);
        recordHeld(journey, target, gateway.recordOutcome(refund, GatewayObservation.Outcome.PARTIAL, refund.amountMinor() / 2), true, 1, List.of(root));
        recordHeld(journey, target, gateway.recordOutcome(refund, GatewayObservation.Outcome.SUCCESS, refund.amountMinor()), true, 1, List.of(root));
        settle(journey, 1000, RefundDestination.CREDIT, true);
        var credit = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        recordHeld(journey, target, gateway.execute(credit), true, 1, List.of(root));
        assertThat(store.root(root, false).refundedMinor()).isEqualTo(2000);
        assertThat(jdbc.queryForObject("select sum(refunded_minor)::bigint from payments_allocation where root_id=?", Long.class, root)).isEqualTo(2000);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=?", Long.class, root)).isZero();
    }

    @ParameterizedTest @EnumSource(HigherTarget.class)
    void shouldComposeMultiRootReversalAndLostBackingDiscrepanciesBeforeRootAndPayerWrites(HigherTarget target) throws Exception {
        var origin = negotiate(); UUID funding = queueFund(origin, 0); drain(); settle(origin, 345, RefundDestination.CREDIT, true); drain();
        var descendant = negotiate(origin.customer(), author(true)); UUID consumed = queueFund(descendant, 345); drain();
        var roots = List.of(store.operation(funding, false).rootId(), store.operation(consumed, false).rootId());
        var reversed = gateway.recordOutcome(operations.request(store.operation(funding, false)), GatewayObservation.Outcome.REVERSED, 12345);
        recordHeld(origin, target, reversed, true, 1, roots); recordHeld(origin, target, reversed, false, 0, roots);
        assertThat(roots.stream().map(id -> store.root(id, false).deficitMinor()).reduce(0L, Math::addExact)).isEqualTo(12345);
        assertThat(roots.stream().allMatch(id -> store.root(id, false).paused())).isTrue();
        var secondOrigin = negotiate(); UUID secondFunding = queueFund(secondOrigin, 0); drain(); settle(secondOrigin, 345, RefundDestination.CREDIT, true); drain();
        var uncertain = negotiate(secondOrigin.customer(), author(true)); UUID uncertainFunding = queueFund(uncertain, 345);
        var uncertainRequest = worker.dispatch(worker.claim().orElseThrow()).orElseThrow();
        var secondRoots = List.of(store.operation(secondFunding, false).rootId(), store.operation(uncertainFunding, false).rootId());
        var conflicting = gateway.recordOutcome(operations.request(store.operation(secondFunding, false)), GatewayObservation.Outcome.REVERSED, 12345);
        recordHeld(secondOrigin, target, conflicting, false, 1, secondRoots);
        assertThat(secondRoots.stream().allMatch(id -> store.root(id, false).paused())).isTrue();
        assertThat(secondRoots.stream().map(id -> store.root(id, false).deficitMinor()).reduce(0L, Math::addExact)).isZero();
        recordHeld(uncertain, target, gateway.execute(uncertainRequest), true, 1, List.of(secondRoots.get(1)));
        assertThat(store.operation(uncertainFunding, false).state()).isEqualTo("RECOVERY");
        assertThat(store.root(secondRoots.get(1), false).fundedMinor()).isEqualTo(12000);
        assertThat(jdbc.queryForObject("select sum(credit_minor)::bigint from payments_source where payer_id=?", Long.class, secondOrigin.customer().id())).isEqualTo(345);
        assertThat(jdbc.queryForObject("select sum(reserved_credit_minor)::bigint from payments_source where payer_id=?", Long.class, secondOrigin.customer().id())).isEqualTo(345);
        assertThat(jdbc.queryForObject("select count(*) from payments_exception where root_id=? and reason='REVERSAL_RECONCILIATION'", Integer.class, secondRoots.getFirst())).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldRetainExactAcceptedFinalReleaseRightsAfterEitherOriginalSideIsRestricted(boolean customerRestricted) throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey);
        var side = customerRestricted ? journey.customer() : journey.provider(); operatorProof();
        mvc.perform(post("/admin/accounts/" + side.id() + "/restriction").session(operator).with(csrf()).param("active", "true")).andExpect(status().is3xxRedirection());
        assertThat(view(journey, side).finalAccepted()).isTrue();
        release(journey, side); drain(); assertThat(view(journey, side).paidMinor()).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='COMPLETED'", Integer.class, journey.job())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select destination from payments_operation where root_id=? and kind='PAYOUT'", String.class,
                jdbc.queryForObject("select id from payments_root where job_id=? and lifecycle='ACTIVE'", UUID.class, journey.job())))
                .isEqualTo("PROVIDER:" + journey.provider().id());
    }

    @Test void shouldKeepGuardianMoneyWithOriginalPayerAfterRevocationAndRelinkWithoutGrantingNewContentRights() throws Exception {
        var guardian = author(false); var child = minor(); UUID relationship = link(guardian, child);
        var journey = negotiate(new AccountFixture(child.id(), guardian.session()), author(true));
        queueFund(journey, 0); drain(); complete(journey);
        long version = jdbc.queryForObject("select version from identity_guardian_link where id=?", Long.class, relationship);
        mvc.perform(post("/account/guardian/" + relationship + "/revoke").session(guardian.session()).with(csrf()).param("version", Long.toString(version))).andExpect(status().is3xxRedirection());
        var newGuardian = author(false); link(newGuardian, child);
        mvc.perform(get("/payments/jobs/" + journey.job()).session(child.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/payments/jobs/" + journey.job()).session(newGuardian.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
        var retained = view(journey, journey.customer()); assertThat(retained.legalPayer()).isTrue();
        settle(journey, 345, RefundDestination.CREDIT, true); drain(); release(journey, journey.customer()); drain();
        assertThat(view(journey, journey.customer()).paidMinor()).isEqualTo(12000);
        mvc.perform(get("/payments/credits").session(guardian.session())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 3,45")));
        mvc.perform(get("/payments/credits").session(child.session())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 0,00")));
        mvc.perform(get("/payments/credits").session(newGuardian.session())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 0,00")));
        assertThat(jdbc.queryForObject("select legal_payer from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(guardian.id());
        mvc.perform(get("/jobs/" + journey.job()).session(guardian.session()).param("subject", child.id().toString())).andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("Entrega privada real del trabajo"))));
    }

    @Test void shouldKeepFailedOriginalRefundDestinationAndRequireFreshBilateralRetry() throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); settle(journey, 345, RefundDestination.ORIGINAL_SOURCE, true);
        var claim = worker.claim().orElseThrow(); var request = worker.dispatch(claim).orElseThrow(); UUID failed = claim.operationId();
        outcomes.record(gateway.recordOutcome(request, GatewayObservation.Outcome.FAILED_NO_EFFECT, 0));
        assertThat(view(journey, journey.customer()).refundedMinor()).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(credit_minor),0)::bigint from payments_source where payer_id=?", Long.class, journey.customer().id())).isZero();
        var current = view(journey, journey.customer()); UUID command = UUID.randomUUID();
        mvc.perform(proposal("/payments/propose", journey, journey.provider(), current, command, null, 345, RefundDestination.ORIGINAL_SOURCE, true)).andExpect(status().is3xxRedirection());
        UUID proposal = jdbc.queryForObject("select outcome_id from payments_receipt where command_id=?", UUID.class, command);
        mvc.perform(proposal("/payments/assent", journey, journey.provider(), current, UUID.randomUUID(), proposal, 345, RefundDestination.CREDIT, true)).andExpect(status().isForbidden());
        mvc.perform(proposal("/payments/assent", journey, journey.provider(), current, UUID.randomUUID(), proposal, 345, RefundDestination.ORIGINAL_SOURCE, true)).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select count(*) from payments_operation where settlement_id=?", Integer.class, proposal)).isZero();
        mvc.perform(proposal("/payments/assent", journey, journey.customer(), current, UUID.randomUUID(), proposal, 345, RefundDestination.ORIGINAL_SOURCE, true)).andExpect(status().is3xxRedirection());
        UUID retry = jdbc.queryForObject("select id from payments_operation where settlement_id=?", UUID.class, proposal);
        assertThat(store.operation(retry, false).predecessor()).contains(failed); assertThat(store.operation(retry, false).destination()).isEqualTo(store.operation(failed, false).destination());
        drain(); assertThat(view(journey, journey.customer()).refundedMinor()).isEqualTo(345);
        assertThat(jdbc.queryForObject("select coalesce(sum(credit_minor),0)::bigint from payments_source where payer_id=?", Long.class, journey.customer().id())).isZero();
    }

    @Test void shouldRequireGenuineSeparateAuditAuthorityAndFreshServerProof() throws Exception {
        var ordinary = author(false);
        mvc.perform(get("/payments/audit").session(ordinary.session()).param("mfaEpoch", "0")).andExpect(status().isForbidden());
        operatorProof();
        var context = (SecurityContext) operator.getAttribute("SPRING_SECURITY_CONTEXT");
        var principal = (ar.changeo.security.AccountPrincipal) context.getAuthentication().getPrincipal();
        var operatorFixture = new AccountFixture(principal.id(), operator);
        boolean allowed = as(operatorFixture, () -> {
            assertThat(proof.mfa(principal.id(), principal.epoch())).isTrue();
            assertThat(proof.freshPassword(principal.id(), principal.epoch())).isTrue();
            return identity.decide(ar.changeo.identity.api.PermissionRequest.self(new ar.changeo.identity.api.AccountId(principal.id()), ar.changeo.identity.api.Capability.ADMIN_AUDIT)).allowed();
        });
        var result = mvc.perform(get("/payments/audit").session(operator));
        if (allowed) { result.andExpect(status().isOk()).andExpect(content().string(not(containsString("Alcance privado sin exposición")))); }
        else { result.andExpect(status().isForbidden()); }
        Path evidence = Path.of("docs/agent-work/changeo-payments/developer/verification"); Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("existing-audit-authority-" + UUID.randomUUID() + ".json"),
                "{\"genuineExistingSession\":true,\"mfaCurrent\":true,\"passwordFresh\":true,\"adminAuditAllowed\":" + allowed + ",\"provisioned\":false,\"privateValuesExcluded\":true}\n", StandardOpenOption.CREATE_NEW);
        operator.removeAttribute("passwordEpoch"); operator.removeAttribute("passwordUntil");
        mvc.perform(get("/payments/audit").session(operator)).andExpect(status().isForbidden());
        operator.removeAttribute("mfaEpoch"); operator.removeAttribute("mfaUntil");
        mvc.perform(get("/payments/audit").session(operator)).andExpect(status().isForbidden());
    }

    @Test void shouldReadMetadataOnlyAuditWithTheActuallyHandedOffExistingAuditRoleAndExpireEachRealProof() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); drain();
        Path existing = Path.of(".runtime/operator-enrollment.private");
        assertThat((Integer) Files.getAttribute(existing, "unix:uid")).isEqualTo(1000);
        assertThat((Integer) Files.getAttribute(existing, "unix:mode") & 0777).isEqualTo(0600);
        var auditMaterial = new Properties();
        try (var input = Files.newBufferedReader(existing)) { auditMaterial.load(input); }
        try {
            var auditSession = login(auditMaterial.getProperty("contact"), auditMaterial.getProperty("password"));
            mvc.perform(get("/payments/audit").session(auditSession).param("mfaEpoch", "0")).andExpect(status().isForbidden());
            clock.advance(61);
            mvc.perform(post("/admin/mfa").session(auditSession).with(csrf()).param("code", totp(auditMaterial.getProperty("totpBase32")))).andExpect(redirectedUrl("/admin/reauth"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isForbidden());
            mvc.perform(post("/admin/reauth").session(auditSession).with(csrf()).param("password", auditMaterial.getProperty("password"))).andExpect(redirectedUrl("/admin/accounts"));
            var context = (SecurityContext) auditSession.getAttribute("SPRING_SECURITY_CONTEXT");
            var principal = (ar.changeo.security.AccountPrincipal) context.getAuthentication().getPrincipal();
            assertThat(as(new AccountFixture(principal.id(), auditSession), () -> {
                assertThat(proof.mfa(principal.id(), principal.epoch())).isTrue(); assertThat(proof.freshPassword(principal.id(), principal.epoch())).isTrue();
                return identity.decide(ar.changeo.identity.api.PermissionRequest.self(new ar.changeo.identity.api.AccountId(principal.id()), ar.changeo.identity.api.Capability.ADMIN_AUDIT)).allowed();
            })).isTrue();
            UUID after = null; boolean found = false; int pages = 0;
            do {
                var request = get("/payments/audit").session(auditSession); if (after != null) { request.param("after", after.toString()); }
                var result = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                        .andExpect(content().string(not(containsString("Alcance privado sin exposición")))).andExpect(content().string(not(containsString("Entrega privada real")))).andReturn();
                @SuppressWarnings("unchecked") var entries = (List<PaymentQueryService.AuditView>) result.getModelAndView().getModel().get("entries");
                assertThat(entries).hasSizeLessThanOrEqualTo(50);
                found |= entries.stream().anyMatch(entry -> entry.operationId().equals(funding) && entry.amountMinor() == 12345 && entry.state().equals("SUCCESS"));
                after = (UUID) result.getModelAndView().getModel().get("next"); pages++;
            } while (after != null && pages < 20);
            assertThat(after).isNull(); assertThat(found).isTrue();
            clock.advance(601);
            mvc.perform(post("/admin/mfa").session(auditSession).with(csrf()).param("code", totp(auditMaterial.getProperty("totpBase32")))).andExpect(redirectedUrl("/admin/reauth"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isForbidden());
            mvc.perform(post("/admin/reauth").session(auditSession).with(csrf()).param("password", auditMaterial.getProperty("password"))).andExpect(redirectedUrl("/admin/accounts"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isOk());
            clock.advance(601);
            mvc.perform(post("/admin/reauth").session(auditSession).with(csrf()).param("password", auditMaterial.getProperty("password"))).andExpect(redirectedUrl("/admin/accounts"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isForbidden());
            Path evidence = Path.of("docs/agent-work/changeo-payments/developer/verification"); Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("existing-foundation-audit-authority-" + UUID.randomUUID() + ".json"),
                    "{\"genuineExistingFoundationSession\":true,\"sourceKeyApplicable\":true,\"adminAuditAllowed\":true,\"mfaCurrent\":true,\"passwordFresh\":true,\"metadataOnly\":true,\"mfaAndPasswordExpiredDenials\":true,\"provisioned\":false,\"privateValuesExcluded\":true}\n", StandardOpenOption.CREATE_NEW);
        } finally { auditMaterial.clear(); }
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void shouldSerializeActualFundingIntentWithRestrictionOrGuardianRevocationInBothOrders(boolean changeFirst, boolean guardianChange) throws Exception {
        var customer = author(false); UUID relationship;
        Journey journey;
        if (guardianChange) {
            var child = minor(); relationship = link(customer, child);
            journey = negotiate(new AccountFixture(child.id(), customer.session()), author(true));
        } else { relationship = null; journey = negotiate(customer, author(true)); }
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        var context = (SecurityContext) operator.getAttribute("SPRING_SECURITY_CONTEXT");
        var operatorPrincipal = (ar.changeo.security.AccountPrincipal) context.getAuthentication().getPrincipal();
        var operatorFixture = new AccountFixture(operatorPrincipal.id(), operator);
        java.util.function.Supplier<Boolean> change = () -> {
            if (guardianChange) {
                long version = jdbc.queryForObject("select version from identity_guardian_link where id=?", Long.class, relationship);
                return as(customer, () -> { guardians.revoke(customer.id(), relationship, version); return true; });
            }
            return as(operatorFixture, () -> { admin.restriction(operatorPrincipal.id(), customer.id(), true); return true; });
        };
        if (!changeFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod();
                var agreement = invocation.getArgument(0, ar.changeo.jobs.api.FundingAgreement.class);
                if (agreement.jobId().equals(journey.job())) {
                    firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release);
                }
                return result;
            }).when(org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter)).prepareFundingForUpdate(any(), any());
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> first;
            java.util.concurrent.Future<?> second;
            if (changeFirst) {
                first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); change.get(); ready.countDown(); await(release); return true;
                }));
                await(ready);
                second = pool.submit(() -> mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden()).andReturn());
            } else {
                first = pool.submit(() -> mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().is3xxRedirection()).andReturn());
                await(ready); second = pool.submit(change::get);
            }
            awaitKnownBlocker(firstPid.get());
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            int expected = changeFirst ? 0 : 1;
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, journey.job())).isEqualTo(expected);
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth g join payments_root r on r.id=g.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='STARTED'", Integer.class, journey.job())).isZero();
            mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden());
            if (!changeFirst) { drain(); assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345); }
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldReserveSameBackedCreditForOnlyOneOfTwoConcurrentJobsInBothOrders(boolean secondJobFirst) throws Exception {
        var donor = negotiate(); queueFund(donor, 0); drain(); settle(donor, 345, RefundDestination.CREDIT, true); drain();
        var one = negotiate(donor.customer(), author(true)); var two = negotiate(donor.customer(), author(true));
        var winner = secondJobFirst ? two : one; var loser = secondJobFirst ? one : two;
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        doAnswer(invocation -> {
            var result = invocation.callRealMethod(); var agreement = invocation.getArgument(0, ar.changeo.jobs.api.FundingAgreement.class);
            if (agreement.jobId().equals(winner.job())) { firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); }
            return result;
        }).when(org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter)).prepareFundingForUpdate(any(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> mvc.perform(fund(winner, UUID.randomUUID(), 345)).andExpect(status().is3xxRedirection()).andReturn());
            await(ready);
            var second = pool.submit(() -> mvc.perform(fund(loser, UUID.randomUUID(), 345)).andExpect(status().isForbidden()).andReturn());
            awaitKnownBlocker(firstPid.get()); release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select coalesce(sum(reserved_credit_minor),0)::bigint from payments_source where payer_id=?", Long.class, donor.customer().id())).isEqualTo(345);
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, loser.job())).isZero();
            drain(); assertThat(view(winner, winner.customer()).fundedMinor()).isEqualTo(12345);
            assertThat(jdbc.queryForObject("select coalesce(sum(credit_minor+reserved_credit_minor),0)::bigint from payments_source where payer_id=?", Long.class, donor.customer().id())).isZero();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldProtectExactRevisionInCanonicalPhaseBeforeCapitalInBothConflictOrders(boolean revisionFirst) throws Exception {
        var journey = negotiate(); var firstReady = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1);
        var firstPid = new AtomicInteger(); var secondPid = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                jdbc.execute("set local statement_timeout='5s'");
                if (revisionFirst) { lockRevision(journey); }
                else { prepareRetained(journey); }
                firstReady.countDown(); await(releaseFirst); return true;
            }));
            await(firstReady);
            var second = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                jdbc.execute("set local statement_timeout='5s'");
                if (revisionFirst) { prepareRetained(journey); }
                else { lockRevision(journey); }
                return true;
            }));
            awaitBlocked(secondPid, firstPid);
            if (revisionFirst) {
                String currency = new TransactionTemplate(transactions).execute(status -> jdbc.queryForObject("select currency from payments_capital where currency='ARS' for update nowait", String.class));
                assertThat(currency).isEqualTo("ARS");
            } else {
                assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> jdbc.queryForObject("select currency from payments_capital where currency='ARS' for update nowait", String.class)))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                        .satisfies(failure -> assertThat(((org.postgresql.util.PSQLException) failure.getCause()).getSQLState()).isEqualTo("55P03"));
            }
            releaseFirst.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from payments_root where job_id=? and lifecycle='ACTIVE' and funded_minor=0", Integer.class, journey.job())).isEqualTo(1);
        } finally { releaseFirst.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldClaimDispatchAndApplyWithoutReacquiringUnchangedHigherOwnerForeignKeyTargets(boolean jobTarget) throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); UUID root = store.operation(funding, false).rootId();
        String before = jdbc.queryForObject("select encode(sha256(convert_to(row(job_id,original_quote,original_revision,payer_id,active_job,active_quote,active_revision,active_payer)::text,'UTF8')),'hex') from payments_root where id=?", String.class, root);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var holderPid = new AtomicInteger(); var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                if (jobTarget) { jdbc.queryForObject("select 1 from jobs_job where id=? for update", Integer.class, journey.job()); }
                else { lockRevision(journey); }
                held.countDown(); await(release); return true;
            }));
            await(held);
            var effect = pool.submit(() -> worker.processOne());
            try { assertThat(effect.get(3, TimeUnit.SECONDS)).isTrue(); }
            catch (TimeoutException timeout) {
                var waits = jdbc.queryForList("select wait_event,case when position('update payments_root set version' in query)>0 then 'ROOT_VERSION' when position('update payments_root set funded_minor' in query)>0 then 'ROOT_FUNDED' when position('payments_root' in query)>0 then 'PAYMENTS_ROOT' when position('payments_source' in query)>0 then 'PAYMENTS_SOURCE' when position('payments_operation' in query)>0 then 'PAYMENTS_OPERATION' else 'OTHER' end as statement_owner from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))", holderPid.get());
                throw new AssertionError("Higher-owner wait: target=" + (jobTarget ? "JOBS_JOB" : "AGREEMENTS_REVISION") + "; " + waits + "; operation=" + store.operation(funding, false).state(), timeout);
            }
            assertThat(store.operation(funding, false).state()).isEqualTo("SUCCESS");
            String after = jdbc.queryForObject("select encode(sha256(convert_to(row(job_id,original_quote,original_revision,payer_id,active_job,active_quote,active_revision,active_payer)::text,'UTF8')),'hex') from payments_root where id=?", String.class, root);
            assertThat(after).isEqualTo(before); release.countDown(); assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @CsvSource({"true,1", "true,2", "false,1", "false,2"})
    void shouldDistinguishSingleRootWriteFromRepeatedWriteForeignKeyRecheck(boolean jobTarget, int updates) throws Exception {
        var journey = negotiate();
        new TransactionTemplate(transactions).executeWithoutResult(status -> prepareRetained(journey));
        UUID root = jdbc.queryForObject("select id from payments_root where job_id=?", UUID.class, journey.job());
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var firstUpdate = new CountDownLatch(1);
        var holderPid = new AtomicInteger(); var writerPid = new AtomicInteger(); var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                if (jobTarget) { jdbc.queryForObject("select 1 from jobs_job where id=? for update", Integer.class, journey.job()); }
                else { lockRevision(journey); }
                held.countDown(); await(release); return true;
            }));
            await(held);
            var writer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                writerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                store.protectExisting(root);
                jdbc.update("update payments_root set version=version+1 where id=?", root); firstUpdate.countDown();
                if (updates == 2) { jdbc.update("update payments_root set version=version+1 where id=?", root); }
                return true;
            }));
            await(firstUpdate);
            if (updates == 1) { assertThat(writer.get(2, TimeUnit.SECONDS)).isTrue(); }
            else { awaitBlocked(writerPid, holderPid); assertThat(writer.isDone()).isFalse(); }
            release.countDown(); assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(writer.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select version from payments_root where id=?", Long.class, root)).isEqualTo(updates);
            assertThat(jdbc.queryForObject("select count(*) from payments_operation where root_id=?", Integer.class, root)).isZero();
            assertThat(jdbc.queryForObject("select funded_minor+refunded_minor+released_minor+paid_minor from payments_root where id=?", Long.class, root)).isZero();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void shouldDistinguishSinglePayerWriteFromRepeatedWriteIdentityForeignKeyRecheck(int updates) throws Exception {
        var journey = negotiate(); new TransactionTemplate(transactions).executeWithoutResult(status -> prepareRetained(journey));
        UUID root = jdbc.queryForObject("select id from payments_root where job_id=?", UUID.class, journey.job());
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var firstUpdate = new CountDownLatch(1);
        var holderPid = new AtomicInteger(); var writerPid = new AtomicInteger(); var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                jdbc.queryForObject("select 1 from identity_account where id=? for update", Integer.class, journey.customer().id());
                held.countDown(); await(release); return true;
            }));
            await(held);
            var writer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                writerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); store.protectExisting(root);
                jdbc.update("update payments_balance set version=version+1 where payer_id=?", journey.customer().id()); firstUpdate.countDown();
                if (updates == 2) { jdbc.update("update payments_balance set version=version+1 where payer_id=?", journey.customer().id()); }
                return true;
            }));
            await(firstUpdate);
            if (updates == 1) { assertThat(writer.get(2, TimeUnit.SECONDS)).isTrue(); }
            else { awaitBlocked(writerPid, holderPid); assertThat(writer.isDone()).isFalse(); }
            release.countDown(); assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(writer.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, journey.customer().id())).isEqualTo(updates);
            assertThat(jdbc.queryForObject("select count(*) from payments_operation where root_id=?", Integer.class, root)).isZero();
            assertThat(jdbc.queryForObject("select funded_minor+refunded_minor+released_minor+paid_minor from payments_root where id=?", Long.class, root)).isZero();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    enum HigherTarget { JOB, REVISION, PAYER_IDENTITY }
    @ParameterizedTest @EnumSource(HigherTarget.class)
    void shouldReconcileActuallyCommittedGatewayEffectAfterLostResultWithoutEarlierLocks(HigherTarget target) throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); UUID root = store.operation(funding, false).rootId();
        var claim = worker.claim().orElseThrow();
        doAnswer(invocation -> { invocation.callRealMethod(); throw new PaymentFailure("Resultado sintético perdido después del efecto persistido"); }).when(gateway).execute(any());
        moneyHeld(journey, target, () -> { worker.process(claim); return true; }, true, 1, List.of(root));
        assertThat(store.operation(funding, false).state()).isEqualTo("UNKNOWN"); assertThat(store.root(root, false).fundedMinor()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where operation_id=? and outcome='SUCCESS'", Integer.class, funding)).isEqualTo(1);
        clock.advance(31); var inquiry = worker.claim().orElseThrow(); assertThat(inquiry.inquiry()).isTrue();
        moneyHeld(journey, target, () -> { worker.process(inquiry); return true; }, true, 1, List.of(root));
        assertThat(store.operation(funding, false).state()).isEqualTo("SUCCESS"); assertThat(store.root(root, false).fundedMinor()).isEqualTo(12345);
        assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=?", Long.class, root)).isZero();
    }
    @ParameterizedTest @EnumSource(HigherTarget.class)
    void shouldKeepAuthenticatedWrongKindReversalAsDiscrepancyAndCancelTheComputedPayout(HigherTarget target) throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey); release(journey);
        var request = worker.dispatch(worker.claim().orElseThrow()).orElseThrow(); UUID root = request.rootId();
        var lateSuccess = gateway.execute(request); var reversed = gateway.recordOutcome(request, GatewayObservation.Outcome.REVERSED, request.amountMinor());
        recordHeld(journey, target, reversed, false, 1, List.of(root));
        assertThat(store.root(root, false).paused()).isTrue(); assertThat(store.root(root, false).releasedMinor()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_exception where root_id=? and reason='REVERSAL_BINDING_CONFLICT'", Integer.class, root)).isEqualTo(1);
        recordHeld(journey, target, lateSuccess, true, 1, List.of(root));
        assertThat(store.root(root, false).releasedMinor()).isEqualTo(12345); assertThat(store.root(root, false).paidMinor()).isZero();
        assertThat(store.operations(root, false).stream().filter(value -> value.kind().equals("PAYOUT")).toList()).hasSize(1).allMatch(value -> value.state().equals("CANCELLED"));
        assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=? and kind='PAYOUT'", Integer.class, root)).isZero();
        recordHeld(journey, target, lateSuccess, false, 0, List.of(root));
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldRediscoverCompleteSourceExposureWhenChargebackAndCreditSpendCompeteInBothOrders(boolean reversalFirst) throws Exception {
        var origin = negotiate(); UUID funding = queueFund(origin, 0); drain(); settle(origin, 345, RefundDestination.CREDIT, true); drain();
        var next = negotiate(origin.customer(), author(true)); var observation = gateway.recordOutcome(operations.request(store.operation(funding, false)), GatewayObservation.Outcome.REVERSED, 12345);
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        if (reversalFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); return result;
            }).when(store).saveOutcome(any(), any());
        } else {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); return result;
            }).when(org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter)).prepareFundingForUpdate(any(), any());
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                if (reversalFirst) { return outcomes.record(observation); }
                mvc.perform(fund(next, UUID.randomUUID(), 345)).andExpect(status().is3xxRedirection()); return true;
            });
            await(ready);
            var second = pool.submit(() -> {
                if (reversalFirst) { mvc.perform(fund(next, UUID.randomUUID(), 345)).andExpect(status().isForbidden()); return true; }
                return outcomes.record(observation);
            });
            awaitKnownBlocker(firstPid.get()); release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select sum(deficit_minor)::bigint from payments_root where payer_id=?", Long.class, origin.customer().id())).isEqualTo(12345);
            assertThat(jdbc.queryForObject("select sum(reserved_credit_minor)::bigint from payments_source where payer_id=?", Long.class, origin.customer().id())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth g join payments_root r on r.id=g.root_id where r.job_id=?", Integer.class, next.job())).isZero();
            if (!reversalFirst) {
                UUID nextRoot = jdbc.queryForObject("select id from payments_root where job_id=?", UUID.class, next.job());
                assertThat(store.operations(nextRoot, false)).allMatch(value -> value.state().equals("CANCELLED"));
                assertThat(store.root(nextRoot, false).paused()).isTrue();
            }
            mvc.perform(get("/payments/credits").session(origin.customer().session())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 0,00")));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void shouldSerializeActualChargebackWithBilateralCashRefundOrFinalReleaseInBothOrders(boolean reversalFirst, boolean refund) throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); drain(); complete(journey);
        var initial = view(journey, journey.provider());
        PaymentQueryService.JobView proposed = null; UUID proposalId = null;
        if (refund) {
            proposed = view(journey, journey.customer()); UUID proposalCommand = UUID.randomUUID();
            mvc.perform(proposal("/payments/propose", journey, journey.customer(), proposed, proposalCommand, null, 345, RefundDestination.ORIGINAL_SOURCE, true)).andExpect(status().is3xxRedirection());
            proposalId = jdbc.queryForObject("select outcome_id from payments_receipt where command_id=?", UUID.class, proposalCommand);
            mvc.perform(proposal("/payments/assent", journey, journey.customer(), proposed, UUID.randomUUID(), proposalId, 345, RefundDestination.ORIGINAL_SOURCE, true)).andExpect(status().is3xxRedirection());
        }
        final var draft = proposed; final var settlement = proposalId;
        var observation = gateway.recordOutcome(operations.request(store.operation(funding, false)), GatewayObservation.Outcome.REVERSED, 12345);
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        doAnswer(invocation -> {
            var result = invocation.callRealMethod(); firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); return result;
        }).when(store).saveOutcome(any(), any());
        java.util.concurrent.Callable<Boolean> monetaryCommand = () -> {
            if (refund) {
                var result = mvc.perform(proposal("/payments/assent", journey, journey.provider(), draft, UUID.randomUUID(), settlement, 345, RefundDestination.ORIGINAL_SOURCE, true));
                if (reversalFirst) { result.andExpect(status().isForbidden()); } else { result.andExpect(status().is3xxRedirection()); }
            } else if (reversalFirst) {
                mvc.perform(post("/payments/release").session(journey.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", journey.job().toString())
                        .param("revision", journey.quote().revisionId().toString()).param("subject", journey.provider().id().toString()).param("jobVersion", Long.toString(initial.jobVersion()))
                        .param("moneyVersion", Long.toString(initial.moneyVersion()))).andExpect(status().isForbidden());
            } else { release(journey); }
            return true;
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> reversalFirst ? outcomes.record(observation) : monetaryCommand.call()); await(ready);
            var second = pool.submit(() -> reversalFirst ? monetaryCommand.call() : outcomes.record(observation));
            awaitKnownBlocker(firstPid.get()); release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            UUID root = store.operation(funding, false).rootId(); var money = store.root(root, false);
            assertThat(money.refundedMinor()).isZero(); assertThat(money.releasedMinor()).isZero(); assertThat(money.paidMinor()).isZero(); assertThat(money.deficitMinor()).isEqualTo(12345);
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=? and kind in ('REFUND','RELEASE','PAYOUT')", Integer.class, root)).isZero();
            assertThat(store.operations(root, false).stream().filter(value -> !value.kind().equals("FUND")).toList()).allMatch(value -> value.state().equals("CANCELLED"));
            assertThat(jdbc.queryForObject("select sum(reserved_refund_minor)::bigint from payments_allocation where root_id=?", Long.class, root)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='COMPLETED'", Integer.class, journey.job())).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldSerializeFundingAndSecondWholeChangeAssentInBothOrders(boolean changeFirst) throws Exception {
        var journey = negotiate(); var replacement = change(journey); assentChange(journey, replacement, journey.provider());
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        var target = org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter);
        if (changeFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); return result;
            }).when(target).prepareReplacementForUpdate(any(), any());
        } else {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); return result;
            }).when(target).prepareFundingForUpdate(any(), any());
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                if (changeFirst) { assentChange(journey, replacement, journey.customer()); }
                else { mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().is3xxRedirection()); }
                return true;
            });
            await(ready);
            var second = pool.submit(() -> {
                if (changeFirst) { mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden()); }
                else { assentChange(journey, replacement, journey.customer()); }
                return true;
            });
            awaitKnownBlocker(firstPid.get());
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select accepted_revision from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(changeFirst ? replacement.revisionId() : journey.quote().revisionId());
            assertThat(jdbc.queryForObject("select count(*) from jobs_selection_history where job_id=?", Integer.class, journey.job())).isEqualTo(changeFirst ? 1 : 0);
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='CHANGE_REQUIRES_SETTLEMENT_AND_NEW_JOB'", Integer.class, journey.job())).isEqualTo(changeFirst ? 0 : 1);
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth g join payments_root r on r.id=g.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            if (!changeFirst) { drain(); assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345); }
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void shouldProtectBothChangeHistoryRevisionKeysBeforeMoneyInBothOrders(boolean revisionFirst, boolean newTarget) throws Exception {
        var journey = negotiate(); var replacement = change(journey); assentChange(journey, replacement, journey.provider());
        var reference = newTarget ? replacement : journey.quote(); var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstPid = new AtomicInteger(); var secondPid = new AtomicInteger(); var pool = Executors.newFixedThreadPool(2);
        if (!revisionFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); return result;
            }).when(org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter)).prepareReplacementForUpdate(any(), any());
        }
        try {
            var first = pool.submit(() -> {
                if (revisionFirst) {
                    return new TransactionTemplate(transactions).execute(status -> {
                        firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        jdbc.queryForObject("select 1 from agreements_revision where quote_id=? and id=? for update", Integer.class, reference.resourceId(), reference.revisionId());
                        ready.countDown(); await(release); return true;
                    });
                }
                assentChange(journey, replacement, journey.customer()); return true;
            });
            await(ready);
            var second = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                if (revisionFirst) {
                    try { assentChange(journey, replacement, journey.customer()); } catch (Exception failure) { throw new IllegalStateException(failure); }
                } else {
                    jdbc.queryForObject("select 1 from agreements_revision where quote_id=? and id=? for update", Integer.class, reference.resourceId(), reference.revisionId());
                }
                return true;
            }));
            awaitBlocked(secondPid, firstPid);
            if (revisionFirst) {
                String currency = new TransactionTemplate(transactions).execute(status -> jdbc.queryForObject("select currency from payments_capital where currency='ARS' for update nowait", String.class));
                assertThat(currency).isEqualTo("ARS");
            }
            assertThat(jdbc.queryForObject("select count(*) from jobs_selection_history where job_id=?", Integer.class, journey.job())).isZero();
            release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from jobs_selection_history where job_id=?", Integer.class, journey.job())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select accepted_revision from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(replacement.revisionId());
            assertThat(jdbc.queryForObject("select legal_payer from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(journey.customer().id());
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldSerializeActualFundingAndActualModerationRecallInBothOrders(boolean recallFirst) throws Exception {
        var journey = negotiate(); operatorProof(); UUID command = UUID.randomUUID();
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command", command.toString()).param("kind", journey.quote().ownerKind().name())
                .param("resource", journey.quote().resourceId().toString()).param("revision", journey.quote().revisionId().toString())).andExpect(status().is3xxRedirection());
        UUID caseId = jdbc.queryForObject("select outcome_id from moderation_receipt where command_id=?", UUID.class, command);
        long version = jdbc.queryForObject("select version from moderation_case where id=?", Long.class, caseId);
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        if (!recallFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release); return result;
            }).when(org.springframework.test.util.AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter)).prepareFundingForUpdate(any(), any());
        }
        java.util.concurrent.Callable<Boolean> recall = () -> {
            mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("caseId", caseId.toString())
                    .param("version", Long.toString(version)).param("action", "RECALL").param("label", "GENERAL").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
            return true;
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                if (recallFirst) {
                    return new TransactionTemplate(transactions).execute(status -> {
                        firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        try { recall.call(); } catch (Exception failure) { throw new IllegalStateException(failure); }
                        ready.countDown(); await(release); return true;
                    });
                }
                mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().is3xxRedirection()); return true;
            });
            await(ready);
            var second = pool.submit(() -> {
                if (recallFirst) { mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden()); return true; }
                return recall.call();
            });
            awaitKnownBlocker(firstPid.get()); release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, journey.job())).isEqualTo(recallFirst ? 0 : 1);
            assertThat(jdbc.queryForObject("select approved_revision from agreements_quote where id=?", UUID.class, journey.quote().resourceId())).isNull();
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='STARTED'", Integer.class, journey.job())).isZero();
            if (!recallFirst) { drain(); assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345); }
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    void recordHeld(Journey journey, HigherTarget target, GatewayObservation observation, boolean expected, long versionAdvance, List<UUID> roots) throws Exception {
        moneyHeld(journey, target, () -> outcomes.record(observation), expected, versionAdvance, roots);
    }
    void moneyHeld(Journey journey, HigherTarget target, java.util.function.Supplier<Boolean> operation, boolean expected, long versionAdvance, List<UUID> roots) throws Exception {
        var before = roots.stream().map(id -> store.root(id, false)).toList();
        var payer = before.getFirst().agreement().legalPayer();
        long payerVersion = jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, payer.value());
        var bindings = roots.stream().map(this::bindingDigest).toList();
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var holderPid = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                switch (target) {
                    case JOB -> jdbc.queryForObject("select 1 from jobs_job where id=? for update", Integer.class, journey.job());
                    case REVISION -> lockRevision(journey);
                    case PAYER_IDENTITY -> jdbc.queryForObject("select 1 from identity_account where id=? for update", Integer.class, payer.value());
                }
                held.countDown(); await(release); return true;
            }));
            await(held);
            var callback = pool.submit(operation::get);
            try { assertThat(callback.get(3, TimeUnit.SECONDS)).isEqualTo(expected); }
            catch (TimeoutException timeout) {
                var waits = jdbc.queryForList("select wait_event from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))", holderPid.get());
                throw new AssertionError("Money-only observation waited on held earlier target " + target + ": " + waits, timeout);
            }
            assertThat(holder.isDone()).isFalse();
            for (int index = 0; index < roots.size(); index++) {
                assertThat(store.root(roots.get(index), false).version()).isEqualTo(Math.addExact(before.get(index).version(), versionAdvance));
                assertThat(bindingDigest(roots.get(index))).isEqualTo(bindings.get(index));
            }
            assertThat(jdbc.queryForObject("select version from payments_balance where payer_id=?", Long.class, payer.value())).isEqualTo(Math.addExact(payerVersion, versionAdvance));
            release.countDown(); assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    String bindingDigest(UUID root) {
        return jdbc.queryForObject("select encode(sha256(convert_to(row(job_id,original_quote,original_revision,payer_id,customer_id,provider_id,customer_actor,provider_actor,currency,service_minor,delivery_minor,total_minor,policy_scope,policy_version,final_rule,canonical_version,lifecycle,active_job,active_quote,active_revision,active_payer)::text,'UTF8')),'hex') from payments_root where id=?", String.class, root);
    }
    void lockRevision(Journey journey) { jdbc.queryForObject("select 1 from agreements_revision where quote_id=? and id=? for update", Integer.class, journey.quote().resourceId(), journey.quote().revisionId()); }
    void prepareRetained(Journey journey) {
        long version = jdbc.queryForObject("select version from jobs_job where id=?", Long.class, journey.job());
        as(journey.customer(), () -> financialAccess.requireSettlement(journey.job(), journey.quote().revisionId(), new ar.changeo.identity.api.SubjectId(journey.customer().id()),
                new SettlementPreparation(UUID.randomUUID(), version, 0)));
    }
    void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    void awaitBlocked(AtomicInteger waiting, AtomicInteger blocker) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            if (waiting.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject("select ?=any(pg_blocking_pids(?))", Boolean.class, blocker.get(), waiting.get()))) { return; }
            Thread.yield();
        }
        throw new AssertionError("Exact known PostgreSQL backends did not enter the required blocking schedule");
    }
    void awaitKnownBlocker(int blocker) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            int waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))", Integer.class, blocker);
            if (waiting == 1) { return; }
            Thread.yield();
        }
        throw new AssertionError("The actual competing command did not wait on its known protected backend");
    }

    String totp(String encoded) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; var bytes = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
        for (char character : encoded.toCharArray()) { buffer = (buffer << 5) | alphabet.indexOf(character); bits += 5; if (bits >= 8) { bits -= 8; bytes.write((buffer >> bits) & 255); } }
        Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(bytes.toByteArray(), "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond() / 30).array()); int offset = hash[19] & 15;
        int value = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16) | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
        return String.format(Locale.ROOT, "%06d", value % 1000000);
    }
    record AccountFixture(UUID id, MockHttpSession session) {}
    record Journey(AccountFixture customer, AccountFixture provider, UUID job, ContentReference quote) {}
}
