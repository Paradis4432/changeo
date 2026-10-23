package ar.changeo.conversations;

import ar.changeo.ChangeoApplication;
import ar.changeo.agreements.*;
import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.jobs.api.*;
import ar.changeo.jobs.*;
import ar.changeo.payments.api.*;
import ar.changeo.marketplace.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.notifications.NotificationService;
import ar.changeo.security.SessionProof;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {ChangeoApplication.class, ConversationJourneyPostgresTest.Configuration.class}, properties = {
        "spring.flyway.schemas=conversations_test", "spring.flyway.default-schema=conversations_test",
        "spring.jpa.properties.hibernate.default_schema=conversations_test", "spring.datasource.hikari.schema=conversations_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.mfa-key=.runtime/conversations-test-mfa.key", "changeo.files-root=.runtime/conversations-test-artifacts"})
class ConversationJourneyPostgresTest {
    @Autowired ConversationService conversations;
    @Autowired ConversationAccess access;
    @Autowired MessageService messages;
    @MockitoSpyBean MessageOwner messageOwner;
    @MockitoSpyBean MessageReader messageReader;
    @Autowired AgreementService agreements;
    @Autowired AgreementReader agreementReader;
    @Autowired AgreementAccess agreementAccess;
    @Autowired JobAccess jobs;
    @Autowired JobExecutionService execution;
    @Autowired JobContentService jobContent;
    @Autowired JobContentReader jobReader;
    @Autowired JobFundingAccess funding;
    @Autowired JobSupportService support;
    @Autowired GuardianService guardians;
    @Autowired ParticipantIdentityAccess participantIdentity;
    @Autowired JobReputationAccess reputation;
    @MockitoSpyBean TestFundingFacts fundingFacts;
    @MockitoSpyBean JobReputationService reputationService;
    @MockitoSpyBean JobContentOwner jobOwner;
    @Autowired MarketplaceService marketplace;
    @MockitoSpyBean ar.changeo.listings.api.ListingResponseAccess responses;
    @Autowired MarketplaceReader marketplaceReader;
    @Autowired ModerationAccess moderation;
    @Autowired ModerationWorker worker;
    @Autowired ModerationService moderationService;
    @MockitoSpyBean SyntheticReviewEngine reviewEngine;
    @Autowired CaseService cases;
    @Autowired ContentReader evidence;
    @Autowired FileAccess files;
    @Autowired AccountService accounts;
    @Autowired AdminService admin;
    @Autowired OperatorService operators;
    @Autowired NotificationService notices;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestClock clock;
    @MockitoBean SessionProof proof;
    @MockitoBean TokenDelivery delivery;
    @MockitoSpyBean AttachmentAccessPolicy filePolicy;
    final ThreadLocal<UUID> actor = new ThreadLocal<>();
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    UUID administrator;
    Set<Path> previousFiles;

    @TestConfiguration static class Configuration {
        @Bean @Primary TestClock conversationClock() { return new TestClock(); }
        @Bean @Primary TestFundingFacts fundingFacts() { return new TestFundingFacts(); }
    }
    static class TestFundingFacts implements JobMoneyStatusAccess {
        final Map<UUID, ContentReference> facts = new ConcurrentHashMap<>();
        final Map<UUID, ContentReference> unfunded = new ConcurrentHashMap<>();
        void unfunded(JobSnapshot actualNegotiatedJob) {
            assertThat(actualNegotiatedJob.state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
            assertThat(actualNegotiatedJob.legalPayer()).isPresent(); unfunded.put(actualNegotiatedJob.id(), actualNegotiatedJob.agreement().orElseThrow());
        }
        void confirm(JobSnapshot actualNegotiatedJob) {
            assertThat(actualNegotiatedJob.state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
            assertThat(actualNegotiatedJob.legalPayer()).isPresent();
            facts.put(actualNegotiatedJob.id(), actualNegotiatedJob.agreement().orElseThrow());
        }
        @Override public JobMoneyStatus currentForUpdate(UUID job, ContentReference agreement) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new JobMoneyStatus(job, agreement, agreement.equals(facts.get(job)) ? JobMoneyStatus.Funding.CONFIRMED_FULL : agreement.equals(unfunded.get(job)) ? JobMoneyStatus.Funding.UNFUNDED : JobMoneyStatus.Funding.INTEGRATION_PENDING,
                    JobMoneyStatus.Release.INTEGRATION_PENDING, JobMoneyStatus.BankPayout.INTEGRATION_PENDING);
        }
    }
    static class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-06T12:00:00Z"));
        void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @BeforeEach void setup() throws Exception {
        previousFiles = artifactFiles(); clock.advance(61); actor.remove(); tokens.clear();
        jdbc.update("delete from notifications_local_delivery"); jdbc.update("delete from notifications_intent");
        when(proof.actor()).thenAnswer(invocation -> Optional.ofNullable(actor.get()));
        when(proof.mfa(any(), anyLong())).thenReturn(true); when(proof.freshPassword(any(), anyLong())).thenReturn(true);
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> { tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true; });
        String contact = "c4operator" + UUID.randomUUID() + "@example.test";
        Path enrollment = Path.of(".runtime/c4-test-enrollment-" + UUID.randomUUID());
        try { operators.provision(contact, Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER"), enrollment); administrator = accounts.credentials(contact).orElseThrow().id(); }
        finally { Files.deleteIfExists(enrollment); }
    }
    @AfterEach void cleanup() throws Exception { actor.remove(); for (Path file : artifactFiles()) { if (!previousFiles.contains(file)) { Files.deleteIfExists(file); } } }
    Set<Path> artifactFiles() throws Exception {
        Path root = Path.of(".runtime/conversations-test-artifacts");
        if (!Files.exists(root)) { return Set.of(); }
        try (var paths = Files.list(root)) { return Set.copyOf(paths.toList()); }
    }
    UUID adult(boolean provider) {
        UUID id = accounts.register("c4" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345").accountId().value();
        accounts.verifyContact(id, tokens.get(id + ":CONTACT")); admin.evidence(administrator, id, "AGE", 30);
        if (provider) { admin.evidence(administrator, id, "PROVIDER_ELIGIBILITY", null); }
        accounts.preferences(id, true, true); return id;
    }
    UUID minor(int age, boolean provider) {
        UUID id = accounts.register("c4minor" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345").accountId().value();
        accounts.verifyContact(id, tokens.get(id + ":CONTACT")); admin.evidence(administrator, id, "AGE", age);
        admin.evidence(administrator, id, "MINOR_DRAFT", null);
        if (provider) { admin.evidence(administrator, id, "PROVIDER_ELIGIBILITY", null); }
        return id;
    }
    UUID link(UUID guardian, UUID minor) {
        UUID id = as(guardian, () -> guardians.request(guardian, guardian, minor));
        as(administrator, () -> { admin.reviewGuardian(administrator, id, 0, true); return null; });
        return id;
    }
    <T> T as(UUID id, Supplier<T> operation) { UUID previous = actor.get(); actor.set(id); try { return operation.get(); } finally { if (previous == null) { actor.remove(); } else { actor.set(previous); } } }
    <T> T tx(Supplier<T> operation) { return new TransactionTemplate(transactions).execute(status -> operation.get()); }
    ContentReference listing(UUID publisher, PresenceType type) {
        return listing(publisher, publisher, type);
    }
    ContentReference listing(UUID controller, UUID subject, PresenceType type) {
        return as(controller, () -> {
            var details = new MarketplaceDetails("Impresión 3D sintética", "Piezas personalizadas y coordinación en sandbox", List.of("3d"), "Buenos Aires", "La Plata",
                    Set.of(FulfillmentMode.values()), "A coordinar", Optional.empty(), Optional.empty(), false);
            var draft = marketplace.save(new MarketplaceService.Draft(UUID.randomUUID(), Optional.empty(), 0, type, subject, details, "GENERAL", List.of()));
            var prepared = marketplace.prepare(new MarketplaceService.Preparation(UUID.randomUUID(), draft, marketplaceReader.own(draft.resourceId()).version(), true, List.of()));
            marketplace.submit(prepared); approve(prepared, "GENERAL"); return prepared;
        });
    }
    void approve(ContentReference reference, String label) {
        as(administrator, () -> { UUID caseId = cases.reviewCase(UUID.randomUUID(), reference); cases.act(UUID.randomUUID(), caseId, 0, "APPROVE", label, "SAFETY"); return null; });
        worker.process(claim(reference, "ACTIVATE"));
    }
    ModerationWorker.Claim claim(ContentReference reference, String kind) {
        return tx(() -> {
            var row = jdbc.queryForMap("select p.id,p.reference_id,s.generation,s.decision_id from moderation_snapshot p join moderation_submission s on s.id=p.id where p.revision_id=?", reference.revisionId());
            UUID id = jdbc.queryForObject("select id from moderation_task where submission_id=? and kind=? and generation=?", UUID.class, row.get("id"), kind, row.get("generation"));
            UUID token = UUID.randomUUID();
            jdbc.update("update moderation_task set state='CLAIMED',claim_token=?,lease_until=?,attempts=attempts+1 where id=?", token, Timestamp.from(clock.instant().plusSeconds(30)), id);
            return new ModerationWorker.Claim(id, (UUID) row.get("reference_id"), (UUID) row.get("id"), kind, ((Number) row.get("generation")).longValue(), token, (UUID) row.get("decision_id"), false);
        });
    }
    AgreementTerms terms(FulfillmentMode mode) {
        var arrangement = switch (mode) { case LOCAL -> AgreementTerms.Arrangement.APPOINTMENT; case REMOTE -> AgreementTerms.Arrangement.REMOTE; case SHIPPED -> AgreementTerms.Arrangement.SHIPPING; };
        return new AgreementTerms("Imprimir la pieza acordada", 12345L, mode == FulfillmentMode.SHIPPED ? 500L : 0L, mode, arrangement,
                "Coordinación privada", "Destino sintético privado", "Una pieza y archivos", clock.instant().plusSeconds(86400), 2, AgreementTerms.Evidence.TEXT,
                "Foto y descripción de la pieza terminada", AgreementTerms.PolicyScope.PRIVATE_SYNTHETIC, AgreementTerms.PolicyVersion.S1_EXPLICIT_ACCEPTANCE_V1,
                AgreementTerms.FinalRule.EXPLICIT_CUSTOMER_ACCEPTANCE, AgreementTerms.ReviewTrigger.APPROVED_FINAL_DELIVERY, 60,
                AgreementTerms.SupportRoute.NONFINANCIAL_SUPPORT_CASE, AgreementTerms.InactivityRule.NO_AUTOMATIC_ACCEPTANCE_OR_RELEASE);
    }
    ContentReference quote(UUID provider, UUID job, FulfillmentMode mode) {
        return quote(provider, provider, job, mode);
    }
    ContentReference quote(UUID controller, UUID subject, UUID job, FulfillmentMode mode) {
        return as(controller, () -> {
            var reference = agreements.save(new AgreementService.Draft(UUID.randomUUID(), job, Optional.empty(), 0, new SubjectId(subject), "INITIAL", terms(mode), "GENERAL", List.of()));
            agreements.submit(reference); approve(reference, "GENERAL"); return reference;
        });
    }
    Negotiated negotiate() {
        UUID customer = adult(false), provider = adult(true);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        var context = access.discover(thread);
        var quote = quote(provider, context.jobId(), FulfillmentMode.REMOTE);
        long version = agreementAccess.discover(quote).version();
        as(customer, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(customer)));
        var job = as(provider, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)));
        return new Negotiated(customer, provider, job, quote);
    }
    record Negotiated(UUID customer, UUID provider, JobSnapshot job, ContentReference quote) {}

    CompletedReview completedReview(UUID provider, ContentReference offer, String label) {
        clock.advance(61);
        UUID customer = adult(false);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        UUID jobId = access.discover(thread).jobId();
        var quote = quote(provider, jobId, FulfillmentMode.REMOTE);
        long version = agreementAccess.discover(quote).version();
        as(customer, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(customer)));
        var selected = as(provider, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)));
        fundingFacts.confirm(selected);
        var started = as(provider, () -> execution.start(UUID.randomUUID(), jobId, selected.version(), new SubjectId(provider)));
        var delivery = as(provider, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), jobId, Optional.empty(), 0,
                new SubjectId(provider), "FINAL_DELIVERY", "Entrega real del trabajo sintético", null, "GENERAL", List.of())));
        as(provider, () -> jobContent.submit(delivery)); approve(delivery, "GENERAL");
        var pending = jobs.discover(started.id());
        var completed = as(customer, () -> execution.acceptFinal(UUID.randomUUID(), jobId, pending.version(), new SubjectId(customer), delivery));
        var review = as(customer, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), jobId, Optional.empty(), 0,
                new SubjectId(customer), "REVIEW", "Reseña de un trabajo bilateral terminado", 4, label, List.of())));
        as(customer, () -> jobContent.submit(review)); approve(review, label);
        return new CompletedReview(customer, provider, completed, review);
    }
    record CompletedReview(UUID customer, UUID provider, JobSnapshot job, ContentReference review) {}

    UUID reviewCase(ContentReference reference) {
        clock.advance(61);
        return as(administrator, () -> cases.reviewCase(UUID.randomUUID(), reference));
    }
    void recall(UUID caseId, String label) {
        as(administrator, () -> { cases.act(UUID.randomUUID(), caseId, 0, "RECALL", label, "SAFETY"); return null; });
    }

    GuardianFixture guardianJourney(boolean selected) {
        UUID guardian = adult(false), child = minor(14, false), provider = adult(true), relationship = link(guardian, child);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(guardian, () -> conversations.respond(offer.resourceId(), new SubjectId(child), FulfillmentMode.REMOTE));
        UUID job = access.discover(thread).jobId();
        if (selected) {
            var quote = quote(provider, job, FulfillmentMode.REMOTE); long version = agreementAccess.discover(quote).version();
            as(guardian, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(child)));
            as(provider, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)));
        }
        var reference = as(provider, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(),
                new SubjectId(provider), "PRIVATE_MESSAGE", "Historial para una carrera real", "GENERAL", List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
        as(provider, () -> messages.submit(reference)); approve(reference, "GENERAL");
        var file = as(guardian, () -> messageReader.ordinary(reference, new SubjectId(child))).attachments().getFirst();
        return new GuardianFixture(guardian, child, provider, relationship, thread, job, reference, file);
    }
    record GuardianFixture(UUID guardian, UUID child, UUID provider, UUID relationship, UUID thread, UUID job, ContentReference message, FileReference file) {}

    @ParameterizedTest @EnumSource(FulfillmentMode.class)
    void shouldNegotiateSameRevisionForEveryModeWithoutClaimingFunding(FulfillmentMode mode) {
        UUID provider = adult(true), customer = adult(false);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), mode));
        var context = as(customer, () -> conversations.view(thread, new SubjectId(customer)));
        var quote = quote(provider, context.jobId(), mode);
        long version = agreementAccess.discover(quote).version();
        var first = as(customer, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(customer)));
        assertThat(first.state()).isEqualTo(JobSnapshot.State.NEGOTIATING);
        var complete = as(provider, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)));
        assertThat(complete.state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
        assertThat(complete.agreement()).contains(quote); assertThat(complete.legalPayer()).contains(new AccountId(customer));
        assertThat(marketplaceReader.publicView(offer.resourceId()).reference()).isEqualTo(offer);
        assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=?", Integer.class, quote.resourceId())).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("update agreements_revision set terms='changed' where id=?", quote.revisionId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void shouldHidePreparedAndEditedMessagesAcrossRecipientPollNoticeAndFileChannels() {
        UUID provider = adult(true), customer = adult(false);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        byte[] bytes = "synthetic:clear".getBytes(StandardCharsets.UTF_8);
        var reference = as(customer, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(), new SubjectId(customer),
                "PRIVATE_MESSAGE", "Modelo privado original", "GENERAL", List.of(new MarketplaceService.Upload("text/plain", bytes)))));
        assertThat(as(provider, () -> messageReader.poll(thread, new SubjectId(provider), 0)).messages()).isEmpty();
        assertThat(as(provider, () -> notices.own())).isEmpty();
        as(customer, () -> messages.submit(reference));
        assertThat(as(provider, () -> messageReader.poll(thread, new SubjectId(provider), 0)).messages()).isEmpty();
        approve(reference, "GENERAL");
        var visible = as(provider, () -> messageReader.ordinary(reference, new SubjectId(provider)));
        assertThat(visible.body()).isEqualTo("Modelo privado original");
        assertThat(as(provider, () -> files.read(visible.attachments().getFirst(), FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo(bytes);
        var edited = as(customer, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), visible.version(), Optional.of(thread), Optional.empty(),
                new SubjectId(customer), "PRIVATE_MESSAGE", "Edición pendiente nunca entregada", "GENERAL", List.of())));
        as(customer, () -> messages.submit(edited));
        var poll = as(provider, () -> messageReader.poll(thread, new SubjectId(provider), 0));
        assertThat(poll.messages()).extracting(MessageReader.MessageView::reference).containsExactly(reference);
        assertThat(poll.messages()).extracting(MessageReader.MessageView::body).doesNotContain("Edición pendiente nunca entregada");
        as(administrator, () -> { UUID caseId = cases.reviewCase(UUID.randomUUID(), reference); cases.act(UUID.randomUUID(), caseId, 0, "RECALL", "GENERAL", "SAFETY"); return null; });
        assertThat(as(provider, () -> messageReader.poll(thread, new SubjectId(provider), 0)).messages()).isEmpty();
        assertThatThrownBy(() -> as(provider, () -> files.read(visible.attachments().getFirst(), FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
    }

    @Test void shouldSelectOneRequestProviderAtomicallyAndRetainPrivateListingContext() {
        UUID customer = adult(false), firstProvider = adult(true), secondProvider = adult(true);
        var request = listing(customer, PresenceType.REQUEST);
        UUID firstThread = as(firstProvider, () -> conversations.respond(request.resourceId(), new SubjectId(firstProvider), FulfillmentMode.SHIPPED));
        UUID secondThread = as(secondProvider, () -> conversations.respond(request.resourceId(), new SubjectId(secondProvider), FulfillmentMode.SHIPPED));
        var firstContext = access.discover(firstThread); var secondContext = access.discover(secondThread);
        var firstQuote = quote(firstProvider, firstContext.jobId(), FulfillmentMode.SHIPPED);
        var secondQuote = quote(secondProvider, secondContext.jobId(), FulfillmentMode.SHIPPED);
        long firstVersion = agreementAccess.discover(firstQuote).version(), secondVersion = agreementAccess.discover(secondQuote).version();
        as(firstProvider, () -> agreements.accept(UUID.randomUUID(), firstQuote, firstVersion, new SubjectId(firstProvider)));
        as(secondProvider, () -> agreements.accept(UUID.randomUUID(), secondQuote, secondVersion, new SubjectId(secondProvider)));
        as(customer, () -> agreements.accept(UUID.randomUUID(), firstQuote, firstVersion, new SubjectId(customer)));
        assertThatThrownBy(() -> as(customer, () -> agreements.accept(UUID.randomUUID(), secondQuote, secondVersion, new SubjectId(customer)))).isInstanceOf(RuntimeException.class);
        assertThat(jobs.discover(firstContext.jobId()).state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
        assertThat(jobs.discover(secondContext.jobId()).state()).isEqualTo(JobSnapshot.State.NEGOTIATING);
        assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and side='CUSTOMER'", Integer.class, secondQuote.resourceId())).isZero();
        assertThat(as(customer, () -> conversations.view(firstThread, new SubjectId(customer))).listingSnapshot()).isEqualTo(firstContext.listingSnapshot());
    }

    @Test void shouldKeepFundingPendingAndPoisonCaughtFundabilityDenial() {
        var fixture = negotiate();
        assertThatThrownBy(() -> as(fixture.provider(), () -> execution.start(UUID.randomUUID(), fixture.job().id(), fixture.job().version(), new SubjectId(fixture.provider()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("financiera");
        assertThat(jobs.discover(fixture.job().id()).state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
        assertThatThrownBy(() -> as(fixture.customer(), () -> funding.requireFundable(fixture.job().id(), fixture.quote().revisionId())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        jdbc.execute("create table if not exists conversations_test_intent(id uuid primary key)");
        UUID intent = UUID.randomUUID();
        assertThatThrownBy(() -> as(fixture.customer(), () -> tx(() -> {
            try { funding.requireFundable(fixture.job().id(), fixture.quote().revisionId()); }
            catch (IllegalStateException denied) { jdbc.update("insert into conversations_test_intent values(?)", intent); }
            return null;
        }))).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from conversations_test_intent where id=?", Integer.class, intent)).isZero();
    }

    @Test void shouldRequireExplicitFinalAcceptanceAfterWindowAndExposeOnlyEligibleCompletedReview() {
        var fixture = negotiate(); fundingFacts.confirm(fixture.job());
        var fundable = as(fixture.customer(), () -> tx(() -> funding.requireFundable(fixture.job().id(), fixture.quote().revisionId())));
        assertThat(fundable.totalMinor()).isEqualTo(12345L); assertThat(fundable.legalPayer()).isEqualTo(new AccountId(fixture.customer()));
        var started = as(fixture.provider(), () -> execution.start(UUID.randomUUID(), fixture.job().id(), fixture.job().version(), new SubjectId(fixture.provider())));
        var delivery = as(fixture.provider(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), started.id(), Optional.empty(), 0, new SubjectId(fixture.provider()),
                "FINAL_DELIVERY", "Entrega final exacta de la pieza", null, "GENERAL", List.of())));
        as(fixture.provider(), () -> jobContent.submit(delivery)); approve(delivery, "GENERAL");
        var pending = jobs.discover(started.id());
        assertThat(pending.state()).isEqualTo(JobSnapshot.State.FINAL_DELIVERY_PENDING_REVIEW);
        assertThat(pending.reviewDueAt()).contains(pending.finalApprovedAt().orElseThrow().plusSeconds(3600));
        clock.advance(3601);
        assertThat(as(fixture.customer(), () -> execution.view(started.id(), new SubjectId(fixture.customer()))).reviewObservation()).isEqualTo("REVISION_SIN_RESPUESTA");
        assertThat(jobs.discover(started.id()).state()).isEqualTo(JobSnapshot.State.FINAL_DELIVERY_PENDING_REVIEW);
        var completed = as(fixture.customer(), () -> execution.acceptFinal(UUID.randomUUID(), started.id(), pending.version(), new SubjectId(fixture.customer()), delivery));
        assertThat(completed.state()).isEqualTo(JobSnapshot.State.COMPLETED);
        var review = as(fixture.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), completed.id(), Optional.empty(), 0, new SubjectId(fixture.customer()),
                "REVIEW", "Reseña del trabajo terminado", 5, "GENERAL", List.of())));
        as(fixture.customer(), () -> jobContent.submit(review));
        assertThat(reputation.project(new SubjectId(fixture.provider()), Optional.empty()).eligibleCount()).isZero();
        approve(review, "GENERAL");
        var page = reputation.project(new SubjectId(fixture.provider()), Optional.empty());
        assertThat(page.eligibleCount()).isEqualTo(1); assertThat(page.histogram()).containsExactly(Map.entry(5, 1)); assertThat(page.observedRows()).isEqualTo(1);
        assertThatThrownBy(() -> tx(() -> reputation.project(new SubjectId(fixture.provider()), Optional.empty()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> as(fixture.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), completed.id(), Optional.empty(), 0, new SubjectId(fixture.customer()),
                "REVIEW", "Segunda reseña de la misma parte", 1, "GENERAL", List.of())))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        as(administrator, () -> { UUID caseId = cases.reviewCase(UUID.randomUUID(), review); cases.act(UUID.randomUUID(), caseId, 0, "RECALL", "GENERAL", "SAFETY"); return null; });
        assertThat(reputation.project(new SubjectId(fixture.provider()), Optional.empty()).eligibleCount()).isZero();
        assertThat(as(fixture.customer(), () -> execution.view(started.id(), new SubjectId(fixture.customer()))).money().orElseThrow().release()).isEqualTo(JobMoneyStatus.Release.INTEGRATION_PENDING);
    }

    @Test void shouldRetainOnlySelectedHistoricalGuardianSideAndSafeApprovedFilesAfterRevocationAndRelink() {
        UUID guardian = adult(false), child = minor(14, false), provider = adult(true);
        UUID relationship = link(guardian, child);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(guardian, () -> conversations.respond(offer.resourceId(), new SubjectId(child), FulfillmentMode.REMOTE));
        var context = access.discover(thread);
        var quote = quote(provider, context.jobId(), FulfillmentMode.REMOTE);
        long quoteVersion = agreementAccess.discover(quote).version();
        as(guardian, () -> agreements.accept(UUID.randomUUID(), quote, quoteVersion, new SubjectId(child)));
        var selected = as(provider, () -> agreements.accept(UUID.randomUUID(), quote, quoteVersion, new SubjectId(provider)));
        assertThat(selected.legalPayer()).contains(new AccountId(guardian));
        byte[] bytes = "synthetic:clear".getBytes(StandardCharsets.UTF_8);
        var message = as(provider, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(),
                new SubjectId(provider), "PRIVATE_MESSAGE", "Archivo histórico sensible aprobado", "SENSITIVE", List.of(new MarketplaceService.Upload("text/plain", bytes)))));
        as(provider, () -> messages.submit(message)); approve(message, "SENSITIVE");
        var attachment = as(guardian, () -> messageReader.ordinary(message, new SubjectId(child))).attachments().getFirst();
        as(guardian, () -> { conversations.block(thread, new SubjectId(child), true); return null; });
        assertThat(as(guardian, () -> files.read(attachment, FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo(bytes);
        as(guardian, () -> { guardians.revoke(guardian, relationship, 1); return null; });
        assertThat(participantIdentity.controllers(Set.of(new SubjectId(child)))).isEmpty();
        assertThatThrownBy(() -> as(guardian, () -> messageReader.poll(thread, new SubjectId(child), 0))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> as(guardian, () -> agreements.accept(UUID.randomUUID(), quote, quoteVersion, new SubjectId(child)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> as(guardian, () -> tx(() -> funding.requireFundable(selected.id(), quote.revisionId())))).isInstanceOf(RuntimeException.class);
        assertThat(as(guardian, () -> support.view(selected.id(), new SubjectId(child))).legalPayer()).isTrue();
        assertThat(as(guardian, () -> messageReader.historical(message, new SubjectId(child)))).isPresent();
        assertThat(as(guardian, () -> files.read(attachment, FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo(bytes);
        assertThatThrownBy(() -> as(guardian, () -> support.view(selected.id(), new SubjectId(provider)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> as(child, () -> files.read(attachment, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        UUID replacement = adult(false); link(replacement, child);
        assertThat(participantIdentity.controllers(Set.of(new SubjectId(child)))).containsEntry(new SubjectId(child), new ActorId(replacement));
        assertThatThrownBy(() -> as(replacement, () -> support.view(selected.id(), new SubjectId(child)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> as(replacement, () -> files.read(attachment, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        assertThat(as(guardian, () -> files.read(attachment, FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo(bytes);
        as(guardian, () -> { accounts.preferences(guardian, false, true); return null; });
        assertThat(as(guardian, () -> messageReader.historical(message, new SubjectId(child)))).isEmpty();
        assertThatThrownBy(() -> as(guardian, () -> files.read(attachment, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        as(guardian, () -> { accounts.preferences(guardian, true, true); return null; });
        assertThat(as(administrator, () -> evidence.evidence(message)).attachments()).containsExactly(attachment);
        assertThat(as(administrator, () -> files.read(attachment, FileAccess.Purpose.REVIEW)).bytes()).isEqualTo(bytes);
        as(administrator, () -> { admin.restriction(administrator, guardian, true); return null; });
        assertThat(as(guardian, () -> support.view(selected.id(), new SubjectId(child))).legalPayer()).isTrue();
        assertThat(as(guardian, () -> messageReader.historical(message, new SubjectId(child)))).isEmpty();
        assertThatThrownBy(() -> as(guardian, () -> files.read(attachment, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        as(administrator, () -> { admin.restriction(administrator, guardian, false); return null; });
        as(administrator, () -> { UUID caseId = cases.reviewCase(UUID.randomUUID(), message); cases.act(UUID.randomUUID(), caseId, 0, "RECALL", "SENSITIVE", "SAFETY"); return null; });
        assertThat(as(guardian, () -> messageReader.historical(message, new SubjectId(child)))).isEmpty();
        assertThatThrownBy(() -> as(guardian, () -> files.read(attachment, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        assertThat(jobs.discover(selected.id()).legalPayer()).contains(new AccountId(guardian));
    }

    @Test void shouldDenyUnselectedLeftHistoryAndPoisonCaughtHistoricalSideDenial() {
        UUID customer = adult(false), provider = adult(true);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        var reference = as(provider, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(),
                new SubjectId(provider), "PRIVATE_MESSAGE", "Mensaje antes de seleccionar", "GENERAL", List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
        as(provider, () -> messages.submit(reference)); approve(reference, "GENERAL");
        var attachment = as(customer, () -> messageReader.ordinary(reference, new SubjectId(customer))).attachments().getFirst();
        as(customer, () -> { conversations.leave(thread, new SubjectId(customer)); return null; });
        assertThatThrownBy(() -> as(customer, () -> files.read(attachment, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> as(customer, () -> messageReader.historical(reference, new SubjectId(customer)))).isInstanceOf(RuntimeException.class);
        var selected = negotiate();
        UUID intent = UUID.randomUUID(); jdbc.execute("create table if not exists conversations_test_intent(id uuid primary key)");
        assertThatThrownBy(() -> as(selected.customer(), () -> tx(() -> {
            try { access.protect(selected.job().conversation().id(), new ActorId(selected.customer()), new SubjectId(selected.provider()), Capability.ACCESS_EXISTING_OBLIGATION); }
            catch (ConversationFailure denied) { jdbc.update("insert into conversations_test_intent values(?)", intent); }
            return null;
        }))).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from conversations_test_intent where id=?", Integer.class, intent)).isZero();
        assertThatThrownBy(() -> participantIdentity.controllers(Set.of(new SubjectId(UUID.randomUUID())))).isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> participantIdentity.controllers(Set.of())).isInstanceOf(IdentityFailure.class);
    }

    @ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void shouldSerializeWholeBytesWithGuardianRevocationInBothOrders(boolean selected, boolean revokeFirst) throws Exception {
        var fixture = guardianJourney(selected);
        var protectedOperation = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        if (!revokeFirst) {
            doAnswer(invocation -> { invocation.callRealMethod(); protectedOperation.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; })
                    .when(filePolicy).requireCurrentAccess(fixture.file(), FileAccess.Purpose.ORDINARY);
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                if (revokeFirst) {
                    var revocation = pool.submit(() -> as(fixture.guardian(), () -> tx(() -> {
                        guardians.revoke(fixture.guardian(), fixture.relationship(), 1); protectedOperation.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        return null;
                    })));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var reading = pool.submit(() -> { secondEntered.countDown(); return as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY)); });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> reading.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); revocation.get(5, TimeUnit.SECONDS);
                    assertThatThrownBy(() -> reading.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IdentityFailure.class);
                } else {
                    var reading = pool.submit(() -> as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY)));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var revocation = pool.submit(() -> { secondEntered.countDown(); return as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; }); });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> revocation.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); assertThat(reading.get(5, TimeUnit.SECONDS).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8)); revocation.get(5, TimeUnit.SECONDS);
                }
            } finally { release.countDown(); }
        } finally { reset(filePolicy); }
        assertThatThrownBy(() -> as(fixture.guardian(), () -> messageReader.poll(fixture.thread(), new SubjectId(fixture.child()), 0))).isInstanceOf(IdentityFailure.class);
        if (selected) { assertThat(as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8)); }
        else { assertThatThrownBy(() -> as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY))).isInstanceOf(IdentityFailure.class); }
    }

    @Test void shouldKeepSelectedHistoryAfterAuthorityExpiryAndDenyCurrentOperatorProofFailure() {
        var fixture = guardianJourney(true); clock.advance(86401);
        as(administrator, () -> {
            admin.evidence(administrator, fixture.guardian(), "AGE", 30); admin.evidence(administrator, fixture.provider(), "AGE", 30);
            admin.evidence(administrator, fixture.child(), "AGE", 14); admin.evidence(administrator, fixture.provider(), "PROVIDER_ELIGIBILITY", null); return null;
        });
        assertThat(participantIdentity.controllers(Set.of(new SubjectId(fixture.child())))).isEmpty();
        assertThatThrownBy(() -> as(fixture.guardian(), () -> messageReader.poll(fixture.thread(), new SubjectId(fixture.child()), 0))).isInstanceOf(IdentityFailure.class);
        assertThat(as(fixture.guardian(), () -> support.view(fixture.job(), new SubjectId(fixture.child()))).legalPayer()).isTrue();
        assertThat(as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        assertThat(as(administrator, () -> files.read(fixture.file(), FileAccess.Purpose.REVIEW)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        when(proof.mfa(eq(administrator), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> as(administrator, () -> files.read(fixture.file(), FileAccess.Purpose.REVIEW))).isInstanceOf(IdentityFailure.class);
    }

    @ParameterizedTest @EnumSource(FulfillmentMode.class)
    void shouldUseActualGuardianProviderAndRealJobLocalConsent(FulfillmentMode mode) {
        UUID provider = minor(16, true), guardian = adult(false), customer = adult(false); UUID relationship = link(guardian, provider);
        var offer = listing(guardian, provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), mode));
        UUID jobId = access.discover(thread).jobId(); var quote = quote(guardian, provider, jobId, mode); long version = agreementAccess.discover(quote).version();
        assertThatThrownBy(() -> as(provider, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)))).isInstanceOf(IdentityFailure.class);
        if (mode == FulfillmentMode.LOCAL) {
            as(guardian, () -> guardians.grant(guardian, relationship, UUID.randomUUID()));
            assertThatThrownBy(() -> as(customer, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(customer)))).isInstanceOf(IdentityFailure.class);
            assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=?", Integer.class, quote.resourceId())).isZero();
            as(guardian, () -> guardians.grant(guardian, relationship, jobId));
        }
        as(customer, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(customer)));
        var selected = as(guardian, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)));
        assertThat(selected.state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
        assertThat(selected.conversation().provider()).isEqualTo(new SubjectId(provider)); assertThat(selected.conversation().providerActor()).isEqualTo(new ActorId(guardian));
        assertThat(selected.legalPayer()).contains(new AccountId(customer)); fundingFacts.confirm(selected);
        as(guardian, () -> { guardians.revoke(guardian, relationship, 1); return null; });
        assertThatThrownBy(() -> as(customer, () -> tx(() -> funding.requireFundable(jobId, quote.revisionId())))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> as(guardian, () -> execution.start(UUID.randomUUID(), jobId, selected.version(), new SubjectId(provider)))).isInstanceOf(RuntimeException.class);
        assertThat(as(guardian, () -> support.view(jobId, new SubjectId(provider))).legalPayer()).isFalse();
    }

    @Test void shouldBoundNoticeRetriesAfterCurrentBlockAndExposeOnlySeparateDeliveryStatus() {
        var fixture = guardianJourney(false);
        as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
        for (int attempt = 0; attempt < 3; attempt++) {
            for (int count = 0; count < 4; count++) { if (!notices.runOnce()) { break; } }
            clock.advance(91);
        }
        assertThat(jdbc.queryForList("select state from notifications_intent where revision_id=?", String.class, fixture.message().revisionId())).containsExactly("FAILED", "FAILED");
        assertThat(jdbc.queryForList("select attempts from notifications_intent where revision_id=?", Integer.class, fixture.message().revisionId())).containsExactly(3, 3);
        assertThat(jdbc.queryForObject("select count(*) from notifications_local_delivery", Integer.class)).isZero();
        assertThat(as(fixture.guardian(), () -> notices.own())).isEmpty();
        as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), false); return null; });
        var current = as(fixture.guardian(), () -> notices.own());
        assertThat(current).hasSize(1); assertThat(current.getFirst().state()).isEqualTo("FAILED");
        assertThat(current.toString()).doesNotContain("Historial para una carrera real");
        assertThat(as(fixture.guardian(), () -> messageReader.poll(fixture.thread(), new SubjectId(fixture.child()), 0)).messages()).hasSize(1);
    }

    @Test void shouldRecoverExpiredNoticeLeaseAndRejectLateDuplicateWorker() throws Exception {
        var fixture = guardianJourney(false);
        var protectedDelivery = new CountDownLatch(1); var release = new CountDownLatch(1); var first = new AtomicBoolean(true);
        MessageReader readerTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(messageReader);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            if (first.compareAndSet(true, false)) { protectedDelivery.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            return result;
        }).when(readerTarget).available(eq(fixture.message()), any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var lost = pool.submit(notices::runOnce); assertThat(protectedDelivery.await(5, TimeUnit.SECONDS)).isTrue();
                UUID intent = jdbc.queryForObject("select id from notifications_intent where state='CLAIMED' and revision_id=?", UUID.class, fixture.message().revisionId());
                clock.advance(31); var recovered = pool.submit(notices::runOnce);
                assertThatThrownBy(() -> recovered.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(jdbc.queryForObject("select generation from notifications_intent where id=?", Long.class, intent)).isEqualTo(2L);
                release.countDown(); assertThat(lost.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(recovered.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from notifications_local_delivery where intent_id=?", Integer.class, intent)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select state from notifications_intent where id=?", String.class, intent)).isEqualTo("DELIVERED");
            } finally { release.countDown(); }
        } finally { reset(readerTarget); }
    }

    @Test void shouldQueueOwnFirstSubmissionAndAllowFocusedSafeEvidenceWithRecalledAgreementDependency() {
        var fixture = negotiate();
        as(administrator, () -> { UUID caseId = cases.reviewCase(UUID.randomUUID(), fixture.quote()); cases.act(UUID.randomUUID(), caseId, 0, "RECALL", "GENERAL", "SAFETY"); return null; });
        var note = as(fixture.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0, new SubjectId(fixture.customer()),
                "SUPPORT_NOTE", "Explicación privada pendiente de ayuda", null, "GENERAL", List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?", Integer.class, note.revisionId())).isZero();
        as(fixture.customer(), () -> jobContent.submit(note)); as(fixture.customer(), () -> jobContent.submit(note));
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?", Integer.class, note.revisionId())).isEqualTo(1);
        var own = as(administrator, () -> evidence.evidence(note)); assertThat(own.text()).contains("Explicación privada pendiente de ayuda");
        var file = own.attachments().getFirst();
        assertThat(as(administrator, () -> files.read(file, FileAccess.Purpose.REVIEW)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> as(fixture.customer(), () -> files.read(file, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        approve(note, "GENERAL");
        assertThat(jdbc.queryForObject("select approved_revision from jobs_authored where id=?", UUID.class, note.resourceId())).isNull();
        assertThat(jobs.discover(fixture.job().id()).state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where revision_id=?", Integer.class, note.revisionId())).isZero();
        assertThat(as(administrator, () -> files.read(file, FileAccess.Purpose.REVIEW)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        assertThat(as(fixture.customer(), () -> support.view(fixture.job().id(), new SubjectId(fixture.customer()))).legalPayer()).isTrue();
        assertThat(as(fixture.customer(), () -> agreementReader.historical(fixture.quote(), new SubjectId(fixture.customer())))).isEmpty();
    }

    @Test void shouldModeratePublicDiscussionWithoutPrivateInputAndProtectBothActualBarrierOrders() {
        UUID provider = adult(true), customer = adult(false); var parent = middleBarrierListing(provider);
        UUID parentBarrier = jdbc.queryForObject("select reference_id from moderation_snapshot where revision_id=?", UUID.class, parent.revisionId());
        Set<Boolean> orders = new HashSet<>();
        for (int count = 0; count < 256 && orders.size() < 2; count++) {
            clock.advance(61);
            var question = as(customer, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.empty(), Optional.of(parent.resourceId()),
                    new SubjectId(customer), "QUESTION", "¿Se puede coordinar una pieza sintética?", "GENERAL", List.of())));
            assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?", Integer.class, question.revisionId())).isZero();
            as(customer, () -> messages.submit(question));
            UUID ownBarrier = jdbc.queryForObject("select reference_id from moderation_snapshot where revision_id=?", UUID.class, question.revisionId());
            orders.add(ownBarrier.toString().compareTo(parentBarrier.toString()) < 0); approve(question, "GENERAL");
            assertThat(jdbc.queryForObject("select approved_revision from conversations_authored where id=?", UUID.class, question.resourceId())).isEqualTo(question.revisionId());
        }
        assertThat(orders).containsExactlyInAnyOrder(true, false);
        assertThat(messageReader.discussion(parent)).isNotEmpty().allMatch(view -> view.attachments().isEmpty());
        var selfComment = as(provider, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.empty(), Optional.of(parent.resourceId()),
                new SubjectId(provider), "COMMENT", "Comentario público del autor", "GENERAL", List.of())));
        as(provider, () -> messages.submit(selfComment)); approve(selfComment, "GENERAL");
        assertThatThrownBy(() -> as(customer, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.empty(), Optional.of(parent.resourceId()),
                new SubjectId(customer), "QUESTION", "Archivo /conversations/files/privado y correo persona@example.test", "GENERAL", List.of())))).isInstanceOf(ConversationFailure.class);
        var held = as(customer, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.empty(), Optional.of(parent.resourceId()),
                new SubjectId(customer), "QUESTION", "Pregunta privada todavía sin moderar", "GENERAL", List.of())));
        as(administrator, () -> { UUID caseId = cases.reviewCase(UUID.randomUUID(), parent); cases.act(UUID.randomUUID(), caseId, 0, "RECALL", "GENERAL", "SAFETY"); return null; });
        as(customer, () -> messages.submit(held));
        assertThat(as(administrator, () -> evidence.evidence(held)).text()).contains("Pregunta privada todavía sin moderar");
        approve(held, "GENERAL");
        assertThat(jdbc.queryForObject("select approved_revision from conversations_authored where id=?", UUID.class, held.resourceId())).isNull();
    }

    ContentReference middleBarrierListing(UUID provider) {
        for (int count = 0; count < 32; count++) {
            clock.advance(61); var reference = listing(provider, PresenceType.OFFER);
            UUID barrier = jdbc.queryForObject("select reference_id from moderation_snapshot where revision_id=?", UUID.class, reference.revisionId());
            int leading = Character.digit(barrier.toString().charAt(0), 16);
            if (leading >= 4 && leading <= 11) { return reference; }
        }
        throw new IllegalStateException("No actual fixture barrier in the middle UUID range");
    }

    @Test void shouldProjectBoundedCompletedReviewPagesAndRejectChangedContinuation() {
        UUID provider = adult(true); var offer = listing(provider, PresenceType.OFFER);
        List<CompletedReview> actual = new ArrayList<>();
        for (int index = 0; index < 11; index++) { actual.add(completedReview(provider, offer, "GENERAL")); }
        var first = reputation.project(new SubjectId(provider), Optional.empty());
        assertThat(first.observedRows()).isEqualTo(10); assertThat(first.eligibleCount()).isEqualTo(10);
        assertThat(first.histogram()).containsExactly(Map.entry(4, 10));
        var last = reputation.project(new SubjectId(provider), first.continuation());
        assertThat(last.observedRows()).isEqualTo(1); assertThat(last.eligibleCount()).isEqualTo(1); assertThat(last.continuation()).isEmpty();
        recall(reviewCase(actual.getFirst().review()), "GENERAL");
        assertThatThrownBy(() -> reputation.project(new SubjectId(provider), first.continuation())).isInstanceOf(IllegalStateException.class);
        assertThat(reputation.project(new SubjectId(provider), Optional.empty()).eligibleCount()).isEqualTo(10);
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldSerializeReputationWithRecallAndRejectChangedCohort(boolean recallFirst) throws Exception {
        UUID provider = adult(true); var fixture = completedReview(provider, listing(provider, PresenceType.OFFER), "GENERAL");
        UUID caseId = reviewCase(fixture.review());
        var protectedOperation = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        if (!recallFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); protectedOperation.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return result;
            }).when(fundingFacts).currentForUpdate(fixture.job().id(), fixture.job().agreement().orElseThrow());
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                if (recallFirst) {
                    var recalling = pool.submit(() -> tx(() -> {
                        recall(caseId, "GENERAL"); protectedOperation.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        return null;
                    }));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var projecting = pool.submit(() -> { secondEntered.countDown(); return reputation.project(new SubjectId(provider), Optional.empty()); });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> projecting.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); recalling.get(5, TimeUnit.SECONDS);
                    assertThatThrownBy(() -> projecting.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
                } else {
                    var projecting = pool.submit(() -> reputation.project(new SubjectId(provider), Optional.empty()));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var recalling = pool.submit(() -> { secondEntered.countDown(); recall(caseId, "GENERAL"); return null; });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> recalling.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); assertThat(projecting.get(5, TimeUnit.SECONDS).eligibleCount()).isEqualTo(1); recalling.get(5, TimeUnit.SECONDS);
                }
            } finally { release.countDown(); }
        } finally { reset(fundingFacts); }
        assertThat(reputation.project(new SubjectId(provider), Optional.empty()).eligibleCount()).isZero();
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldProtectCurrentReputationAudienceInBothPreferenceOrders(boolean preferenceFirst) throws Exception {
        UUID provider = adult(true), viewer = adult(false);
        var fixture = completedReview(provider, listing(provider, PresenceType.OFFER), "SENSITIVE");
        var protectedOperation = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        if (!preferenceFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); protectedOperation.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return result;
            }).when(fundingFacts).currentForUpdate(fixture.job().id(), fixture.job().agreement().orElseThrow());
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                if (preferenceFirst) {
                    var changing = pool.submit(() -> tx(() -> {
                        accounts.preferences(viewer, false, false); protectedOperation.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        return null;
                    }));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var projecting = pool.submit(() -> { secondEntered.countDown(); return as(viewer, () -> reputation.project(new SubjectId(provider), Optional.empty())); });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> projecting.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); changing.get(5, TimeUnit.SECONDS); assertThat(projecting.get(5, TimeUnit.SECONDS).eligibleCount()).isZero();
                } else {
                    var projecting = pool.submit(() -> as(viewer, () -> reputation.project(new SubjectId(provider), Optional.empty())));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var changing = pool.submit(() -> { secondEntered.countDown(); accounts.preferences(viewer, false, false); return null; });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> changing.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); assertThat(projecting.get(5, TimeUnit.SECONDS).eligibleCount()).isEqualTo(1); changing.get(5, TimeUnit.SECONDS);
                }
            } finally { release.countDown(); }
        } finally { reset(fundingFacts); }
        assertThat(as(viewer, () -> reputation.project(new SubjectId(provider), Optional.empty())).eligibleCount()).isZero();
        assertThat(as(viewer, () -> reputation.project(new SubjectId(provider), Optional.empty())).observedRows()).isEqualTo(1);
    }

    @Test void shouldRecheckIndependentProfileAfterCompletedReputationProjection() {
        UUID provider = adult(true); completedReview(provider, listing(provider, PresenceType.OFFER), "GENERAL");
        var profile = listing(provider, PresenceType.PROVIDER_PROFILE);
        assertThat(marketplaceReader.composedProfile(profile.resourceId(), Optional.empty()).reputation().eligibleCount()).isEqualTo(1);
        doAnswer(invocation -> {
            var page = invocation.callRealMethod();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            as(provider, () -> { marketplace.transition(UUID.randomUUID(), profile.resourceId(), marketplaceReader.own(profile.resourceId()).version(), "PAUSE"); return null; });
            return page;
        }).when(reputationService).project(new SubjectId(provider), Optional.empty());
        assertThatThrownBy(() -> marketplaceReader.composedProfile(profile.resourceId(), Optional.empty())).isInstanceOf(RuntimeException.class);
        reset(reputationService);
        assertThatThrownBy(() -> marketplaceReader.composedProfile(profile.resourceId(), Optional.empty())).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldSerializeActualCompletionAndReputationCohortInBothOrders(boolean completionFirst) throws Exception {
        UUID provider = adult(true); var offer = listing(provider, PresenceType.OFFER);
        var existing = completedReview(provider, offer, "GENERAL"); clock.advance(61);
        UUID customer = adult(false);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        UUID jobId = access.discover(thread).jobId(); var quote = quote(provider, jobId, FulfillmentMode.REMOTE);
        long quoteVersion = agreementAccess.discover(quote).version();
        as(customer, () -> agreements.accept(UUID.randomUUID(), quote, quoteVersion, new SubjectId(customer)));
        var selected = as(provider, () -> agreements.accept(UUID.randomUUID(), quote, quoteVersion, new SubjectId(provider)));
        fundingFacts.confirm(selected);
        as(provider, () -> execution.start(UUID.randomUUID(), jobId, selected.version(), new SubjectId(provider)));
        var delivery = as(provider, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), jobId, Optional.empty(), 0,
                new SubjectId(provider), "FINAL_DELIVERY", "Entrega bilateral antes de completar", null, "GENERAL", List.of())));
        as(provider, () -> jobContent.submit(delivery)); approve(delivery, "GENERAL");
        long version = jobs.discover(jobId).version();
        var protectedOperation = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        if (!completionFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); protectedOperation.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return result;
            }).when(fundingFacts).currentForUpdate(existing.job().id(), existing.job().agreement().orElseThrow());
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                if (completionFirst) {
                    var completing = pool.submit(() -> tx(() -> {
                        var result = as(customer, () -> execution.acceptFinal(UUID.randomUUID(), jobId, version, new SubjectId(customer), delivery));
                        protectedOperation.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        return result;
                    }));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var projecting = pool.submit(() -> { secondEntered.countDown(); return reputation.project(new SubjectId(provider), Optional.empty()); });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> projecting.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); assertThat(completing.get(5, TimeUnit.SECONDS).state()).isEqualTo(JobSnapshot.State.COMPLETED);
                    assertThatThrownBy(() -> projecting.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
                } else {
                    var projecting = pool.submit(() -> reputation.project(new SubjectId(provider), Optional.empty()));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var completing = pool.submit(() -> { secondEntered.countDown(); return as(customer, () -> execution.acceptFinal(UUID.randomUUID(), jobId, version, new SubjectId(customer), delivery)); });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> completing.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); assertThat(projecting.get(5, TimeUnit.SECONDS).eligibleCount()).isEqualTo(1);
                    assertThat(completing.get(5, TimeUnit.SECONDS).state()).isEqualTo(JobSnapshot.State.COMPLETED);
                }
            } finally { release.countDown(); }
        } finally { reset(fundingFacts); }
        assertThat(reputation.project(new SubjectId(provider), Optional.empty()).eligibleCount()).isEqualTo(1);
    }

    @Test void shouldRecordBlockedActivationFailureWithoutLeavingClaimedPrivateDelivery() {
        var fixture = negotiate();
        var message = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Bloqueado antes de entregar", "GENERAL", List.of())));
        as(fixture.provider(), () -> messages.submit(message));
        as(fixture.customer(), () -> { conversations.block(fixture.job().conversation().id(), new SubjectId(fixture.customer()), true); return null; });
        assertThatCode(() -> approve(message, "GENERAL")).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select approved_revision from conversations_authored where id=?", UUID.class, message.resourceId())).isNull();
        assertThat(moderation.approval(message).state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select state from moderation_task t join moderation_snapshot p on p.id=t.submission_id where p.revision_id=? and t.kind='ACTIVATE'", String.class, message.revisionId())).isEqualTo("FAILED");
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldSerializeDependentActivationAndTermRecallWithoutPartialDelivery(boolean recallFirst) throws Exception {
        var fixture = negotiate();
        var note = as(fixture.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0,
                new SubjectId(fixture.customer()), "SUPPORT_NOTE", "Explicación dependiente exacta", null, "GENERAL", List.of())));
        as(fixture.customer(), () -> jobContent.submit(note));
        UUID noteCase = reviewCase(note);
        as(administrator, () -> { cases.act(UUID.randomUUID(), noteCase, 0, "APPROVE", "GENERAL", "SAFETY"); return null; });
        UUID termCase = reviewCase(fixture.quote()); var activation = claim(note, "ACTIVATE");
        var protectedOperation = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        if (!recallFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); protectedOperation.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return result;
            }).when(jobOwner).activate(note);
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                if (recallFirst) {
                    var recalling = pool.submit(() -> tx(() -> {
                        recall(termCase, "GENERAL"); protectedOperation.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        return null;
                    }));
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var activating = pool.submit(() -> { secondEntered.countDown(); worker.process(activation); return null; });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> activating.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); recalling.get(5, TimeUnit.SECONDS); activating.get(5, TimeUnit.SECONDS);
                } else {
                    var activating = pool.submit(() -> { worker.process(activation); return null; });
                    assertThat(protectedOperation.await(5, TimeUnit.SECONDS)).isTrue();
                    var recalling = pool.submit(() -> { secondEntered.countDown(); recall(termCase, "GENERAL"); return null; });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> recalling.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); activating.get(5, TimeUnit.SECONDS); recalling.get(5, TimeUnit.SECONDS);
                }
            } finally { release.countDown(); }
        } finally { reset(jobOwner); }
        assertThat(jdbc.queryForObject("select approved_revision from jobs_authored where id=?", UUID.class, note.resourceId())).isEqualTo(recallFirst ? null : note.revisionId());
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where revision_id=?", Integer.class, note.revisionId())).isEqualTo(recallFirst ? 0 : 2);
        assertThat(as(fixture.customer(), () -> jobReader.historical(note, new SubjectId(fixture.customer())))).isEmpty();
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldSelectExactlyOneCompetingProviderWithAtomicSecondAssent(boolean secondWins) throws Exception {
        UUID customer = adult(false), first = adult(true), second = adult(true); var request = listing(customer, PresenceType.REQUEST);
        UUID firstThread = as(first, () -> conversations.respond(request.resourceId(), new SubjectId(first), FulfillmentMode.REMOTE));
        UUID secondThread = as(second, () -> conversations.respond(request.resourceId(), new SubjectId(second), FulfillmentMode.REMOTE));
        var firstQuote = quote(first, access.discover(firstThread).jobId(), FulfillmentMode.REMOTE);
        var secondQuote = quote(second, access.discover(secondThread).jobId(), FulfillmentMode.REMOTE);
        long firstVersion = agreementAccess.discover(firstQuote).version(), secondVersion = agreementAccess.discover(secondQuote).version();
        as(customer, () -> agreements.accept(UUID.randomUUID(), firstQuote, firstVersion, new SubjectId(customer)));
        as(customer, () -> agreements.accept(UUID.randomUUID(), secondQuote, secondVersion, new SubjectId(customer)));
        UUID winner = secondWins ? second : first, loser = secondWins ? first : second;
        var winningQuote = secondWins ? secondQuote : firstQuote; var losingQuote = secondWins ? firstQuote : secondQuote;
        long winningVersion = secondWins ? secondVersion : firstVersion, losingVersion = secondWins ? firstVersion : secondVersion;
        var protectedWinner = new CountDownLatch(1); var release = new CountDownLatch(1); var loserEntered = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var selecting = pool.submit(() -> as(winner, () -> tx(() -> {
                    var selected = agreements.accept(UUID.randomUUID(), winningQuote, winningVersion, new SubjectId(winner)); protectedWinner.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    return selected;
                })));
                assertThat(protectedWinner.await(5, TimeUnit.SECONDS)).isTrue();
                var competing = pool.submit(() -> { loserEntered.countDown(); return as(loser, () -> agreements.accept(UUID.randomUUID(), losingQuote, losingVersion, new SubjectId(loser))); });
                assertThat(loserEntered.await(5, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> competing.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); assertThat(selecting.get(5, TimeUnit.SECONDS).state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
                assertThatThrownBy(() -> competing.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("select provider_id from marketplace_request_selection where listing_id=?", UUID.class, request.resourceId())).isEqualTo(winner);
        assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=?", Integer.class, winningQuote.resourceId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=?", Integer.class, losingQuote.resourceId())).isEqualTo(1);
        assertThat(jobs.discover(agreementAccess.discover(losingQuote).jobId()).state()).isEqualTo(JobSnapshot.State.NEGOTIATING);
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldSerializeQuoteEditAndFinalBilateralAcceptance(boolean editFirst) throws Exception {
        UUID customer = adult(false), provider = adult(true); var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        UUID job = access.discover(thread).jobId(); var quote = quote(provider, job, FulfillmentMode.REMOTE); long version = agreementAccess.discover(quote).version();
        as(customer, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(customer)));
        var edit = new AgreementService.Draft(UUID.randomUUID(), job, Optional.of(quote.resourceId()), version, new SubjectId(provider), "INITIAL", terms(FulfillmentMode.REMOTE), "GENERAL", List.of());
        var protectedFirst = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> as(provider, () -> tx(() -> {
                    Object result = editFirst ? agreements.save(edit) : agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)); protectedFirst.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    return result;
                })));
                assertThat(protectedFirst.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> { secondEntered.countDown(); return as(provider, () -> editFirst ? agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)) : agreements.save(edit)); });
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); first.get(5, TimeUnit.SECONDS); assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalArgumentException.class);
            } finally { release.countDown(); }
        }
        assertThat(jobs.discover(job).accepted()).isEqualTo(!editFirst);
        assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and revision_id=?", Integer.class, quote.resourceId(), quote.revisionId())).isEqualTo(editFirst ? 1 : 2);
    }

    @Test void shouldRecordBilateralChangeAsPendingFinancialOwnerWithoutRewritingAcceptedTerms() {
        var fixture = negotiate();
        var change = as(fixture.provider(), () -> agreements.save(new AgreementService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0,
                new SubjectId(fixture.provider()), "CHANGE", terms(FulfillmentMode.REMOTE), "GENERAL", List.of())));
        as(fixture.provider(), () -> agreements.submit(change)); approve(change, "GENERAL"); long version = agreementAccess.discover(change).version();
        as(fixture.customer(), () -> agreements.accept(UUID.randomUUID(), change, version, new SubjectId(fixture.customer())));
        var unchanged = as(fixture.provider(), () -> agreements.accept(UUID.randomUUID(), change, version, new SubjectId(fixture.provider())));
        assertThat(unchanged.agreement()).contains(fixture.quote()); assertThat(unchanged.legalPayer()).contains(new AccountId(fixture.customer()));
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='CHANGE_PENDING_FINANCIAL_OWNER'", Integer.class, fixture.job().id())).isEqualTo(1);
        assertThat(as(fixture.customer(), () -> execution.view(fixture.job().id(), new SubjectId(fixture.customer()))).money().orElseThrow().funding()).isEqualTo(JobMoneyStatus.Funding.INTEGRATION_PENDING);
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldSerializeSelectedHistoricalBytesAndRecallInBothOrders(boolean recallFirst) throws Exception {
        var fixture = guardianJourney(true); UUID caseId = reviewCase(fixture.message());
        as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
        var protectedFirst = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        if (!recallFirst) {
            doAnswer(invocation -> { invocation.callRealMethod(); protectedFirst.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; }).when(filePolicy).requireCurrentAccess(fixture.file(), FileAccess.Purpose.ORDINARY);
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                if (recallFirst) {
                    var recalling = pool.submit(() -> tx(() -> { recall(caseId, "GENERAL"); protectedFirst.countDown();
                        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        return null;
                    }));
                    assertThat(protectedFirst.await(5, TimeUnit.SECONDS)).isTrue();
                    var reading = pool.submit(() -> { secondEntered.countDown(); return as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY)); });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> reading.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); recalling.get(5, TimeUnit.SECONDS); assertThatThrownBy(() -> reading.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                } else {
                    var reading = pool.submit(() -> as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY)));
                    assertThat(protectedFirst.await(5, TimeUnit.SECONDS)).isTrue();
                    var recalling = pool.submit(() -> { secondEntered.countDown(); recall(caseId, "GENERAL"); return null; });
                    assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> recalling.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    release.countDown(); assertThat(reading.get(5, TimeUnit.SECONDS).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8)); recalling.get(5, TimeUnit.SECONDS);
                }
            } finally { release.countDown(); }
        } finally { reset(filePolicy); }
        assertThatThrownBy(() -> as(fixture.guardian(), () -> files.read(fixture.file(), FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
    }

    @Test void shouldKeepRejectedPrivateRevisionHiddenAndDeliverOnlyAfterActualAppealApproval() {
        var fixture = negotiate();
        var reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Texto que espera una revisión humana", "GENERAL", List.of())));
        as(fixture.provider(), () -> messages.submit(reference)); UUID caseId = reviewCase(reference);
        as(administrator, () -> { cases.act(UUID.randomUUID(), caseId, 0, "REJECT", "GENERAL", "SAFETY"); return null; });
        assertThat(moderation.approval(reference).state()).isEqualTo("REJECTED");
        assertThat(as(fixture.customer(), () -> messageReader.poll(fixture.job().conversation().id(), new SubjectId(fixture.customer()), 0)).messages()).isEmpty();
        UUID appeal = as(fixture.provider(), () -> cases.open(UUID.randomUUID(), reference, "APPEAL", "FALSE_POSITIVE", "Revisar esta decisión exacta"));
        as(administrator, () -> { cases.act(UUID.randomUUID(), appeal, 0, "TAKE", "GENERAL", "FALSE_POSITIVE"); return null; });
        as(administrator, () -> { cases.act(UUID.randomUUID(), appeal, 1, "APPROVE", "GENERAL", "FALSE_POSITIVE"); return null; });
        worker.process(claim(reference, "ACTIVATE"));
        assertThat(as(fixture.customer(), () -> messageReader.ordinary(reference, new SubjectId(fixture.customer()))).body()).isEqualTo("Texto que espera una revisión humana");
    }

    @Test void shouldExhaustDurableReviewOutageAndRetryExactPrivateRevisionWithoutDuplicateSnapshot() {
        var fixture = negotiate();
        var reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Revisión afectada por una falla sintética", "GENERAL", List.of())));
        as(fixture.provider(), () -> messages.submit(reference));
        doAnswer(invocation -> {
            ReviewInput input = invocation.getArgument(0);
            if (input.reference().equals(reference)) { throw new IllegalStateException("Synthetic review outage"); }
            return invocation.callRealMethod();
        }).when(reviewEngine).review(any());
        try {
            for (int attempt = 0; attempt < 3; attempt++) { worker.process(claim(reference, "REVIEW")); clock.advance(16); }
        } finally { reset(reviewEngine); }
        assertThat(moderation.approval(reference).state()).isEqualTo("FAILED");
        assertThat(as(fixture.customer(), () -> messageReader.poll(fixture.job().conversation().id(), new SubjectId(fixture.customer()), 0)).messages()).isEmpty();
        UUID command = UUID.randomUUID(); long generation = moderationService.generation(reference);
        as(fixture.provider(), () -> { moderationService.retry(command, reference, generation); moderationService.retry(command, reference, generation); return null; });
        assertThat(moderationService.generation(reference)).isEqualTo(generation + 1);
        worker.process(claim(reference, "REVIEW")); assertThat(moderation.approval(reference).state()).isEqualTo("MANUAL_REVIEW");
        approve(reference, "GENERAL");
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?", Integer.class, reference.revisionId())).isEqualTo(1);
        assertThat(as(fixture.customer(), () -> messageReader.ordinary(reference, new SubjectId(fixture.customer()))).reference()).isEqualTo(reference);
    }

    @Test void shouldDenyQuarantinedPrivateBytesAndManualApprovalEvenForFocusedOperator() {
        var fixture = negotiate();
        var reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Archivo todavía no inspeccionable", "GENERAL",
                List.of(new MarketplaceService.Upload("application/pdf", new byte[]{1, 2, 3})))));
        as(fixture.provider(), () -> messages.submit(reference)); worker.process(claim(reference, "REVIEW"));
        assertThat(moderation.approval(reference).reason()).isEqualTo("UNINSPECTABLE");
        var unsafe = as(administrator, () -> evidence.evidence(reference)).attachments().getFirst();
        assertThatThrownBy(() -> as(administrator, () -> files.read(unsafe, FileAccess.Purpose.REVIEW))).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> as(fixture.customer(), () -> files.read(unsafe, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        UUID caseId = reviewCase(reference);
        assertThatThrownBy(() -> as(administrator, () -> { cases.act(UUID.randomUUID(), caseId, 0, "APPROVE", "GENERAL", "SAFETY"); return null; })).isInstanceOf(ModerationFailure.class);
        assertThat(jdbc.queryForObject("select approved_revision from conversations_authored where id=?", UUID.class, reference.resourceId())).isNull();
    }

    @ParameterizedTest @CsvSource({"false", "true"})
    void shouldRequireAgreedSafePrivateFileForFinalDeliveryWithoutLeavingClaimedTask(boolean provided) {
        UUID customer = adult(false), provider = adult(true); var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        UUID jobId = access.discover(thread).jobId(); var base = terms(FulfillmentMode.REMOTE);
        var fileTerms = new AgreementTerms(base.scope(), base.serviceMinor(), base.deliveryMinor(), base.mode(), base.arrangement(),
                base.arrangementDescription(), base.privateInstructions(), base.deliverables(), base.completionDeadline(), base.feedbackRounds(),
                AgreementTerms.Evidence.PRIVATE_FILE, base.evidenceDescription(), base.policyScope(), base.policyVersion(), base.finalRule(),
                base.reviewTrigger(), base.reviewWindowMinutes(), base.supportRoute(), base.inactivityRule());
        var quote = as(provider, () -> agreements.save(new AgreementService.Draft(UUID.randomUUID(), jobId, Optional.empty(), 0,
                new SubjectId(provider), "INITIAL", fileTerms, "GENERAL", List.of())));
        as(provider, () -> agreements.submit(quote)); approve(quote, "GENERAL");
        long quoteVersion = agreementAccess.discover(quote).version();
        as(customer, () -> agreements.accept(UUID.randomUUID(), quote, quoteVersion, new SubjectId(customer)));
        var selected = as(provider, () -> agreements.accept(UUID.randomUUID(), quote, quoteVersion, new SubjectId(provider)));
        fundingFacts.confirm(selected); as(provider, () -> execution.start(UUID.randomUUID(), jobId, selected.version(), new SubjectId(provider)));
        List<MarketplaceService.Upload> attachments = provided ? List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))) : List.of();
        var delivery = as(provider, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), jobId, Optional.empty(), 0,
                new SubjectId(provider), "FINAL_DELIVERY", "Entrega con evidencia privada acordada", null, "GENERAL", attachments)));
        as(provider, () -> jobContent.submit(delivery)); assertThatCode(() -> approve(delivery, "GENERAL")).doesNotThrowAnyException();
        if (provided) {
            assertThat(jobs.discover(jobId).state()).isEqualTo(JobSnapshot.State.FINAL_DELIVERY_PENDING_REVIEW);
            var safe = as(customer, () -> jobReader.view(delivery, new SubjectId(customer))).attachments().getFirst();
            assertThat(as(customer, () -> files.read(safe, FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        } else {
            assertThat(jobs.discover(jobId).state()).isEqualTo(JobSnapshot.State.IN_PROGRESS);
            assertThat(moderation.approval(delivery).state()).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select approved_revision from jobs_authored where id=?", UUID.class, delivery.resourceId())).isNull();
            assertThat(jdbc.queryForObject("select state from moderation_task t join moderation_snapshot p on p.id=t.submission_id where p.revision_id=? and t.kind='ACTIVATE'", String.class, delivery.revisionId())).isEqualTo("FAILED");
        }
    }
    @Test void shouldReachIndependentMessageQuoteAndJobDraftsWithExactContinuation() {
        var fixture = negotiate(); UUID thread = fixture.job().conversation().id();
        var first = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Primer borrador independiente", "GENERAL", List.of())));
        var second = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Segundo borrador independiente", "GENERAL", List.of())));
        assertThat(as(fixture.provider(), () -> messageReader.own(thread, new SubjectId(fixture.provider())))).extracting(MessageReader.PreparedView::reference).containsExactlyInAnyOrder(first, second);
        as(fixture.provider(), () -> messages.submit(first)); approve(first, "GENERAL");
        assertThat(as(fixture.provider(), () -> messageReader.own(thread, new SubjectId(fixture.provider())))).extracting(MessageReader.PreparedView::reference).containsExactlyInAnyOrder(first, second);
        for (int index = 0; index < 10; index++) { clock.advance(61); as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Otro borrador independiente", "GENERAL", List.of()))); }
        var page = as(fixture.provider(), () -> messageReader.ownPage(thread, new SubjectId(fixture.provider()), Optional.empty(), Optional.empty()));
        assertThat(page.entries()).hasSize(10); assertThat(page.next()).isPresent();
        var rest = as(fixture.provider(), () -> messageReader.ownPage(thread, new SubjectId(fixture.provider()), Optional.empty(), page.next()));
        assertThat(rest.entries()).hasSize(2); assertThat(rest.next()).isEmpty();
        assertThat(as(fixture.provider(), () -> messageReader.ownPage(thread, new SubjectId(fixture.provider()), Optional.of(second.resourceId()), Optional.empty())).entries()).extracting(MessageReader.PreparedView::reference).containsExactly(second);
        assertThatThrownBy(() -> as(fixture.customer(), () -> messageReader.ownResource(second.resourceId(), new SubjectId(fixture.customer())))).isInstanceOf(ConversationFailure.class);
        var q1 = as(fixture.provider(), () -> agreements.save(new AgreementService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0, new SubjectId(fixture.provider()), "CHANGE", terms(FulfillmentMode.REMOTE), "GENERAL", List.of())));
        var q2 = as(fixture.provider(), () -> agreements.save(new AgreementService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0, new SubjectId(fixture.provider()), "CHANGE", terms(FulfillmentMode.REMOTE), "GENERAL", List.of())));
        assertThat(as(fixture.provider(), () -> agreementReader.own(fixture.job().id(), new SubjectId(fixture.provider())))).extracting(AgreementReader.PreparedQuote::reference).contains(fixture.quote(), q1, q2);
        var c1 = as(fixture.provider(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0, new SubjectId(fixture.provider()), "SUPPORT_NOTE", "Primera explicación privada", null, "GENERAL", List.of())));
        var c2 = as(fixture.provider(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0, new SubjectId(fixture.provider()), "SUPPORT_NOTE", "Segunda explicación privada", null, "GENERAL", List.of())));
        assertThat(as(fixture.provider(), () -> jobReader.own(fixture.job().id(), new SubjectId(fixture.provider())))).extracting(JobContentReader.PreparedContent::reference).containsExactlyInAnyOrder(c1, c2);
    }

    @Test void shouldSuppressCapturedRecallAndUseCurrentLabelWithoutChangingSelectedTerms() {
        var fixture = negotiate(); var context = fixture.job().conversation(); var listing = context.listing();
        assertThat(as(fixture.customer(), () -> conversations.view(context.id(), context.customer())).capture()).isPresent();
        as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), listing); cases.act(UUID.randomUUID(), id, 0, "CORRECT_LABEL", "ADULT", "MISLABEL"); return null; });
        as(fixture.customer(), () -> { accounts.preferences(fixture.customer(), true, false); return null; });
        assertThat(as(fixture.customer(), () -> conversations.view(context.id(), context.customer())).capture()).isEmpty();
        assertThat(as(fixture.customer(), () -> conversations.inbox(context.customer()))).singleElement().satisfies(display -> assertThat(display.listingSnapshot()).isEmpty());
        as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), listing); cases.act(UUID.randomUUID(), id, 0, "CORRECT_LABEL", "GENERAL", "MISLABEL"); return null; });
        assertThat(as(fixture.customer(), () -> conversations.view(context.id(), context.customer())).capture()).get().extracting(ConversationDisplay.CapturedListing::label).isEqualTo("GENERAL");
        recall(reviewCase(listing), "GENERAL");
        var suppressed = as(fixture.customer(), () -> conversations.view(context.id(), context.customer()));
        assertThat(suppressed.capture()).isEmpty(); assertThat(suppressed.listingSnapshot()).isEmpty(); assertThat(suppressed.listingTitle()).isEqualTo("Conversación privada");
        assertThat(jobs.discover(fixture.job().id()).agreement()).contains(fixture.quote());
        assertThat(as(fixture.customer(), () -> support.view(fixture.job().id(), context.customer())).legalPayer()).isTrue();
    }

    @Test void shouldProjectAdultCaptureAsGeneralAfterRelabelAndDenyGuardianAdultCapture() {
        UUID provider = adult(true), customer = adult(false); var listing = listing(provider, PresenceType.OFFER);
        as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), listing); cases.act(UUID.randomUUID(), id, 0, "CORRECT_LABEL", "ADULT", "MISLABEL"); return null; });
        worker.process(claim(listing, "ACTIVATE"));
        UUID thread = as(customer, () -> conversations.respond(listing.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        assertThat(access.discover(thread).listingLabel()).isEqualTo("ADULT");
        as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), listing); cases.act(UUID.randomUUID(), id, 0, "CORRECT_LABEL", "GENERAL", "MISLABEL"); return null; });
        as(customer, () -> { accounts.preferences(customer, true, false); return null; });
        var display = as(customer, () -> conversations.view(thread, new SubjectId(customer)));
        assertThat(display.capture()).isPresent(); assertThat(display.capture().orElseThrow().label()).isEqualTo("GENERAL"); assertThat(display.normalActions()).isFalse();
        clock.advance(61); var guardian = guardianJourney(true); var captured = access.discover(guardian.thread()).listing();
        as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), captured); cases.act(UUID.randomUUID(), id, 0, "CORRECT_LABEL", "ADULT", "MISLABEL"); return null; });
        assertThat(as(guardian.guardian(), () -> conversations.view(guardian.thread(), new SubjectId(guardian.child()))).capture()).isEmpty();
    }

    @Test void shouldPoisonCaughtCapturedCanonicalBindingDenial() {
        var fixture = negotiate(); var context = fixture.job().conversation();
        ar.changeo.listings.api.ListingResponseAccess responseSpy = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(responses);
        doAnswer(invocation -> { var result = invocation.callRealMethod(); jdbc.update("update conversations_thread set version=version+1 where id=?", context.id()); return result; }).when(responseSpy).prepareResponse(context.listing());
        UUID intent = UUID.randomUUID(); jdbc.execute("create table if not exists conversations_cfr_intent(id uuid primary key)");
        assertThatThrownBy(() -> as(fixture.customer(), () -> tx(() -> {
            try { conversations.view(context.id(), context.customer()); }
            catch (ConversationFailure denied) { jdbc.update("insert into conversations_cfr_intent values(?)", intent); }
            return null;
        }))).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from conversations_cfr_intent where id=?", Integer.class, intent)).isZero();
        assertThat(access.discover(context.id()).version()).isEqualTo(context.version()); reset(responseSpy);
    }

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void shouldSerializeCapturedProjectionAndRecallOrPreference(boolean changeFirst, boolean preference) throws Exception {
        var fixture = negotiate(); var context = fixture.job().conversation(); var listing = context.listing();
        if (preference) { as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), listing); cases.act(UUID.randomUUID(), id, 0, "CORRECT_LABEL", "ADULT", "MISLABEL"); return null; }); }
        UUID caseId = preference ? null : reviewCase(listing);
        Supplier<Object> change = () -> { if (preference) { as(fixture.customer(), () -> { accounts.preferences(fixture.customer(), true, false); return null; }); } else { recall(caseId, "GENERAL"); } return null; };
        Supplier<Object> projection = () -> as(fixture.customer(), () -> conversations.view(context.id(), context.customer()));
        Object first = conflict(changeFirst ? change : projection, changeFirst ? projection : change, false);
        if (!changeFirst) { assertThat(((ConversationDisplay) first).capture()).isPresent(); }
        assertThat(as(fixture.customer(), () -> conversations.view(context.id(), context.customer())).capture()).isEmpty();
    }

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void shouldSerializeMessageActivationAndCounterpartyRestrictionOrGuardianChange(boolean changeFirst, boolean guardianChange) throws Exception {
        UUID provider, customer, customerActor, thread, relationship;
        if (guardianChange) { var f = guardianJourney(false); provider = f.provider(); customer = f.child(); customerActor = f.guardian(); thread = f.thread(); relationship = f.relationship(); }
        else { provider = adult(true); customer = adult(false); customerActor = customer; relationship = null; var offer = listing(provider, PresenceType.OFFER); thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE)); }
        var reference = as(provider, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(thread), Optional.empty(), new SubjectId(provider), "PRIVATE_MESSAGE", "Nueva activación contra cambio de contraparte", "GENERAL", List.of())));
        as(provider, () -> messages.submit(reference)); as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), reference); cases.act(UUID.randomUUID(), id, 0, "APPROVE", "GENERAL", "SAFETY"); return null; });
        var activation = claim(reference, "ACTIVATE");
        var protectedFirst = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        if (!changeFirst) { doAnswer(invocation -> { var result = invocation.callRealMethod(); protectedFirst.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return result; }).when(messageOwner).activate(reference); }
        Supplier<Object> change = () -> { if (guardianChange) { as(customerActor, () -> { guardians.revoke(customerActor, relationship, 1); return null; }); } else { as(administrator, () -> { admin.restriction(administrator, customer, true); return null; }); } return null; };
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = changeFirst ? pool.submit(() -> tx(() -> { change.get(); protectedFirst.countDown(); await(release); return null; })) : pool.submit(() -> { worker.process(activation); return null; });
                assertThat(protectedFirst.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> { secondEntered.countDown(); if (changeFirst) { worker.process(activation); } else { change.get(); } return null; });
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        } finally { reset(messageOwner); }
        assertThat(jdbc.queryForObject("select approved_revision from conversations_authored where id=?", UUID.class, reference.resourceId())).isEqualTo(changeFirst ? null : reference.revisionId());
        assertThat(jdbc.queryForObject("select count(*) from conversations_event where resource_id=? and kind='DELIVERED'", Integer.class, reference.resourceId())).isEqualTo(changeFirst ? 0 : 1);
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where revision_id=?", Integer.class, reference.revisionId())).isEqualTo(changeFirst ? 0 : 2);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?", Integer.class, activation.submission())).isEqualTo(changeFirst ? 0 : 1);
        assertThat(jdbc.queryForObject("select state from moderation_task where id=?", String.class, activation.id())).isEqualTo(changeFirst ? "FAILED" : "COMPLETE");
        assertThatThrownBy(() -> as(customerActor, () -> messageReader.poll(thread, new SubjectId(customer), 0))).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest @CsvSource({"false,RECALL", "true,RECALL", "false,RESTRICTION", "true,RESTRICTION", "false,GUARDIAN", "true,GUARDIAN"})
    void shouldSerializeExactBilateralAcceptanceAgainstRecallRestrictionAndGuardian(boolean changeFirst, String changeKind) throws Exception {
        UUID customerActor = adult(false), customer = changeKind.equals("GUARDIAN") ? minor(14, false) : customerActor, provider = adult(true);
        UUID relationship = changeKind.equals("GUARDIAN") ? link(customerActor, customer) : null;
        var request = listing(customerActor, customer, PresenceType.REQUEST);
        UUID thread = as(provider, () -> conversations.respond(request.resourceId(), new SubjectId(provider), FulfillmentMode.REMOTE)); UUID job = access.discover(thread).jobId();
        var quote = quote(provider, job, FulfillmentMode.REMOTE); long version = agreementAccess.discover(quote).version();
        as(customerActor, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(customer)));
        UUID caseId = changeKind.equals("RECALL") ? reviewCase(quote) : null;
        Supplier<Object> change = () -> { switch (changeKind) { case "RECALL" -> recall(caseId, "GENERAL"); case "RESTRICTION" -> as(administrator, () -> { admin.restriction(administrator, customer, true); return null; }); case "GUARDIAN" -> as(customerActor, () -> { guardians.revoke(customerActor, relationship, 1); return null; }); default -> throw new IllegalArgumentException(); } return null; };
        Supplier<Object> accepting = () -> as(provider, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)));
        conflict(changeFirst ? change : accepting, changeFirst ? accepting : change, changeFirst);
        var current = jobs.discover(job);
        assertThat(current.accepted()).isEqualTo(!changeFirst); assertThat(current.legalPayer()).isEqualTo(changeFirst ? Optional.empty() : Optional.of(new AccountId(customerActor)));
        assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and revision_id=?", Integer.class, quote.resourceId(), quote.revisionId())).isEqualTo(changeFirst ? 1 : 2);
        assertThat(jdbc.queryForObject("select count(*) from marketplace_request_selection where listing_id=?", Integer.class, request.resourceId())).isEqualTo(changeFirst ? 0 : 1);
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='AGREED'", Integer.class, job)).isEqualTo(changeFirst ? 0 : 1);
        assertThatThrownBy(() -> as(provider, () -> agreements.accept(UUID.randomUUID(), quote, version, new SubjectId(provider)))).isInstanceOf(RuntimeException.class);
    }

    Object conflict(Supplier<Object> firstAction, Supplier<Object> secondAction, boolean secondDenied) throws Exception {
        var protectedFirst = new CountDownLatch(1); var release = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> tx(() -> { var result = firstAction.get(); protectedFirst.countDown(); await(release); return result; }));
                assertThat(protectedFirst.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> { secondEntered.countDown(); return secondAction.get(); });
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); var result = first.get(5, TimeUnit.SECONDS);
                if (secondDenied) { assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class); } else { second.get(5, TimeUnit.SECONDS); }
                return result;
            } finally { release.countDown(); }
        }
    }
    void await(CountDownLatch latch) { try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }

    @ParameterizedTest @CsvSource({"ABSENT", "BOUND", "INVALID", "BINDING"})
    void shouldRollbackCaughtStrictBatchFailureAndCallerEffects(String failure) {
        var fixture = negotiate(); UUID intent = UUID.randomUUID(); jdbc.execute("create table if not exists conversations_cfr_intent(id uuid primary key)");
        var absent = new ContentReference(SurfaceKind.PRIVATE_MESSAGE, UUID.randomUUID(), UUID.randomUUID());
        if (failure.equals("ABSENT") || failure.equals("BINDING")) { absent = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Unsubmitted exact canonical", "GENERAL", List.of()))); }
        Set<ContentReference> references = failure.equals("INVALID") ? Set.of() : failure.equals("BOUND") ? java.util.stream.IntStream.range(0, 33).mapToObj(index -> new ContentReference(SurfaceKind.PRIVATE_MESSAGE, UUID.randomUUID(), UUID.randomUUID())).collect(java.util.stream.Collectors.toSet()) : Set.of(absent);
        var exact = references;
        long events = jdbc.queryForObject("select count(*) from jobs_event where job_id=?", Long.class, fixture.job().id());
        long signals = jdbc.queryForObject("select count(*) from moderation_signal", Long.class);
        assertThatThrownBy(() -> tx(() -> {
            if (failure.equals("BINDING")) {
                var reference = exact.iterator().next(); UUID barrier = UUID.randomUUID(), submission = UUID.randomUUID();
                jdbc.update("insert into moderation_reference values(?,?,?)", barrier, reference.ownerKind().name(), reference.resourceId());
                jdbc.update("insert into moderation_snapshot values(?,?,?,?,?,?,?,?,?,?,?,?)", submission, barrier, reference.revisionId(), fixture.provider(), fixture.provider(), "SEND_MARKETPLACE_MESSAGE", fixture.job().id(), "REMOTE", "Malformed synthetic exact snapshot", "GENERAL", "0".repeat(64), Timestamp.from(clock.instant()));
                jdbc.update("insert into moderation_submission(id,state,label,reason) values(?,'PENDING','GENERAL','QUEUED')", submission);
            }
            try { moderation.approvalsForUpdate(exact); }
            catch (ModerationFailure denied) { jdbc.update("insert into conversations_cfr_intent values(?)", intent); jdbc.update("insert into jobs_event(id,job_id,kind,occurred_at) values(?,?,'SUPPORT_REQUESTED',?)", UUID.randomUUID(), fixture.job().id(), Timestamp.from(clock.instant())); }
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from conversations_cfr_intent where id=?", Integer.class, intent)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=?", Long.class, fixture.job().id())).isEqualTo(events);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal", Long.class)).isEqualTo(signals);
        assertThat(moderation.approval(fixture.quote()).digest()).isEqualTo(agreementAccess.discover(fixture.quote()).digest());
    }

    @Test void shouldCountIncludedFeedbackOnceAndHoldExhaustedRoundWithoutDelivery() {
        var fixture = negotiate(); fundingFacts.confirm(fixture.job());
        as(fixture.provider(), () -> execution.start(UUID.randomUUID(), fixture.job().id(), fixture.job().version(), new SubjectId(fixture.provider())));
        List<ContentReference> feedback = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            clock.advance(61); var ref = as(fixture.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0, new SubjectId(fixture.customer()), "FEEDBACK", "Devolución sintética incluida", null, "GENERAL", List.of())));
            as(fixture.customer(), () -> jobContent.submit(ref)); approve(ref, "GENERAL"); feedback.add(ref);
        }
        assertThat(jobs.discover(fixture.job().id()).feedbackCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='FEEDBACK_APPROVED'", Integer.class, fixture.job().id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select approved_revision from jobs_authored where id=?", UUID.class, feedback.getLast().resourceId())).isNull();
        assertThat(moderation.approval(feedback.getLast()).state()).isEqualTo("FAILED");
        var first = feedback.getFirst(); long version = as(fixture.customer(), () -> jobReader.ownPage(fixture.job().id(), new SubjectId(fixture.customer()), Optional.of(first.resourceId()), Optional.empty())).entries().getFirst().version();
        var edit = as(fixture.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.of(first.resourceId()), version, new SubjectId(fixture.customer()), "FEEDBACK", "Devolución corregida sin consumir otra ronda", null, "GENERAL", List.of())));
        as(fixture.customer(), () -> jobContent.submit(edit)); approve(edit, "GENERAL"); assertThat(jobs.discover(fixture.job().id()).feedbackCount()).isEqualTo(2);
    }

    @Test void shouldKeepUnknownCancellationRequestedAndCancelOnlyBilateralKnownUnfunded() {
        var unknown = negotiate();
        as(unknown.customer(), () -> { support.cancel(UUID.randomUUID(), unknown.job().id(), unknown.job().version(), new SubjectId(unknown.customer())); return null; });
        as(unknown.provider(), () -> { support.cancel(UUID.randomUUID(), unknown.job().id(), jobs.discover(unknown.job().id()).version(), new SubjectId(unknown.provider())); return null; });
        assertThat(jobs.discover(unknown.job().id()).state()).isEqualTo(JobSnapshot.State.CANCELLATION_REQUESTED);
        clock.advance(61); var known = negotiate(); fundingFacts.unfunded(known.job());
        as(known.customer(), () -> { support.cancel(UUID.randomUUID(), known.job().id(), known.job().version(), new SubjectId(known.customer())); return null; });
        assertThat(jobs.discover(known.job().id()).state()).isEqualTo(JobSnapshot.State.CANCELLATION_REQUESTED);
        as(known.provider(), () -> { support.cancel(UUID.randomUUID(), known.job().id(), jobs.discover(known.job().id()).version(), new SubjectId(known.provider())); return null; });
        assertThat(jobs.discover(known.job().id()).state()).isEqualTo(JobSnapshot.State.CANCELLED);
        clock.advance(61); var dispute = negotiate(); long cohort = jdbc.queryForObject("select version from jobs_reputation_cohort where subject_id=?", Long.class, dispute.provider());
        as(dispute.customer(), () -> { support.request(UUID.randomUUID(), dispute.job().id(), dispute.job().version(), new SubjectId(dispute.customer()), true); return null; });
        assertThat(jobs.discover(dispute.job().id()).state()).isEqualTo(JobSnapshot.State.SUPPORT_HOLD);
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='DISPUTE_REQUESTED'", Integer.class, dispute.job().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from jobs_reputation_cohort where subject_id=?", Long.class, dispute.provider())).isGreaterThan(cohort);
        assertThatThrownBy(() -> as(dispute.provider(), () -> execution.start(UUID.randomUUID(), dispute.job().id(), jobs.discover(dispute.job().id()).version(), new SubjectId(dispute.provider())))).isInstanceOf(RuntimeException.class);
    }

    @Test void shouldCorrectAndAppealCompletedReviewOnSameUniqueResource() {
        UUID provider = adult(true); var completed = completedReview(provider, listing(provider, PresenceType.OFFER), "GENERAL");
        var original = completed.review(); long version = as(completed.customer(), () -> jobReader.ownPage(completed.job().id(), new SubjectId(completed.customer()), Optional.of(original.resourceId()), Optional.empty())).entries().getFirst().version();
        var corrected = as(completed.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), completed.job().id(), Optional.of(original.resourceId()), version, new SubjectId(completed.customer()), "REVIEW", "Reseña corregida de trabajo completado", 5, "GENERAL", List.of())));
        as(completed.customer(), () -> jobContent.submit(corrected));
        assertThat(as(completed.provider(), () -> jobReader.view(original, new SubjectId(completed.provider()))).score()).isEqualTo(4);
        as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), corrected); cases.act(UUID.randomUUID(), id, 0, "REJECT", "GENERAL", "SAFETY"); return null; });
        UUID appeal = as(completed.customer(), () -> cases.open(UUID.randomUUID(), corrected, "APPEAL", "FALSE_POSITIVE", "Revisar mi reseña corregida"));
        as(administrator, () -> { cases.act(UUID.randomUUID(), appeal, 0, "TAKE", "GENERAL", "FALSE_POSITIVE"); cases.act(UUID.randomUUID(), appeal, 1, "APPROVE", "GENERAL", "FALSE_POSITIVE"); return null; });
        worker.process(claim(corrected, "ACTIVATE"));
        assertThat(as(completed.provider(), () -> jobReader.view(corrected, new SubjectId(completed.provider()))).score()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from jobs_authored where job_id=? and subject_id=? and kind='REVIEW'", Integer.class, completed.job().id(), completed.customer())).isEqualTo(1);
        assertThatThrownBy(() -> as(completed.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), completed.job().id(), Optional.empty(), 0, new SubjectId(completed.customer()), "REVIEW", "Reseña duplicada", 5, "GENERAL", List.of())))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
