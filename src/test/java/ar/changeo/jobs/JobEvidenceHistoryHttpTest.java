package ar.changeo.jobs;

import ar.changeo.ChangeoApplication;
import ar.changeo.agreements.AgreementController;
import ar.changeo.identity.*;
import ar.changeo.marketplace.MarketplaceController;
import ar.changeo.moderation.ModerationWorker;
import ar.changeo.moderation.api.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {ChangeoApplication.class, JobEvidenceHistoryHttpTest.Configuration.class}, properties = {
        "spring.flyway.schemas=job_evidence_history_http_test", "spring.flyway.default-schema=job_evidence_history_http_test",
        "spring.jpa.properties.hibernate.default_schema=job_evidence_history_http_test", "spring.datasource.hikari.schema=job_evidence_history_http_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.mfa-key=.runtime/job-history-http-mfa.key", "changeo.files-root=.runtime/job-history-http-artifacts"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class JobEvidenceHistoryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operators;
    @Autowired ModerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired ar.changeo.jobs.api.JobAccess jobs;
    @Autowired HttpFundingFacts fundingFacts;
    @Autowired JobContentReader contentReader;
    @MockitoBean TokenDelivery delivery;
    @MockitoSpyBean JobContentStore contentStore;
    @MockitoSpyBean ar.changeo.moderation.ModerationService moderationMetadata;
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    final List<MockHttpSession> sessions = new ArrayList<>();
    Properties enrollment;
    MockHttpSession operator;

    @TestConfiguration static class Configuration {
        @Bean @Primary TestClock httpClock() { return new TestClock(); }
        @Bean @Primary HttpFundingFacts httpFundingFacts() { return new HttpFundingFacts(); }
    }
    static class HttpFundingFacts implements ar.changeo.payments.api.JobMoneyStatusAccess {
        final Map<UUID, ContentReference> facts = new ConcurrentHashMap<>();
        void confirm(ar.changeo.jobs.api.JobSnapshot job) { assertThat(job.state()).isEqualTo(ar.changeo.jobs.api.JobSnapshot.State.AGREED_AWAITING_FUNDING); assertThat(job.legalPayer()).isPresent(); facts.put(job.id(), job.agreement().orElseThrow()); }
        @Override public ar.changeo.payments.api.JobMoneyStatus currentForUpdate(UUID job, ContentReference terms) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new ar.changeo.payments.api.JobMoneyStatus(job, terms, terms.equals(facts.get(job)) ? ar.changeo.payments.api.JobMoneyStatus.Funding.CONFIRMED_FULL : ar.changeo.payments.api.JobMoneyStatus.Funding.INTEGRATION_PENDING, ar.changeo.payments.api.JobMoneyStatus.Release.INTEGRATION_PENDING, ar.changeo.payments.api.JobMoneyStatus.BankPayout.INTEGRATION_PENDING);
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
        clock.advance(61); tokens.clear(); jdbc.update("delete from moderation_task");
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true;
        });
        Path path = Path.of(".runtime/job-history-enrollment-" + UUID.randomUUID());
        operators.provision("jobuioperator" + UUID.randomUUID() + "@example.test", Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER"), path);
        enrollment = new Properties(); try (var input = Files.newBufferedReader(path)) { enrollment.load(input); }
        operator = login(enrollment.getProperty("contact"), enrollment.getProperty("password"));
        operatorProof();
    }
    @AfterEach void cleanup() throws Exception {
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
    AccountFixture author(boolean provider, int age) throws Exception {
        String contact = "jobui" + UUID.randomUUID() + "@example.test", password = "Synthetic-password-12345";
        UUID id = accounts.register(contact, password).accountId().value(); accounts.verifyContact(id, tokens.get(id + ":CONTACT"));
        mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "AGE").param("age", Integer.toString(age))).andExpect(status().is3xxRedirection());
        if (provider) { mvc.perform(post("/admin/accounts/" + id + "/eligibility").session(operator).with(csrf()).param("kind", "PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection()); }
        accounts.preferences(id, true, age >= 18); return new AccountFixture(id, login(contact, password));
    }
    ContentReference offer(AccountFixture provider) throws Exception {
        mvc.perform(post("/marketplace/compose").session(provider.session()).with(csrf()).param("type", "OFFER").param("subject", provider.id().toString())
                .param("title", "Impresión 3D sintética").param("body", "Piezas por encargo en sandbox").param("label", "GENERAL")).andExpect(status().isOk());
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
        worker.processOne();
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("kind", reference.ownerKind().name())
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
        UUID caseId = jdbc.queryForObject("select c.id from moderation_case c join moderation_snapshot p on p.id=c.submission_id where p.revision_id=?", UUID.class, reference.revisionId());
        mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("caseId", caseId.toString())
                .param("version", "0").param("action", "APPROVE").param("label", "GENERAL").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
        for (int count = 0; count < 10; count++) { if (!worker.processOne()) { break; } }
        assertThat(jdbc.queryForObject("select s.state from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, reference.revisionId())).isEqualTo("APPROVED");
    }
    UUID respond(AccountFixture customer, UUID subject, ContentReference offer) throws Exception {
        String location = mvc.perform(post("/conversations/respond").session(customer.session()).with(csrf()).param("listing", offer.resourceId().toString())
                .param("subject", subject.toString()).param("mode", "REMOTE")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(location.substring("/conversations/".length(), location.indexOf('?')));
    }
    ContentReference quote(AccountFixture provider, UUID job) throws Exception {
        return quote(provider, job, true);
    }
    ContentReference quote(AccountFixture provider, UUID job, boolean approve) throws Exception {
        mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString())
                .param("subject", provider.id().toString()).param("kind", "INITIAL").param("scope", "Pieza y revisión final").param("serviceAmount", "123.45")
                .param("deliveryAmount", "0").param("deliverables", "Una pieza completa")).andExpect(status().isOk());
        var composition = (AgreementController.Composition) provider.session().getAttribute("agreementComposition");
        mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString()).param("arrangement", "REMOTE")
                .param("arrangementDescription", "Coordinación remota").param("completionDeadline", "2026-10-07T12:00").param("feedbackRounds", "0")
                .param("completionEvidence", "TEXT").param("evidenceDescription", "Descripción de la entrega").param("reviewWindowMinutes", "60").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var reference = jdbc.queryForObject("select id,current_revision from agreements_quote where job_id=?", (row, index) ->
                new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), job);
        UUID thread = jdbc.queryForObject("select conversation_id from jobs_job where id=?", UUID.class, job);
        mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
        if (approve) { approve(reference); }
        return reference;
    }
    MockHttpServletRequestBuilder accept(AccountFixture actor, UUID side, ContentReference reference) {
        long version = jdbc.queryForObject("select version from agreements_quote where id=?", Long.class, reference.resourceId());
        return post("/agreements/accept").session(actor.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", reference.resourceId().toString())
                .param("revision", reference.revisionId().toString()).param("version", Long.toString(version)).param("subject", side.toString());
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
    void decide(ContentReference reference, String action, String label) throws Exception {
        operatorProof();
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
        UUID caseId = jdbc.queryForObject("select c.id from moderation_case c join moderation_snapshot p on p.id=c.submission_id where p.revision_id=? and c.state='OWNED'", UUID.class, reference.revisionId());
        mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("caseId", caseId.toString()).param("version", "0").param("action", action).param("label", label).param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
    }
    UUID saveJob(AccountFixture author, UUID job, String kind, String body, Integer score, Optional<UUID> resource) throws Exception {
        var request = multipart("/jobs/content").file(new MockMultipartFile("uploads", "synthetic.txt", "text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))).session(author.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString()).param("subject", author.id().toString()).param("kind", kind).param("body", body).param("label", "GENERAL");
        if (resource.isPresent()) { request.param("resource", resource.orElseThrow().toString()).param("version", Long.toString(jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, resource.orElseThrow()))); }
        if (score != null) { request.param("score", score.toString()); }
        String location = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl(); return UUID.fromString(location.substring(location.indexOf("&content=") + 9));
    }
    void submitJob(AccountFixture author, UUID job, ContentReference reference) throws Exception {
        mvc.perform(post("/jobs/submit").session(author.session()).with(csrf()).param("job", job.toString()).param("subject", author.id().toString()).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
    }

    JobFixture negotiated(boolean start) throws Exception {
        var provider = author(true, 30); var customer = author(false, 30);
        var listing = offer(provider); UUID thread = respond(customer, customer.id(), listing);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var terms = quote(provider, job);
        mvc.perform(accept(customer, customer.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), terms)).andExpect(status().is3xxRedirection());
        if (start) {
            fundingFacts.confirm(jobs.discover(job));
            mvc.perform(post("/jobs/start").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                    .param("job", job.toString()).param("subject", provider.id().toString()).param("version", Long.toString(jobs.discover(job).version())))
                    .andExpect(status().is3xxRedirection());
        }
        return new JobFixture(job, provider, customer);
    }
    ContentReference evidence(JobFixture fixture, String kind, String body) throws Exception {
        UUID id = saveJob(fixture.provider(), fixture.id(), kind, body, null, Optional.empty());
        var reference = new ContentReference(SurfaceKind.JOB_FEEDBACK, id, jdbc.queryForObject("select current_revision from jobs_authored where id=?", UUID.class, id));
        submitJob(fixture.provider(), fixture.id(), reference); approve(reference); return reference;
    }
    MockHttpServletRequestBuilder detail(JobFixture fixture, AccountFixture actor) {
        return get("/jobs/" + fixture.id()).session(actor.session()).param("subject", actor.id().toString());
    }
    record JobFixture(UUID id, AccountFixture provider, AccountFixture customer) {}
    @Test void shouldReachCounterpartEvidenceBeyondTen() throws Exception {
        var fixture = negotiated(false);
        for (int index = 0; index < 11; index++) {
            clock.advance(61); evidence(fixture, "SUPPORT_NOTE", "Evidencia aprobada " + index);
        }
        var detail = mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk()).andReturn();
        assertThat(detail.getResponse().getContentAsString()).contains("/evidence?subject=");
        mvc.perform(get("/jobs/" + fixture.id() + "/evidence").session(fixture.customer().session()).param("subject", fixture.customer().id().toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Más evidencia aprobada")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 10, 11, 21})
    void shouldPageUnderlyingReferencesForwardBackwardAndFromBeginning(int count) throws Exception {
        var fixture = negotiated(false);
        for (int index = 0; index < count; index++) { clock.advance(61); evidence(fixture, "SUPPORT_NOTE", "Nota de página " + index); }
        var expected = approved(fixture);
        List<ContentReference> reached = new ArrayList<>();
        String after = null;
        JobContentReader.EvidencePage first = null;
        do {
            var observed = observe(fixture, fixture.customer(), false, after, null);
            if (first == null) { first = observed; }
            assertThat(observed.entries()).hasSizeLessThanOrEqualTo(10);
            reached.addAll(observed.entries().stream().map(JobContentReader.ContentView::reference).toList());
            if (observed.previous().isPresent()) {
                var previous = observe(fixture, fixture.customer(), false, null, observed.previous().orElseThrow().toString());
                assertThat(previous.entries().stream().map(JobContentReader.ContentView::reference).toList()).isEqualTo(expected.subList(Math.max(0, reached.size() - observed.entries().size() - 10), reached.size() - observed.entries().size()));
            }
            after = observed.next().map(UUID::toString).orElse(null);
        } while (after != null);
        assertThat(reached).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(observe(fixture, fixture.provider(), false, null, null).entries()).isEqualTo(first.entries());
        assertThat(observe(fixture, fixture.customer(), true, null, null).entries()).isEqualTo(first.entries());
    }
    @Test void shouldAuthorizeBeforeRejectingMalformedForeignMissingAndAmbiguousCursors() throws Exception {
        var fixture = negotiated(false); var foreignJob = negotiated(false); var stranger = author(false, 30);
        var reference = evidence(fixture, "SUPPORT_NOTE", "Evidencia privada");
        var foreign = evidence(foreignJob, "SUPPORT_NOTE", "Ajena");
        for (boolean retained : List.of(false, true)) {
            for (String cursor : List.of("", "invalid", "1-1-1-1-1", UUID.randomUUID().toString(), foreign.resourceId().toString())) {
                mvc.perform(history(fixture, fixture.customer(), retained).param("evidenceAfter", cursor)).andExpect(status().isBadRequest());
                mvc.perform(history(fixture, stranger, retained).param("subject", fixture.customer().id().toString()).param("evidenceAfter", cursor)).andExpect(status().isForbidden());
            }
            mvc.perform(history(fixture, fixture.customer(), retained).param("evidenceAfter", reference.resourceId().toString()).param("evidenceBefore", reference.resourceId().toString())).andExpect(status().isBadRequest());
            mvc.perform(get(route(fixture, retained)).session(fixture.customer().session()).param("subject", fixture.provider().id().toString()).param("evidenceAfter", "invalid")).andExpect(status().isForbidden());
            mvc.perform(get(route(fixture, retained)).param("subject", fixture.customer().id().toString()).param("evidenceAfter", "invalid")).andExpect(status().is3xxRedirection());
        }
    }
    @Test void shouldKeepContinuationThroughSuppressedIntervalsAndStaleRecalledCursor() throws Exception {
        var fixture = negotiated(false);
        for (int index = 0; index < 21; index++) { clock.advance(61); evidence(fixture, "SUPPORT_NOTE", "Tramo sensible " + index); }
        var ordered = approved(fixture);
        for (var reference : ordered.subList(0, 10)) { decide(reference, "CORRECT_LABEL", "SENSITIVE"); }
        accounts.preferences(fixture.customer().id(), false, false);
        var first = observe(fixture, fixture.customer(), false, null, null);
        assertThat(first.entries()).isEmpty(); assertThat(first.next()).contains(ordered.get(9).resourceId());
        var second = observe(fixture, fixture.customer(), false, first.next().orElseThrow().toString(), null);
        assertThat(second.entries()).hasSize(10);
        assertThat(observe(fixture, fixture.customer(), true, null, null).entries()).isEmpty();
        decide(ordered.get(9), "RECALL", "GENERAL");
        assertThat(observe(fixture, fixture.customer(), false, first.next().orElseThrow().toString(), null).entries()).isEqualTo(second.entries());
        mvc.perform(jobFile(ordered.get(9)).session(fixture.customer().session())).andExpect(status().isForbidden());
        accounts.preferences(fixture.customer().id(), true, false);
        assertThat(observe(fixture, fixture.customer(), false, null, null).entries()).hasSize(10);
    }
    @Test void shouldRefreshApprovedRevisionAfterPendingEditAndConcealRecalledBytes() throws Exception {
        var fixture = negotiated(false);
        for (int index = 0; index < 11; index++) { clock.advance(61); evidence(fixture, "SUPPORT_NOTE", "Versión inicial " + index); }
        var first = observe(fixture, fixture.customer(), false, null, null);
        var old = approved(fixture).get(10);
        clock.advance(61);
        saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Edición pendiente no entregada", null, Optional.of(old.resourceId()));
        var edited = new ContentReference(SurfaceKind.JOB_FEEDBACK, old.resourceId(), jdbc.queryForObject("select current_revision from jobs_authored where id=?", UUID.class, old.resourceId()));
        var pending = observe(fixture, fixture.customer(), false, first.next().orElseThrow().toString(), null);
        assertThat(pending.entries()).extracting(JobContentReader.ContentView::reference).containsExactly(old);
        submitJob(fixture.provider(), fixture.id(), edited); approve(edited);
        assertThat(observe(fixture, fixture.customer(), false, first.next().orElseThrow().toString(), null).entries()).extracting(JobContentReader.ContentView::reference).containsExactly(edited);
        mvc.perform(jobFile(old).session(fixture.customer().session())).andExpect(status().isForbidden());
        mvc.perform(jobFile(edited).session(fixture.customer().session())).andExpect(status().isOk()).andExpect(content().bytes("synthetic:clear".getBytes(StandardCharsets.UTF_8)));
        decide(edited, "RECALL", "GENERAL");
        assertThat(observe(fixture, fixture.customer(), false, first.next().orElseThrow().toString(), null).entries()).isEmpty();
        mvc.perform(jobFile(edited).session(fixture.customer().session())).andExpect(status().isForbidden());
    }
    @Test void shouldRetainExactFinalOutsideFirstTenAndNeverAcceptFromHistory() throws Exception {
        var fixture = negotiated(true);
        for (int index = 0; index < 21; index++) { clock.advance(61); evidence(fixture, "PROGRESS", "Avance ordenado " + index); }
        var ordered = approved(fixture);
        List<UUID> finalDrafts = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            clock.advance(61); finalDrafts.add(saveJob(fixture.provider(), fixture.id(), "FINAL_DELIVERY", "Final seleccionado fuera del primer tramo", null, Optional.empty()));
        }
        UUID finalId = finalDrafts.stream().max(Comparator.comparing(UUID::toString)).orElseThrow();
        assertThat(finalId.toString()).isGreaterThan(ordered.get(9).resourceId().toString());
        var delivery = new ContentReference(SurfaceKind.JOB_FEEDBACK, finalId, jdbc.queryForObject("select current_revision from jobs_authored where id=?", UUID.class, finalId));
        submitJob(fixture.provider(), fixture.id(), delivery); approve(delivery);
        assertThat(approved(fixture).indexOf(delivery)).isGreaterThanOrEqualTo(10);
        var detail = mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var detailEntries = (List<JobContentReader.ContentView>) detail.getModelAndView().getModel().get("contents");
        assertThat(detailEntries).hasSize(11); assertThat(detailEntries.getFirst().reference()).isEqualTo(delivery);
        assertThat(detailEntries.stream().map(JobContentReader.ContentView::reference)).containsOnlyOnce(delivery);
        var response = mvc.perform(history(fixture, fixture.customer(), false)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("/jobs/accept-final", "/jobs/content");
        assertThat(page(response).entries()).extracting(JobContentReader.ContentView::reference).doesNotContain(delivery);
    }
    @Test void shouldKeepMessageAndEvidenceSelectionsIndependentOnRetainedPages() throws Exception {
        var fixture = negotiated(false);
        UUID thread = jobs.discover(fixture.id()).conversation().id();
        for (int index = 0; index < 11; index++) {
            clock.advance(61); evidence(fixture, "SUPPORT_NOTE", "Explicación " + index);
            var result = mvc.perform(multipart("/conversations/messages").session(fixture.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("thread", thread.toString()).param("subject", fixture.provider().id().toString()).param("kind", "PRIVATE_MESSAGE").param("body", "Mensaje conservado " + index).param("label", "GENERAL"))
                    .andExpect(status().is3xxRedirection()).andReturn();
            String location = result.getResponse().getRedirectedUrl(); UUID id = UUID.fromString(location.substring(location.indexOf("&message=") + 9));
            var reference = new ContentReference(SurfaceKind.PRIVATE_MESSAGE, id, jdbc.queryForObject("select current_revision from conversations_authored where id=?", UUID.class, id));
            mvc.perform(post("/conversations/submit").session(fixture.provider().session()).with(csrf()).param("thread", thread.toString()).param("subject", fixture.provider().id().toString()).param("kind", reference.ownerKind().name()).param("resource", id.toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
            approve(reference);
        }
        var messageIds = jdbc.queryForList("select id from conversations_authored where thread_id=? order by id::text", UUID.class, thread);
        var evidenceIds = approved(fixture);
        String messageCursor = messageIds.get(9).toString(), evidenceCursor = evidenceIds.get(9).resourceId().toString();
        var observed = mvc.perform(history(fixture, fixture.customer(), true).param("messageAfter", messageCursor).param("evidenceAfter", evidenceCursor)).andExpect(status().isOk()).andReturn();
        assertThat(page(observed).entries()).hasSize(1);
        assertThat((List<?>) observed.getModelAndView().getModel().get("messages")).hasSize(1);
        String html = observed.getResponse().getContentAsString();
        assertThat(html).contains("messageAfter=" + messageCursor + "&amp;evidenceBefore=", "evidenceAfter=" + evidenceCursor);
        mvc.perform(post("/conversations/block").session(fixture.customer().session()).with(csrf()).param("thread", thread.toString()).param("subject", fixture.customer().id().toString()).param("blocked", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(history(fixture, fixture.customer(), false).param("evidenceAfter", evidenceCursor)).andExpect(status().isForbidden());
        assertThat(observe(fixture, fixture.customer(), true, evidenceCursor, null).entries()).hasSize(1);
    }
    @Test void shouldFailClosedOnCorruptUnknownOrUnavailableMetadataOnEveryPage() throws Exception {
        var fixture = negotiated(false); var reference = evidence(fixture, "SUPPORT_NOTE", "Evidencia exacta protegida");
        var references = Set.of(reference, jobs.discover(fixture.id()).agreement().orElseThrow());
        ar.changeo.moderation.ModerationService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(moderationMetadata);
        try {
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic metadata outage")).when(target).approvalsForUpdate(references);
            for (boolean retained : List.of(false, true)) { mvc.perform(history(fixture, fixture.customer(), retained)).andExpect(status().isServiceUnavailable()); }
            reset(target);
            for (String invalid : List.of("digest", "state", "label")) {
                doAnswer(invocation -> {
                    @SuppressWarnings("unchecked") var observed = new HashMap<ContentReference, ModerationAccess.Approval>((Map<ContentReference, ModerationAccess.Approval>) invocation.callRealMethod());
                    var current = observed.get(reference);
                    observed.put(reference, new ModerationAccess.Approval(reference, invalid.equals("digest") ? "invalid" : current.digest(), invalid.equals("state") ? "UNKNOWN" : current.state(), invalid.equals("label") ? "UNKNOWN" : current.label(), current.reason()));
                    return Map.copyOf(observed);
                }).when(target).approvalsForUpdate(references);
                for (boolean retained : List.of(false, true)) { mvc.perform(history(fixture, fixture.customer(), retained)).andExpect(status().isForbidden()).andExpect(content().string(not(containsString("Evidencia exacta protegida")))); }
                reset(target);
            }
        } finally { reset(target); }
    }
    List<ContentReference> approved(JobFixture fixture) {
        return jdbc.query("select id,kind,approved_revision from jobs_authored where job_id=? and approved_revision is not null order by id::text", (row, index) -> new ContentReference(JobContentStore.Kind.valueOf(row.getString(2)).surface(), row.getObject(1, UUID.class), row.getObject(3, UUID.class)), fixture.id());
    }
    String route(JobFixture fixture, boolean retained) { return "/jobs/" + fixture.id() + (retained ? "/support" : "/evidence"); }
    MockHttpServletRequestBuilder history(JobFixture fixture, AccountFixture actor, boolean retained) {
        return get(route(fixture, retained)).session(actor.session()).param("subject", actor.id().toString());
    }
    JobContentReader.EvidencePage observe(JobFixture fixture, AccountFixture actor, boolean retained, String after, String before) throws Exception {
        var request = history(fixture, actor, retained);
        if (after != null) { request.param("evidenceAfter", after); } if (before != null) { request.param("evidenceBefore", before); }
        return page(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }
    JobContentReader.EvidencePage page(org.springframework.test.web.servlet.MvcResult response) { return (JobContentReader.EvidencePage) response.getModelAndView().getModel().get("evidencePage"); }
    MockHttpServletRequestBuilder jobFile(ContentReference reference) {
        var file = jdbc.queryForMap("select file_id,digest from jobs_attachment where revision_id=?", reference.revisionId());
        return get("/conversations/files/" + file.get("file_id")).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("digest", file.get("digest").toString());
    }
    @Test void shouldRetainOriginalGuardianSideAfterRevokeAndDenySuccessorAndChild() throws Exception {
        var guardian = author(false, 30); var child = author(false, 17); var provider = author(true, 30); var successor = author(false, 30);
        mvc.perform(post("/admin/accounts/" + child.id() + "/eligibility").session(operator).with(csrf()).param("kind", "MINOR_DRAFT")).andExpect(status().is3xxRedirection());
        UUID link = linkGuardian(guardian, child);
        var listing = offer(provider); UUID thread = respond(guardian, child.id(), listing);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var terms = quote(provider, job);
        mvc.perform(accept(guardian, child.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), terms)).andExpect(status().is3xxRedirection());
        var fixture = new JobFixture(job, provider, new AccountFixture(child.id(), guardian.session()));
        for (int index = 0; index < 11; index++) { clock.advance(61); evidence(fixture, "SUPPORT_NOTE", "Ayuda del acuerdo original " + index); }
        var first = observe(fixture, fixture.customer(), true, null, null); String cursor = first.next().orElseThrow().toString();
        var last = approved(fixture).get(10); var adult = approved(fixture).getFirst();
        decide(adult, "CORRECT_LABEL", "ADULT");
        for (int attempt = 0; attempt < 10; attempt++) { if (!worker.processOne()) { break; } }
        assertThat(jdbc.queryForObject("select s.state from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, adult.revisionId())).isEqualTo("APPROVED");
        mvc.perform(post("/account/guardian/" + link + "/revoke").session(guardian.session()).with(csrf()).param("version", "1")).andExpect(status().is3xxRedirection());
        mvc.perform(history(fixture, fixture.customer(), false).param("evidenceAfter", cursor)).andExpect(status().isForbidden());
        assertThat(observe(fixture, fixture.customer(), true, cursor, null).entries()).extracting(JobContentReader.ContentView::reference).containsExactly(last);
        mvc.perform(jobFile(last).session(guardian.session())).andExpect(status().isOk());
        linkGuardian(successor, child);
        for (var denied : List.of(child, successor)) {
            mvc.perform(get(route(fixture, true)).session(denied.session()).param("subject", child.id().toString()).param("evidenceAfter", cursor)).andExpect(status().isForbidden());
            mvc.perform(jobFile(last).session(denied.session())).andExpect(status().isBadRequest());
        }
        assertThat(observe(fixture, fixture.customer(), true, cursor, null).entries()).hasSize(1);
        assertThat(observe(fixture, fixture.customer(), true, null, null).entries()).extracting(JobContentReader.ContentView::reference).doesNotContain(adult);
        mvc.perform(jobFile(adult).session(guardian.session())).andExpect(status().isForbidden());
        mvc.perform(post("/conversations/leave").session(guardian.session()).with(csrf()).param("thread", thread.toString()).param("subject", child.id().toString())).andExpect(status().isForbidden());
        assertThat(observe(fixture, fixture.customer(), true, cursor, null).entries()).hasSize(1);
    }
    UUID linkGuardian(AccountFixture guardian, AccountFixture child) throws Exception {
        mvc.perform(post("/account/guardian/request").session(guardian.session()).with(csrf()).param("other", child.id().toString()).param("actingAsGuardian", "true")).andExpect(status().is3xxRedirection());
        UUID link = jdbc.queryForObject("select id from identity_guardian_link where minor_id=? and status='PENDING'", UUID.class, child.id());
        mvc.perform(post("/admin/guardian/" + link + "/review").session(operator).with(csrf()).param("version", "0").param("verified", "true")).andExpect(status().is3xxRedirection());
        return link;
    }
    @Test void shouldPreserveSelectedMembershipAndDenyUnselectedLeftHistory() throws Exception {
        var fixture = negotiated(false); var reference = evidence(fixture, "SUPPORT_NOTE", "Ayuda del acuerdo seleccionado");
        UUID thread = jobs.discover(fixture.id()).conversation().id();
        mvc.perform(post("/conversations/leave").session(fixture.customer().session()).with(csrf()).param("thread", thread.toString()).param("subject", fixture.customer().id().toString())).andExpect(status().isForbidden());
        assertThat(observe(fixture, fixture.customer(), true, null, null).entries()).extracting(JobContentReader.ContentView::reference).containsExactly(reference);
        var listing = offer(fixture.provider()); UUID leftThread = respond(fixture.customer(), fixture.customer().id(), listing);
        UUID leftJob = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, leftThread);
        var unselected = new JobFixture(leftJob, fixture.provider(), fixture.customer());
        mvc.perform(history(unselected, fixture.customer(), true)).andExpect(status().isForbidden());
        mvc.perform(post("/conversations/leave").session(fixture.customer().session()).with(csrf()).param("thread", leftThread.toString()).param("subject", fixture.customer().id().toString())).andExpect(status().is3xxRedirection());
        for (boolean retained : List.of(false, true)) { mvc.perform(history(unselected, fixture.customer(), retained).param("evidenceAfter", reference.resourceId().toString())).andExpect(status().isForbidden()); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shouldRecheckRecalledPointerBetweenDiscoveryAndIndependentProjection(boolean retained) throws Exception {
        var fixture = negotiated(false); var reference = evidence(fixture, "SUPPORT_NOTE", "Evidencia retirada entre observaciones");
        var projectionReady = new java.util.concurrent.CountDownLatch(1); var recalled = new java.util.concurrent.CountDownLatch(1);
        JobContentStore target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(contentStore);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().name("job-evidence-read").factory())) {
            doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("job-evidence-read")) {
                    projectionReady.countDown(); assertThat(recalled.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
                return invocation.callRealMethod();
            }).when(target).resource(reference.resourceId(), false);
            var observed = executor.submit(() -> mvc.perform(history(fixture, fixture.customer(), retained)).andExpect(status().isOk()).andReturn());
            try {
                assertThat(projectionReady.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                decide(reference, "RECALL", "GENERAL");
            } finally { recalled.countDown(); }
            assertThat(page(observed.get(15, java.util.concurrent.TimeUnit.SECONDS)).entries()).isEmpty();
        } finally { reset(target); }
        mvc.perform(jobFile(reference).session(fixture.customer().session())).andExpect(status().isForbidden());
    }
    @Test void shouldOrderEqualTimeEvidenceByReferenceAndExcludeUnsubmittedRowsWithoutMutation() throws Exception {
        var fixture = negotiated(false);
        UUID first = saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Primera evidencia simultánea", null, Optional.empty());
        UUID second = saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Segunda evidencia simultánea", null, Optional.empty());
        assertThat(jdbc.queryForList("select distinct created_at from jobs_revision where resource_id in (?,?)", java.sql.Timestamp.class, first, second)).hasSize(1);
        for (UUID id : List.of(first, second)) {
            var reference = new ContentReference(SurfaceKind.JOB_FEEDBACK, id, jdbc.queryForObject("select current_revision from jobs_authored where id=?", UUID.class, id));
            submitJob(fixture.provider(), fixture.id(), reference); approve(reference);
        }
        saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Sin moderación no entregada", null, Optional.empty());
        var before = effects();
        assertThat(observe(fixture, fixture.customer(), false, null, null).entries()).extracting(entry -> entry.reference().resourceId()).containsExactlyElementsOf(List.of(first, second).stream().sorted(Comparator.comparing(UUID::toString)).toList());
        var response = mvc.perform(history(fixture, fixture.customer(), false)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store"))).andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'"))).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("Sin moderación no entregada", "evidence-next");
        assertThat(effects()).isEqualTo(before);
    }
    Map<String, Object> effects() {
        return jdbc.queryForMap("select (select count(*) from jobs_authored) as resources, (select count(*) from jobs_revision) as revisions, (select count(*) from jobs_receipt) as receipts, (select count(*) from jobs_event) as events, (select count(*) from files_artifact) as files, (select count(*) from notifications_intent) as notices");
    }
    @Test void shouldKeepRetainedHistorySeparateFromStrictNoticesAndReportCsrf() throws Exception {
        var fixture = negotiated(false); var reference = evidence(fixture, "SUPPORT_NOTE", "Evidencia reportable con acceso actual");
        var result = mvc.perform(history(fixture, fixture.customer(), false)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("/conversations/cases", "name=\"_csrf\"");
        mvc.perform(post("/conversations/cases").session(fixture.customer().session()).param("command", UUID.randomUUID().toString()).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("caseKind", "REPORT").param("reason", "SAFETY")).andExpect(status().isForbidden());
        var notices = mvc.perform(get("/notifications").session(fixture.customer().session())).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var beforeNotices = (List<ar.changeo.notifications.NotificationService.Notice>) notices.getModelAndView().getModel().get("notices");
        assertThat(beforeNotices).extracting(ar.changeo.notifications.NotificationService.Notice::reference).contains(reference);
        UUID thread = jobs.discover(fixture.id()).conversation().id();
        mvc.perform(post("/conversations/block").session(fixture.customer().session()).with(csrf()).param("thread", thread.toString()).param("subject", fixture.customer().id().toString()).param("blocked", "true")).andExpect(status().is3xxRedirection());
        assertThat(observe(fixture, fixture.customer(), true, null, null).entries()).extracting(JobContentReader.ContentView::reference).containsExactly(reference);
        var hidden = mvc.perform(get("/notifications").session(fixture.customer().session())).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var afterNotices = (List<ar.changeo.notifications.NotificationService.Notice>) hidden.getModelAndView().getModel().get("notices");
        assertThat(afterNotices).extracting(ar.changeo.notifications.NotificationService.Notice::reference).doesNotContain(reference);
        mvc.perform(history(fixture, fixture.customer(), true)).andExpect(status().isOk()).andExpect(content().string(not(containsString("/conversations/cases"))));
    }
}
