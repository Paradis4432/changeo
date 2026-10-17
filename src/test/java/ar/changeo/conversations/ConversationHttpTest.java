package ar.changeo.conversations;

import ar.changeo.ChangeoApplication;
import ar.changeo.agreements.AgreementController;
import ar.changeo.identity.*;
import ar.changeo.marketplace.MarketplaceController;
import ar.changeo.moderation.ModerationWorker;
import ar.changeo.moderation.ReviewInput;
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

@SpringBootTest(classes = {ChangeoApplication.class, ConversationHttpTest.Configuration.class}, properties = {
        "spring.flyway.schemas=conversations_http_test", "spring.flyway.default-schema=conversations_http_test",
        "spring.jpa.properties.hibernate.default_schema=conversations_http_test", "spring.datasource.hikari.schema=conversations_http_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.mfa-key=.runtime/conversations-http-mfa.key", "changeo.files-root=.runtime/conversations-http-artifacts"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ConversationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operators;
    @Autowired ModerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired ar.changeo.jobs.api.JobAccess jobs;
    @Autowired HttpFundingFacts fundingFacts;
    @MockitoBean TokenDelivery delivery;
    @MockitoSpyBean ar.changeo.moderation.SyntheticReviewEngine reviewEngine;
    @MockitoSpyBean ar.changeo.moderation.ModerationService moderationMetadata;
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    final List<MockHttpSession> sessions = new ArrayList<>();
    Properties enrollment;
    MockHttpSession operator;
    Set<Path> previousFiles;

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
        previousFiles = artifactFiles(); clock.advance(61); tokens.clear(); jdbc.update("delete from moderation_task");
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true;
        });
        Path path = Path.of(".runtime/c4-http-enrollment-" + UUID.randomUUID());
        try {
            operators.provision("c4httpoperator" + UUID.randomUUID() + "@example.test", Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER"), path);
            enrollment = new Properties(); try (var input = Files.newBufferedReader(path)) { enrollment.load(input); }
        } finally { Files.deleteIfExists(path); }
        operator = login(enrollment.getProperty("contact"), enrollment.getProperty("password"));
        operatorProof();
    }
    @AfterEach void cleanup() throws Exception {
        if (enrollment != null) { enrollment.clear(); }
        for (var session : sessions) { if (!session.isInvalid()) { session.invalidate(); } }
        sessions.clear(); tokens.clear();
        for (Path file : artifactFiles()) { if (!previousFiles.contains(file)) { Files.deleteIfExists(file); } }
    }
    Set<Path> artifactFiles() throws Exception {
        Path root = Path.of(".runtime/conversations-http-artifacts");
        if (!Files.exists(root)) { return Set.of(); }
        try (var paths = Files.list(root)) { return Set.copyOf(paths.toList()); }
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
        String contact = "c4http" + UUID.randomUUID() + "@example.test", password = "Synthetic-password-12345";
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
    ContentReference message(AccountFixture actor, UUID thread, UUID subject, String body) throws Exception {
        mvc.perform(multipart("/conversations/messages").file(new MockMultipartFile("uploads", "synthetic.txt", "text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8)))
                .session(actor.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("thread", thread.toString()).param("subject", subject.toString())
                .param("kind", "PRIVATE_MESSAGE").param("body", body).param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        return jdbc.queryForObject("select a.id,a.current_revision from conversations_authored a where thread_id=? order by a.id::text desc limit 1",
                (row, index) -> new ContentReference(SurfaceKind.PRIVATE_MESSAGE, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), thread);
    }
    void submit(AccountFixture actor, UUID thread, UUID subject, ContentReference reference) throws Exception {
        mvc.perform(post("/conversations/submit").session(actor.session()).with(csrf()).param("thread", thread.toString()).param("subject", subject.toString())
                .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
    }
    MockHttpServletRequestBuilder file(ContentReference reference) {
        return file(reference, "/conversations/files/");
    }
    MockHttpServletRequestBuilder file(ContentReference reference, String route) {
        var row = jdbc.queryForMap("select file_id,digest from conversations_attachment where revision_id=?", reference.revisionId());
        return get(route + row.get("file_id")).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString())
                .param("revision", reference.revisionId().toString()).param("digest", row.get("digest").toString());
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
    UUID guardian(AccountFixture controller, AccountFixture child) throws Exception {
        mvc.perform(post("/admin/accounts/" + child.id() + "/eligibility").session(operator).with(csrf()).param("kind", "MINOR_DRAFT")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/account/guardian/request").session(controller.session()).with(csrf()).param("other", child.id().toString()).param("actingAsGuardian", "true")).andExpect(status().is3xxRedirection());
        UUID link = jdbc.queryForObject("select id from identity_guardian_link where minor_id=? and status='PENDING'", UUID.class, child.id());
        mvc.perform(post("/admin/guardian/" + link + "/review").session(operator).with(csrf()).param("version", "0").param("verified", "true")).andExpect(status().is3xxRedirection()); return link;
    }
    @Test void shouldRenderActualSessionJourneyWithoutDisclosingPendingBodyOrFile() throws Exception {
        var provider = author(true, 30); var customer = author(false, 30); var stranger = author(false, 30); var listing = offer(provider);
        mvc.perform(get("/listings/" + listing.resourceId()).session(customer.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Abrir conversación privada")));
        mvc.perform(post("/conversations/respond").session(customer.session()).param("listing", listing.resourceId().toString()).param("subject", customer.id().toString()).param("mode", "REMOTE")).andExpect(status().isForbidden());
        UUID thread = respond(customer, customer.id(), listing);
        mvc.perform(get("/conversations/" + thread).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Mensajes entregados")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'"))).andExpect(header().string("Cache-Control", containsString("no-store")));
        var pending = message(customer, thread, customer.id(), "Privado <script>texto</script>");
        mvc.perform(get("/conversations/" + thread).session(provider.session()).param("subject", provider.id().toString())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Privado &lt;script&gt;"))));
        mvc.perform(file(pending).session(provider.session())).andExpect(status().isForbidden());
        submit(customer, thread, customer.id(), pending); approve(pending);
        mvc.perform(get("/conversations/" + thread).session(provider.session()).param("subject", provider.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Privado &lt;script&gt;texto&lt;/script&gt;")));
        mvc.perform(file(pending).session(provider.session())).andExpect(status().isOk()).andExpect(content().bytes("synthetic:clear".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(get("/conversations/" + thread + "/poll").session(stranger.session()).param("subject", provider.id().toString())).andExpect(status().isForbidden());
        mvc.perform(file(pending).session(stranger.session())).andExpect(status().isForbidden());
        mvc.perform(get("/notifications").session(provider.session())).andExpect(status().isOk());
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        mvc.perform(get("/jobs/" + job).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("no se confirmaron fondos")));
        mvc.perform(get("/agreements/new").session(provider.session()).param("job", job.toString()).param("subject", provider.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("1 de 2")));
        mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString()).param("subject", provider.id().toString())
                .param("kind", "INITIAL").param("scope", "Pieza completa").param("serviceAmount", "123.45").param("deliveryAmount", "0").param("deliverables", "Una pieza"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("2 de 2")));
        var composition = (AgreementController.Composition) provider.session().getAttribute("agreementComposition");
        mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString()).param("arrangement", "REMOTE")
                .param("arrangementDescription", "Coordinación remota").param("completionDeadline", "2026-10-07T12:00").param("feedbackRounds", "0")
                .param("completionEvidence", "TEXT").param("evidenceDescription", "Descripción completa").param("reviewWindowMinutes", "60").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/conversations/" + thread).session(provider.session()).param("subject", provider.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 123.45")));
    }

    @Test void shouldKeepActualHistoricPayerSupportAndBytesWithoutTransferringToNewGuardianSession() throws Exception {
        var provider = author(true, 30); var controller = author(false, 30); var child = author(false, 14);
        UUID relationship = guardian(controller, child); var listing = offer(provider); UUID thread = respond(controller, child.id(), listing);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var quote = quote(provider, job); mvc.perform(accept(controller, child.id(), quote)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), quote)).andExpect(status().is3xxRedirection());
        var message = message(provider, thread, provider.id(), "Historial privado aprobado del pagador original"); submit(provider, thread, provider.id(), message); approve(message);
        mvc.perform(file(message).session(controller.session())).andExpect(status().isOk());
        mvc.perform(post("/account/guardian/" + relationship + "/revoke").session(controller.session()).with(csrf()).param("version", "1")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/conversations/" + thread + "/poll").session(controller.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
        mvc.perform(accept(controller, child.id(), quote)).andExpect(status().isForbidden());
        mvc.perform(get("/jobs/" + job + "/support").session(controller.session()).param("subject", child.id().toString())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Historial privado aprobado del pagador original"))).andExpect(content().string(containsString("ARS 123.45")));
        mvc.perform(get("/jobs/support").session(controller.session())).andExpect(status().isOk()).andExpect(content().string(containsString(job.toString())));
        mvc.perform(file(message).session(controller.session())).andExpect(status().isOk()).andExpect(content().bytes("synthetic:clear".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(file(message).session(child.session())).andExpect(status().isForbidden());
        mvc.perform(get("/jobs/" + job + "/support").session(controller.session()).param("subject", provider.id().toString())).andExpect(status().isForbidden());
        var replacement = author(false, 30); guardian(replacement, child);
        mvc.perform(file(message).session(replacement.session())).andExpect(status().isForbidden());
        mvc.perform(get("/jobs/" + job + "/support").session(replacement.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/moderation/evidence").session(operator).param("kind", message.ownerKind().name()).param("resource", message.resourceId().toString())
                .param("revision", message.revisionId().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Historial privado aprobado")));
        mvc.perform(file(message, "/moderation/files/").session(operator)).andExpect(status().isOk()).andExpect(content().bytes("synthetic:clear".getBytes(StandardCharsets.UTF_8)));
        operator.removeAttribute("mfaUntil");
        mvc.perform(get("/moderation/evidence").session(operator).param("kind", message.ownerKind().name()).param("resource", message.resourceId().toString())
                .param("revision", message.revisionId().toString())).andExpect(status().isForbidden());
        mvc.perform(file(message, "/moderation/files/").session(operator)).andExpect(status().isForbidden());
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
    ContentReference authored(String table, UUID id, SurfaceKind kind) {
        UUID revision = jdbc.queryForObject("select current_revision from " + table + " where id=?", UUID.class, id);
        return new ContentReference(kind, id, revision);
    }
    @Test void shouldOpenDefaultPublicOwnIndexAndKeepBoundedContinuationPrivateThroughSessions() throws Exception {
        var provider = author(true, 30); var customer = author(false, 30); var observer = author(false, 30); var listing = offer(provider);
        mvc.perform(get("/conversations/discussion/mine").session(customer.session())).andExpect(status().isOk())
                .andExpect(content().string(containsString("No hay textos propios en esta página")));
        String firstPage = "/conversations/discussion/mine?subject=" + customer.id();
        mvc.perform(get("/listings/" + listing.resourceId()).session(customer.session())).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/conversations/discussion/mine\"")));
        Map<UUID, String> bodies = new HashMap<>();
        for (int index = 0; index < 12; index++) {
            clock.advance(61);
            String body = "Pregunta sintética propia número " + index + " final.";
            String location = mvc.perform(post("/conversations/discussion").session(customer.session()).with(csrf())
                    .param("command", UUID.randomUUID().toString()).param("listing", listing.resourceId().toString()).param("subject", customer.id().toString())
                    .param("kind", "QUESTION").param("body", body).param("label", "GENERAL")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            UUID id = UUID.fromString(location.substring("/conversations/authored/".length(), location.indexOf('?')));
            bodies.put(id, body);
            mvc.perform(get(location).session(customer.session())).andExpect(status().isOk()).andExpect(content().string(containsString(body)))
                    .andExpect(content().string(containsString("href=\"" + firstPage + "\"")));
        }
        String otherBody = "Pregunta sintética pendiente de otra persona.";
        String otherLocation = mvc.perform(post("/conversations/discussion").session(observer.session()).with(csrf())
                .param("command", UUID.randomUUID().toString()).param("listing", listing.resourceId().toString()).param("subject", observer.id().toString())
                .param("kind", "QUESTION").param("body", otherBody).param("label", "GENERAL")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var ordered = bodies.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        var first = mvc.perform(get("/conversations/discussion/mine").session(customer.session())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(otherBody)))).andReturn();
        assertThat(first.getModelAndView().getModel().get("next")).isEqualTo(Optional.of(ordered.get(9)));
        String firstHtml = first.getResponse().getContentAsString();
        for (int index = 0; index < ordered.size(); index++) { assertThat(firstHtml.contains(bodies.get(ordered.get(index)))).isEqualTo(index < 10); }
        var second = mvc.perform(get("/conversations/discussion/mine").session(customer.session()).param("after", ordered.get(9).toString()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(otherBody)))).andReturn();
        assertThat(second.getModelAndView().getModel().get("next")).isEqualTo(Optional.empty());
        String secondHtml = second.getResponse().getContentAsString();
        for (int index = 0; index < ordered.size(); index++) { assertThat(secondHtml.contains(bodies.get(ordered.get(index)))).isEqualTo(index >= 10); }
        mvc.perform(get(firstPage).session(customer.session())).andExpect(status().isOk()).andExpect(content().string(containsString(bodies.get(ordered.getFirst()))));
        mvc.perform(get("/conversations/discussion/mine").session(customer.session()).param("subject", observer.id().toString())).andExpect(status().isOk())
                .andExpect(content().string(containsString("No hay textos propios en esta página"))).andExpect(content().string(not(containsString(otherBody))));
        mvc.perform(get(otherLocation).session(customer.session())).andExpect(status().isForbidden());
        mvc.perform(get("/listings/" + listing.resourceId()).session(observer.session())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(bodies.get(ordered.getFirst())))));
    }

    @Test void shouldRenderNeutralCapturedContextAndRetainStructuralHelpThroughRealSessions() throws Exception {
        var provider = author(true, 30); var customer = author(false, 30); var listing = offer(provider); UUID thread = respond(customer, customer.id(), listing);
        decide(listing, "CORRECT_LABEL", "ADULT"); accounts.preferences(customer.id(), true, false);
        mvc.perform(get("/conversations/" + thread).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Piezas por encargo en sandbox")))).andExpect(content().string(not(containsString("Impresión 3D sintética"))));
        mvc.perform(get("/conversations").session(customer.session())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Impresión 3D sintética"))));
        decide(listing, "CORRECT_LABEL", "GENERAL");
        mvc.perform(get("/conversations/" + thread).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Piezas por encargo en sandbox")));
        decide(listing, "RECALL", "GENERAL");
        mvc.perform(get("/conversations/" + thread).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("El texto original no está disponible"))).andExpect(content().string(not(containsString("Piezas por encargo en sandbox"))));
        mvc.perform(get("/conversations").session(customer.session())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Impresión 3D sintética"))));
        clock.advance(61); operatorProof(); var controller = author(false, 30); var child = author(false, 14); guardian(controller, child); var parent = offer(provider);
        UUID managedThread = respond(controller, child.id(), parent); UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, managedThread); var quote = quote(provider, job);
        mvc.perform(accept(controller, child.id(), quote)).andExpect(status().is3xxRedirection()); mvc.perform(accept(provider, provider.id(), quote)).andExpect(status().is3xxRedirection());
        decide(parent, "CORRECT_LABEL", "ADULT");
        mvc.perform(get("/conversations/" + managedThread).session(controller.session()).param("subject", child.id().toString())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Piezas por encargo en sandbox"))));
        mvc.perform(get("/jobs/" + job + "/support").session(controller.session()).param("subject", child.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("ARS 123.45"))).andExpect(content().string(not(containsString("Piezas por encargo en sandbox"))));
        mvc.perform(get("/conversations/" + managedThread).session(child.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
    }

    @Test void shouldKeepPublicDraftRecoveryPrivateAndEditReportRecallExactRevisions() throws Exception {
        var provider = author(true, 30); var customer = author(false, 30); var observer = author(false, 30); var listing = offer(provider);
        String location = mvc.perform(post("/conversations/discussion").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("listing", listing.resourceId().toString()).param("subject", customer.id().toString()).param("kind", "QUESTION").param("body", "Pregunta pública sintética original").param("label", "GENERAL")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID id = UUID.fromString(location.substring("/conversations/authored/".length(), location.indexOf('?'))); var original = authored("conversations_authored", id, SurfaceKind.QUESTION);
        mvc.perform(get(location).session(customer.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Pregunta pública sintética original")));
        mvc.perform(get(location).session(observer.session())).andExpect(status().isForbidden());
        mvc.perform(get("/listings/" + listing.resourceId()).session(observer.session())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Pregunta pública sintética original")))); approve(original);
        long version = jdbc.queryForObject("select version from conversations_authored where id=?", Long.class, id);
        mvc.perform(post("/conversations/discussion").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("listing", listing.resourceId().toString()).param("subject", customer.id().toString()).param("resource", id.toString()).param("version", Long.toString(version)).param("kind", "QUESTION").param("body", "Pregunta pública sintética corregida").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var edited = authored("conversations_authored", id, SurfaceKind.QUESTION); assertThat(edited.revisionId()).isNotEqualTo(original.revisionId());
        mvc.perform(get("/listings/" + listing.resourceId()).session(observer.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Pregunta pública sintética original"))).andExpect(content().string(not(containsString("Pregunta pública sintética corregida"))));
        decide(edited, "REJECT", "GENERAL");
        mvc.perform(get(location).session(customer.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Apelar esta revisión exacta")));
        mvc.perform(post("/conversations/cases").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("kind", "QUESTION").param("resource", id.toString()).param("revision", edited.revisionId().toString()).param("caseKind", "APPEAL").param("reason", "FALSE_POSITIVE")).andExpect(status().is3xxRedirection());
        approveAppeal(edited); mvc.perform(get("/listings/" + listing.resourceId()).session(observer.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Pregunta pública sintética corregida")));
        mvc.perform(post("/conversations/cases").session(observer.session()).param("command", UUID.randomUUID().toString()).param("kind", "QUESTION").param("resource", id.toString()).param("revision", edited.revisionId().toString()).param("caseKind", "REPORT").param("reason", "SAFETY")).andExpect(status().isForbidden());
        mvc.perform(post("/conversations/cases").session(observer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("kind", "QUESTION").param("resource", id.toString()).param("revision", edited.revisionId().toString()).param("caseKind", "REPORT").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
        decide(edited, "RECALL", "GENERAL");
        mvc.perform(get("/listings/" + listing.resourceId()).session(observer.session())).andExpect(status().isOk()).andExpect(content().string(not(containsString("Pregunta pública sintética corregida"))));
        decide(listing, "RECALL", "GENERAL");
        mvc.perform(get(location).session(customer.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Pregunta pública sintética corregida")));
    }
    void approveAppeal(ContentReference reference) throws Exception {
        operatorProof(); UUID appeal = jdbc.queryForObject("select c.id from moderation_case c join moderation_snapshot p on p.id=c.submission_id where p.revision_id=? and c.kind='APPEAL' and c.state='OPEN'", UUID.class, reference.revisionId());
        for (int index = 0; index < 2; index++) { mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command", UUID.randomUUID().toString()).param("caseId", appeal.toString()).param("version", Integer.toString(index)).param("action", index == 0 ? "TAKE" : "APPROVE").param("label", "GENERAL").param("reason", "FALSE_POSITIVE")).andExpect(status().is3xxRedirection()); }
        for (int index = 0; index < 10; index++) { if (!worker.processOne()) { break; } }
    }

    @Test void shouldCompleteActualFundedJobAndCorrectRejectedUniqueReviewThroughSessionControls() throws Exception {
        var provider = author(true, 30); var customer = author(false, 30); var listing = offer(provider); UUID thread = respond(customer, customer.id(), listing); UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread); var quote = quote(provider, job);
        mvc.perform(accept(customer, customer.id(), quote)).andExpect(status().is3xxRedirection()); mvc.perform(accept(provider, provider.id(), quote)).andExpect(status().is3xxRedirection()); fundingFacts.confirm(jobs.discover(job));
        mvc.perform(post("/jobs/start").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString()).param("subject", provider.id().toString()).param("version", Long.toString(jobs.discover(job).version()))).andExpect(status().is3xxRedirection());
        UUID delivery = saveJob(provider, job, "FINAL_DELIVERY", "Entrega final exacta por sesión", null, Optional.empty()); var finalRef = authored("jobs_authored", delivery, SurfaceKind.JOB_FEEDBACK); submitJob(provider, job, finalRef); approve(finalRef);
        UUID editedDelivery = saveJob(provider, job, "FINAL_DELIVERY", "Entrega final corregida por sesión", null, Optional.of(delivery));
        assertThat(editedDelivery).isEqualTo(delivery);
        var finalCorrection = authored("jobs_authored", delivery, SurfaceKind.JOB_FEEDBACK);
        assertThat(jobs.discover(job).finalDelivery()).contains(finalRef);
        mvc.perform(get("/jobs/" + job).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Entrega final exacta por sesión"))).andExpect(content().string(not(containsString("Entrega final corregida por sesión"))));
        submitJob(provider, job, finalCorrection); approve(finalCorrection);
        assertThat(jobs.discover(job).finalDelivery()).contains(finalCorrection);
        mvc.perform(post("/jobs/accept-final").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString()).param("subject", customer.id().toString()).param("version", Long.toString(jobs.discover(job).version())).param("resource", delivery.toString()).param("revision", finalRef.revisionId().toString())).andExpect(status().is4xxClientError());
        mvc.perform(post("/jobs/accept-final").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString()).param("subject", customer.id().toString()).param("version", Long.toString(jobs.discover(job).version())).param("resource", delivery.toString()).param("revision", finalCorrection.revisionId().toString())).andExpect(status().is3xxRedirection());
        assertThat(jobs.discover(job).state()).isEqualTo(ar.changeo.jobs.api.JobSnapshot.State.COMPLETED);
        clock.advance(61); operatorProof(); UUID review = saveJob(customer, job, "REVIEW", "Reseña original rechazada", 3, Optional.empty()); var rejected = authored("jobs_authored", review, SurfaceKind.COMPLETED_JOB_REVIEW); submitJob(customer, job, rejected); decide(rejected, "REJECT", "GENERAL");
        mvc.perform(get("/jobs/" + job).session(customer.session()).param("subject", customer.id().toString()).param("content", review.toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Abrir mi reseña existente"))).andExpect(content().string(containsString("Editar evidencia: crea una revisión nueva")));
        mvc.perform(post("/conversations/cases").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("kind", "COMPLETED_JOB_REVIEW").param("resource", review.toString()).param("revision", rejected.revisionId().toString()).param("caseKind", "APPEAL").param("reason", "FALSE_POSITIVE")).andExpect(status().is3xxRedirection());
        UUID same = saveJob(customer, job, "REVIEW", "Reseña corregida del trabajo completado", 5, Optional.of(review)); assertThat(same).isEqualTo(review); var corrected = authored("jobs_authored", review, SurfaceKind.COMPLETED_JOB_REVIEW); submitJob(customer, job, corrected); approve(corrected);
        mvc.perform(get("/jobs/" + job).session(provider.session()).param("subject", provider.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Reseña corregida del trabajo completado"))).andExpect(content().string(containsString("Reportar evidencia aprobada")));
        assertThat(jdbc.queryForObject("select count(*) from jobs_authored where job_id=? and kind='REVIEW' and subject_id=?", Integer.class, job, customer.id())).isEqualTo(1);
    }
    UUID saveJob(AccountFixture author, UUID job, String kind, String body, Integer score, Optional<UUID> resource) throws Exception {
        var request = multipart("/jobs/content").session(author.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString()).param("subject", author.id().toString()).param("kind", kind).param("body", body).param("label", "GENERAL");
        if (resource.isPresent()) { request.param("resource", resource.orElseThrow().toString()).param("version", Long.toString(jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, resource.orElseThrow()))); }
        if (score != null) { request.param("score", score.toString()); }
        String location = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl(); return UUID.fromString(location.substring(location.indexOf("&content=") + 9));
    }
    void submitJob(AccountFixture author, UUID job, ContentReference reference) throws Exception {
        mvc.perform(post("/jobs/submit").session(author.session()).with(csrf()).param("job", job.toString()).param("subject", author.id().toString()).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
    }
    @Test void shouldRecoverExactFailedQuoteAndJobRevisionThroughPrivateSessionControls() throws Exception {
        var provider = author(true, 30); var customer = author(false, 30); var stranger = author(false, 30);
        var listing = offer(provider); UUID thread = respond(customer, customer.id(), listing);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        ar.changeo.moderation.ModerationService metadataTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(moderationMetadata);
        doThrow(new IllegalStateException("Synthetic listing metadata outage")).when(metadataTarget).approvalsForUpdate(Set.of(listing));
        ContentReference quote;
        try {
            quote = quote(provider, job, false);
            assertThat(worker.processOne()).isTrue();
            mvc.perform(get("/agreements/new").session(provider.session()).param("job", job.toString()).param("subject", provider.id().toString())
                    .param("resource", quote.resourceId().toString()).param("revision", quote.revisionId().toString())).andExpect(status().isOk());
            long version = jdbc.queryForObject("select version from agreements_quote where id=?", Long.class, quote.resourceId());
            mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString())
                    .param("subject", provider.id().toString()).param("kind", "INITIAL").param("scope", "Alcance propio corregido sin metadatos del aviso").param("serviceAmount", "20.00")
                    .param("deliveryAmount", "0.00").param("deliverables", "Entrega sintética").param("resource", quote.resourceId().toString()).param("revision", quote.revisionId().toString())
                    .param("version", Long.toString(version))).andExpect(status().isOk());
            var composition = (AgreementController.Composition) provider.session().getAttribute("agreementComposition");
            mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString()).param("arrangement", "REMOTE")
                    .param("arrangementDescription", "Coordinación remota").param("completionDeadline", "2026-10-07T12:00").param("feedbackRounds", "0")
                    .param("completionEvidence", "TEXT").param("evidenceDescription", "Descripción de la entrega").param("reviewWindowMinutes", "60").param("label", "GENERAL"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/conversations/" + thread + "?subject=" + provider.id() + "&quote=" + quote.resourceId()));
            quote = authored("agreements_quote", quote.resourceId(), SurfaceKind.QUOTE_DESCRIPTION);
            mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", quote.resourceId().toString()).param("revision", quote.revisionId().toString())
                    .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
            assertThat(jdbc.queryForObject("select count(*) from agreements_revision where quote_id=?", Integer.class, quote.resourceId())).isEqualTo(2);
        } finally { reset(metadataTarget); }
        mvc.perform(get("/agreements/new").session(stranger.session()).param("job", job.toString()).param("subject", provider.id().toString())).andExpect(status().is4xxClientError());
        failReview(quote);
        mvc.perform(get("/conversations/" + thread).session(provider.session()).param("subject", provider.id().toString()).param("quote", quote.resourceId().toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Reintentar presupuesto exacto")));
        long generation = generation(quote);
        var quoteRetry = post("/agreements/retry").session(provider.session()).param("command", UUID.randomUUID().toString()).param("resource", quote.resourceId().toString())
                .param("revision", quote.revisionId().toString()).param("thread", thread.toString()).param("subject", provider.id().toString()).param("generation", Long.toString(generation));
        mvc.perform(quoteRetry).andExpect(status().isForbidden());
        doThrow(new IllegalStateException("Synthetic listing metadata outage")).when(metadataTarget).approvalsForUpdate(Set.of(listing));
        try {
            mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", quote.resourceId().toString())
                    .param("revision", quote.revisionId().toString()).param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
            mvc.perform(quoteRetry.with(csrf())).andExpect(status().is3xxRedirection());
        } finally { reset(metadataTarget); }
        assertThat(generation(quote)).isEqualTo(generation + 1);
        UUID wrongThread = respond(stranger, stranger.id(), listing);
        mvc.perform(post("/agreements/retry").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", quote.resourceId().toString())
                .param("revision", quote.revisionId().toString()).param("thread", wrongThread.toString()).param("subject", provider.id().toString()).param("generation", Long.toString(generation + 1))).andExpect(status().is4xxClientError());
        mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", quote.resourceId().toString()).param("revision", UUID.randomUUID().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is4xxClientError());
        var child = author(false, 17); guardian(stranger, child);
        mvc.perform(post("/agreements/submit").session(stranger.session()).with(csrf()).param("resource", quote.resourceId().toString()).param("revision", quote.revisionId().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is4xxClientError());
        doThrow(new IllegalStateException("Synthetic own quote metadata outage")).when(metadataTarget).findApprovalForUpdate(quote);
        try {
            mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", quote.resourceId().toString()).param("revision", quote.revisionId().toString())
                    .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is4xxClientError());
        } finally { reset(metadataTarget); }
        assertThat(generation(quote)).isEqualTo(generation + 1);
        mvc.perform(post("/agreements/retry").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("resource", quote.resourceId().toString())
                .param("revision", quote.revisionId().toString()).param("thread", thread.toString()).param("subject", provider.id().toString()).param("generation", Long.toString(generation))).andExpect(status().is4xxClientError());
        approve(quote); mvc.perform(accept(customer, customer.id(), quote)).andExpect(status().is3xxRedirection()); mvc.perform(accept(provider, provider.id(), quote)).andExpect(status().is3xxRedirection());
        clock.advance(61); UUID content = saveJob(provider, job, "SUPPORT_NOTE", "Explicación propia con falla técnica", null, Optional.empty());
        var note = authored("jobs_authored", content, SurfaceKind.JOB_FEEDBACK); submitJob(provider, job, note); failReview(note);
        String own = "/jobs/" + job + "/authored?subject=" + provider.id() + "&content=" + content;
        mvc.perform(get(own).session(provider.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Reintentar evidencia exacta")));
        mvc.perform(get(own).session(stranger.session())).andExpect(status().is4xxClientError()).andExpect(content().string(not(containsString("Explicación propia con falla técnica"))));
        long jobGeneration = generation(note);
        mvc.perform(post("/jobs/retry").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString()).param("subject", provider.id().toString())
                .param("kind", "JOB_FEEDBACK").param("resource", content.toString()).param("revision", note.revisionId().toString()).param("generation", Long.toString(jobGeneration))).andExpect(status().is3xxRedirection());
        assertThat(generation(note)).isEqualTo(jobGeneration + 1); approve(note);
        mvc.perform(get("/jobs/" + job).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(containsString("Explicación propia con falla técnica")));
        decide(quote, "RECALL", "GENERAL");
        mvc.perform(get(own).session(provider.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Explicación propia con falla técnica")));
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?", Integer.class, note.revisionId())).isEqualTo(1);
    }
    long generation(ContentReference reference) {
        return jdbc.queryForObject("select s.generation from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", Long.class, reference.revisionId());
    }
    void failReview(ContentReference reference) {
        doAnswer(invocation -> {
            ReviewInput input = invocation.getArgument(0);
            if (input.reference().equals(reference)) { throw new IllegalStateException("Synthetic exact review outage"); }
            return invocation.callRealMethod();
        }).when(reviewEngine).review(any());
        try {
            for (int attempt = 0; attempt < 3; attempt++) { assertThat(worker.processOne()).isTrue(); clock.advance(16); }
        } finally { reset(reviewEngine); }
        assertThat(jdbc.queryForObject("select s.state from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, reference.revisionId())).isEqualTo("FAILED");
    }
}
