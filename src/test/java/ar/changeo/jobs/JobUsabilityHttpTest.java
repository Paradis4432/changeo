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

@SpringBootTest(classes = {ChangeoApplication.class, JobUsabilityHttpTest.Configuration.class}, properties = {
        "spring.flyway.schemas=job_usability_http_test", "spring.flyway.default-schema=job_usability_http_test",
        "spring.jpa.properties.hibernate.default_schema=job_usability_http_test", "spring.datasource.hikari.schema=job_usability_http_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.mfa-key=.runtime/job-usability-http-mfa.key", "changeo.files-root=.runtime/job-usability-http-artifacts"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class JobUsabilityHttpTest {
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
        Path path = Path.of(".runtime/job-ui-enrollment-" + UUID.randomUUID());
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
    @Test void shouldKeepExactFinalAvailableWhenOtherApprovedEvidenceIsSuppressed() throws Exception {
        var fixture = negotiated(true);
        var progress = evidence(fixture, "PROGRESS", "Avance privado sensible no mostrado");
        var delivery = evidence(fixture, "FINAL_DELIVERY", "Entrega final visible exacta");
        decide(progress, "CORRECT_LABEL", "SENSITIVE"); accounts.preferences(fixture.customer().id(), false, false);
        mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Entrega final visible exacta")))
                .andExpect(content().string(not(containsString("Avance privado sensible no mostrado"))))
                .andExpect(content().string(containsString("/jobs/accept-final")));
        assertThat(jobs.discover(fixture.id()).finalDelivery()).contains(delivery);
    }
    @Test void shouldHideAcceptanceWhenSelectedFinalIsRecalled() throws Exception {
        var fixture = negotiated(true); var delivery = evidence(fixture, "FINAL_DELIVERY", "Entrega retirada no mostrada");
        decide(delivery, "RECALL", "GENERAL");
        mvc.perform(jobFile(delivery).session(fixture.customer().session())).andExpect(status().isForbidden());
        mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/jobs/accept-final"))))
                .andExpect(content().string(containsString("La entrega final no está disponible")));
    }
    @Test void shouldPreserveEffectiveClassificationInAuthoredEdit() throws Exception {
        var fixture = negotiated(true); var progress = evidence(fixture, "PROGRESS", "Avance con clasificación corregida");
        decide(progress, "CORRECT_LABEL", "SENSITIVE");
        mvc.perform(get("/jobs/" + fixture.id() + "/authored").session(fixture.provider().session())
                        .param("subject", fixture.provider().id().toString()).param("content", progress.resourceId().toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("value=\"SENSITIVE\" selected=\"selected\"")));
    }
    @Test void shouldRecoverSubmittedTextAndScoreErrorWithoutSavingContent() throws Exception {
        var fixture = negotiated(true);
        long before = jdbc.queryForObject("select count(*) from jobs_revision", Long.class);
        mvc.perform(multipart("/jobs/content").session(fixture.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString())
                        .param("job", fixture.id().toString()).param("subject", fixture.provider().id().toString()).param("kind", "PROGRESS")
                        .param("body", "Texto recuperable <script>sin ejecutar</script>").param("score", "incorrecto").param("label", "SENSITIVE"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Texto recuperable &lt;script&gt;sin ejecutar&lt;/script&gt;")))
                .andExpect(content().string(containsString("job-score-error")));
        assertThat(jdbc.queryForObject("select count(*) from jobs_revision", Long.class)).isEqualTo(before);
    }

    @Test void shouldProtectFinalAudienceFilesAndAcceptanceWithoutAnyCompletionEffect() throws Exception {
        var fixture = negotiated(true); var stranger = author(false, 30);
        var delivery = evidence(fixture, "FINAL_DELIVERY", "Final sensible protegido");
        decide(delivery, "CORRECT_LABEL", "SENSITIVE"); accounts.preferences(fixture.customer().id(), false, false);
        mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Final sensible protegido"))))
                .andExpect(content().string(not(containsString("/jobs/accept-final"))))
                .andExpect(content().string(containsString("La entrega final no está disponible")));
        mvc.perform(jobFile(delivery).session(fixture.customer().session())).andExpect(status().isForbidden());
        mvc.perform(jobFile(delivery).session(stranger.session())).andExpect(status().isBadRequest());
        long version = jobs.discover(fixture.id()).version();
        mvc.perform(acceptFinal(fixture, fixture.customer(), delivery, version)).andExpect(status().isForbidden());
        mvc.perform(acceptFinal(fixture, fixture.provider(), delivery, version)).andExpect(status().isBadRequest());
        mvc.perform(detail(fixture, stranger)).andExpect(status().isForbidden());
        mvc.perform(get("/jobs/" + fixture.id()).session(fixture.customer().session()).param("subject", fixture.provider().id().toString())).andExpect(status().isForbidden());
        accounts.preferences(fixture.customer().id(), true, false);
        mvc.perform(jobFile(delivery).session(fixture.customer().session())).andExpect(status().isOk()).andExpect(content().bytes("synthetic:clear".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk()).andExpect(content().string(containsString("/jobs/accept-final")));
        mvc.perform(acceptFinal(fixture, fixture.customer(), delivery, version - 1)).andExpect(status().isForbidden());
        mvc.perform(post("/jobs/accept-final").session(fixture.customer().session()).param("command", UUID.randomUUID().toString()).param("job", fixture.id().toString())
                .param("subject", fixture.customer().id().toString()).param("version", Long.toString(version)).param("resource", delivery.resourceId().toString()).param("revision", delivery.revisionId().toString())).andExpect(status().isForbidden());
        assertThat(jobs.discover(fixture.id()).version()).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='COMPLETED'", Integer.class, fixture.id())).isZero();
    }
    @Test void shouldAlwaysDisplaySelectedFinalFirstOnceAlongsideBoundedEvidence() throws Exception {
        var fixture = negotiated(true);
        for (int index = 0; index < 12; index++) { clock.advance(61); evidence(fixture, "SUPPORT_NOTE", "Nota aprobada " + index); }
        var delivery = evidence(fixture, "FINAL_DELIVERY", "Final prominente independiente del límite");
        var result = mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked")
        var contents = (List<JobContentReader.ContentView>) result.getModelAndView().getModel().get("contents");
        assertThat(contents).hasSizeBetween(10, 11);
        assertThat(contents.getFirst().reference()).isEqualTo(delivery);
        assertThat(contents.stream().map(JobContentReader.ContentView::reference).filter(delivery::equals).count()).isEqualTo(1);
        assertThat(result.getResponse().getContentAsString()).contains("Entrega final seleccionada", "/jobs/accept-final");
    }
    @Test void shouldGuardRecoverableErrorsAgainstForeignStaleAndConcealedEdits() throws Exception {
        var fixture = negotiated(true); var stranger = author(false, 30);
        var progress = evidence(fixture, "PROGRESS", "Avance propio protegido");
        var original = jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, progress.resourceId());
        for (var actor : List.of(stranger, fixture.customer())) {
            mvc.perform(invalidEdit(fixture, actor, progress, original)).andExpect(status().is4xxClientError())
                    .andExpect(content().string(not(containsString("No mostrar texto presentado"))));
        }
        mvc.perform(invalidEdit(fixture, fixture.provider(), progress, original - 1)).andExpect(status().isForbidden());
        decide(progress, "CORRECT_LABEL", "ADULT"); accounts.preferences(fixture.provider().id(), true, false);
        var concealed = jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, progress.resourceId());
        mvc.perform(invalidEdit(fixture, fixture.provider(), progress, concealed)).andExpect(status().isForbidden());
        mvc.perform(get("/jobs/" + fixture.id() + "/authored").session(fixture.provider().session()).param("subject", fixture.provider().id().toString()).param("content", progress.resourceId().toString()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("Editar evidencia: crea una revisión nueva"))));
        assertThat(jdbc.queryForObject("select count(*) from jobs_revision where resource_id=?", Integer.class, progress.resourceId())).isEqualTo(1);
    }
    @Test void shouldRecoverInvalidKindBodyLabelAndContradictoryScoreWithoutReceipts() throws Exception {
        var fixture = negotiated(true);
        long before = jdbc.queryForObject("select count(*) from jobs_receipt", Long.class);
        for (var form : List.of(new JobForms.Content("UNKNOWN", "Texto retenido", "", "GENERAL"), new JobForms.Content("PROGRESS", " ", "", "GENERAL"),
                new JobForms.Content("SUPPORT_NOTE", "Texto retenido", "5", "GENERAL"), new JobForms.Content("SUPPORT_NOTE", "Texto retenido", "", "<script>"))) {
            mvc.perform(multipart("/jobs/content").session(fixture.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", fixture.id().toString())
                            .param("subject", fixture.provider().id().toString()).param("kind", form.kind()).param("body", form.body()).param("score", form.score()).param("label", form.label()))
                    .andExpect(status().isBadRequest()).andExpect(view().name("job-content-form"))
                    .andExpect(header().string("Cache-Control", containsString("no-store"))).andExpect(content().string(containsString("role=\"alert\"")))
                    .andExpect(content().string(containsString("Volvé a seleccionar los archivos"))).andExpect(content().string(not(containsString("<script>"))));
        }
        assertThat(jdbc.queryForObject("select count(*) from jobs_receipt", Long.class)).isEqualTo(before);
    }
    org.springframework.test.web.servlet.RequestBuilder invalidEdit(JobFixture fixture, AccountFixture actor, ContentReference reference, long version) {
        return multipart("/jobs/content").session(actor.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", fixture.id().toString())
                .param("subject", actor.id().toString()).param("resource", reference.resourceId().toString()).param("version", Long.toString(version)).param("kind", "PROGRESS")
                .param("body", "No mostrar texto presentado").param("score", "incorrecto").param("label", "GENERAL");
    }
    MockHttpServletRequestBuilder acceptFinal(JobFixture fixture, AccountFixture actor, ContentReference reference, long version) {
        return post("/jobs/accept-final").session(actor.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", fixture.id().toString())
                .param("subject", actor.id().toString()).param("version", Long.toString(version)).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString());
    }

    MockHttpServletRequestBuilder jobFile(ContentReference reference) {
        var file = jdbc.queryForMap("select file_id,digest from jobs_attachment where revision_id=?", reference.revisionId());
        return get("/conversations/files/" + file.get("file_id")).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString())
                .param("revision", reference.revisionId().toString()).param("digest", file.get("digest").toString());
    }
    @Test void shouldPropagateOutagesAndCorruptMetadataRatherThanSuppressThem() throws Exception {
        var fixture = negotiated(true); var delivery = evidence(fixture, "FINAL_DELIVERY", "Final exacto ante fallas");
        var terms = jobs.discover(fixture.id()).agreement().orElseThrow(); var references = Set.of(terms, delivery);
        ar.changeo.moderation.ModerationService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(moderationMetadata);
        long version = jobs.discover(fixture.id()).version();
        try {
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic metadata outage")).when(target).approvalsForUpdate(references);
            mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isServiceUnavailable()).andExpect(content().string(not(containsString("Final exacto ante fallas"))));
            reset(target);
            for (boolean badLabel : List.of(true, false)) {
                doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var observed = new HashMap<ContentReference, ModerationAccess.Approval>((Map<ContentReference, ModerationAccess.Approval>) invocation.callRealMethod());
                    var current = observed.get(delivery);
                    observed.put(delivery, new ModerationAccess.Approval(delivery, badLabel ? current.digest() : "invalid", current.state(), badLabel ? "UNKNOWN" : current.label(), current.reason()));
                    return Map.copyOf(observed);
                }).when(target).approvalsForUpdate(references);
                mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isForbidden()).andExpect(content().string(not(containsString("Final exacto ante fallas"))));
                reset(target);
            }
        } finally { reset(target); }
        assertThat(jobs.discover(fixture.id()).version()).isEqualTo(version);
        mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk()).andExpect(content().string(containsString("/jobs/accept-final")));
    }
    @Test void shouldPreserveDraftRecoveryDespiteRecalledTermsWithoutGrantingDelivery() throws Exception {
        var fixture = negotiated(false);
        var note = saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Explicación propia previa", null, Optional.empty());
        decide(jobs.discover(fixture.id()).agreement().orElseThrow(), "RECALL", "GENERAL");
        mvc.perform(multipart("/jobs/content").session(fixture.provider().session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("job", fixture.id().toString()).param("subject", fixture.provider().id().toString()).param("resource", note.toString())
                .param("version", Long.toString(jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, note)))
                .param("kind", "SUPPORT_NOTE").param("body", "Explicación recuperable").param("score", "5").param("label", "GENERAL"))
                .andExpect(status().isBadRequest()).andExpect(view().name("job-content-form")).andExpect(content().string(containsString("Explicación recuperable"))).andExpect(content().string(containsString("value=\"Explicación para ayuda\"")));
        assertThat(jdbc.queryForObject("select count(*) from jobs_revision where resource_id=?", Integer.class, note)).isEqualTo(1);
    }

    @Test void shouldDenyRecoveryAfterActualGuardianRevocationWithoutEchoOrSave() throws Exception {
        var guardian = author(false, 30); var child = author(false, 17); var provider = author(true, 30);
        mvc.perform(post("/admin/accounts/" + child.id() + "/eligibility").session(operator).with(csrf()).param("kind", "MINOR_DRAFT")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/account/guardian/request").session(guardian.session()).with(csrf()).param("other", child.id().toString()).param("actingAsGuardian", "true")).andExpect(status().is3xxRedirection());
        UUID link = jdbc.queryForObject("select id from identity_guardian_link where minor_id=? and status='PENDING'", UUID.class, child.id());
        mvc.perform(post("/admin/guardian/" + link + "/review").session(operator).with(csrf()).param("version", "0").param("verified", "true")).andExpect(status().is3xxRedirection());
        var listing = offer(provider); var thread = respond(guardian, child.id(), listing);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var terms = quote(provider, job);
        mvc.perform(accept(guardian, child.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), terms)).andExpect(status().is3xxRedirection());
        long before = jdbc.queryForObject("select count(*) from jobs_revision", Long.class);
        mvc.perform(post("/account/guardian/" + link + "/revoke").session(guardian.session()).with(csrf()).param("version", "1")).andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/jobs/content").session(guardian.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString())
                        .param("subject", child.id().toString()).param("kind", "SUPPORT_NOTE").param("body", "Texto sin autoridad actual no mostrado").param("score", "incorrecto").param("label", "GENERAL"))
                .andExpect(status().isForbidden()).andExpect(content().string(not(containsString("Texto sin autoridad actual no mostrado"))));
        assertThat(jdbc.queryForObject("select count(*) from jobs_revision", Long.class)).isEqualTo(before);
    }
    @Test void shouldBoundRetainedInvalidTextAndAllowCorrectionWithSameCommand() throws Exception {
        var fixture = negotiated(false); UUID command = UUID.randomUUID();
        var result = mvc.perform(multipart("/jobs/content").session(fixture.provider().session()).with(csrf()).param("command", command.toString())
                        .param("job", fixture.id().toString()).param("subject", fixture.provider().id().toString()).param("kind", "SUPPORT_NOTE")
                        .param("body", "B".repeat(12001)).param("label", "GENERAL"))
                .andExpect(status().isBadRequest()).andExpect(view().name("job-content-form")).andReturn();
        assertThat(((JobForms.Content) result.getModelAndView().getModel().get("form")).body()).hasSize(12000);
        assertThat(jdbc.queryForObject("select count(*) from jobs_receipt where command_id=?", Integer.class, command)).isZero();
        mvc.perform(multipart("/jobs/content").session(fixture.provider().session()).with(csrf()).param("command", command.toString())
                        .param("job", fixture.id().toString()).param("subject", fixture.provider().id().toString()).param("kind", "SUPPORT_NOTE")
                        .param("body", "Texto corregido y todavía privado").param("label", "GENERAL"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select count(*) from jobs_receipt where command_id=?", Integer.class, command)).isEqualTo(1);
        mvc.perform(detail(fixture, fixture.customer())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Texto corregido y todavía privado"))));
    }
    @Test void shouldReplayExactSuccessfulEditWithoutAnotherRevisionReceiptOrFile() throws Exception {
        var fixture = negotiated(false);
        var resource = saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Nota privada inicial", null, Optional.empty());
        long version = jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, resource);
        UUID command = UUID.randomUUID();
        String location = mvc.perform(editNote(fixture, fixture.provider(), resource, version, command, "Edición privada exacta"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var effects = contentEffects();
        var current = jdbc.queryForMap("select current_revision,version from jobs_authored where id=?", resource);
        mvc.perform(editNote(fixture, fixture.provider(), resource, version, command, "Edición privada exacta"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(location));
        assertThat(contentEffects()).isEqualTo(effects);
        assertThat(jdbc.queryForMap("select current_revision,version from jobs_authored where id=?", resource)).isEqualTo(current);
        assertThat(jdbc.queryForObject("select count(*) from jobs_receipt where command_id=?", Integer.class, command)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from jobs_revision where resource_id=?", Integer.class, resource)).isEqualTo(2);
    }
    @Test void shouldDenyConflictingAndStaleEditsWithoutEchoOrEffects() throws Exception {
        var fixture = negotiated(false);
        var resource = saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Nota privada inicial", null, Optional.empty());
        var other = saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Otra nota privada", null, Optional.empty());
        long version = jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, resource);
        long otherVersion = jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, other);
        UUID command = UUID.randomUUID();
        mvc.perform(editNote(fixture, fixture.provider(), resource, version, command, "Edición privada exacta")).andExpect(status().is3xxRedirection());
        var effects = contentEffects();
        String body = "Texto conflictivo no mostrado";
        for (var request : List.of(editNote(fixture, fixture.provider(), resource, version, command, body),
                editNote(fixture, fixture.provider(), other, otherVersion, command, "Edición privada exacta"),
                editNote(fixture, fixture.provider(), resource, version, UUID.randomUUID(), body))) {
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(body))))
                    .andExpect(content().string(not(containsString("Edición privada exacta"))));
            assertThat(contentEffects()).isEqualTo(effects);
        }
        mvc.perform(editNote(fixture, fixture.provider(), resource, version, UUID.randomUUID(), body).param("score", "incorrecto"))
                .andExpect(status().isForbidden()).andExpect(content().string(not(containsString(body))));
        assertThat(contentEffects()).isEqualTo(effects);
    }
    @Test void shouldDenySavedCommandReplayForWrongSideOrConcealedCurrentResource() throws Exception {
        var fixture = negotiated(false);
        var resource = saveJob(fixture.provider(), fixture.id(), "SUPPORT_NOTE", "Nota privada inicial", null, Optional.empty());
        long version = jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, resource);
        UUID command = UUID.randomUUID(); String body = "Edición privada exacta no mostrada";
        mvc.perform(editNote(fixture, fixture.provider(), resource, version, command, body)).andExpect(status().is3xxRedirection());
        var effects = contentEffects();
        mvc.perform(editNote(fixture, fixture.customer(), resource, version, command, body))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(body))));
        assertThat(contentEffects()).isEqualTo(effects);
        var current = new ContentReference(SurfaceKind.JOB_FEEDBACK, resource,
                jdbc.queryForObject("select current_revision from jobs_authored where id=?", UUID.class, resource));
        submitJob(fixture.provider(), fixture.id(), current); approve(current); decide(current, "CORRECT_LABEL", "ADULT");
        accounts.preferences(fixture.provider().id(), true, false);
        effects = contentEffects();
        mvc.perform(editNote(fixture, fixture.provider(), resource, version, command, body))
                .andExpect(status().isForbidden()).andExpect(content().string(not(containsString(body))));
        assertThat(contentEffects()).isEqualTo(effects);
    }
    org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder editNote(JobFixture fixture, AccountFixture actor, UUID resource, long version, UUID command, String body) {
        return multipart("/jobs/content").file(new MockMultipartFile("uploads", "nuevo.txt", "text/plain", "synthetic:clear edit".getBytes(StandardCharsets.UTF_8)))
                .session(actor.session()).with(csrf()).param("command", command.toString()).param("job", fixture.id().toString())
                .param("subject", actor.id().toString()).param("resource", resource.toString()).param("version", Long.toString(version))
                .param("kind", "SUPPORT_NOTE").param("body", body).param("label", "GENERAL");
    }
    Map<String, Object> contentEffects() {
        return jdbc.queryForMap("select (select count(*) from jobs_authored) as resources, (select count(*) from jobs_revision) as revisions, "
                + "(select count(*) from jobs_attachment) as attachments, (select count(*) from files_artifact) as files, "
                + "(select count(*) from jobs_receipt) as receipts, (select sum(version) from jobs_reputation_cohort) as reputation, "
                + "(select count(*) from jobs_event) as events");
    }
    record JobFixture(UUID id, AccountFixture provider, AccountFixture customer) {}
}
