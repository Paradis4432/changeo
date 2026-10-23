package ar.changeo.conversations;

import ar.changeo.moderation.api.ContentReference;
import ar.changeo.identity.api.ActorId;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.moderation.ModerationService;
import ar.changeo.moderation.api.ModerationAccess;
import ar.changeo.moderation.api.SurfaceKind;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import ar.changeo.ChangeoApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {ChangeoApplication.class, ConversationHttpTest.Configuration.class}, properties = {
        "spring.flyway.schemas=case_origin_http_test", "spring.flyway.default-schema=case_origin_http_test",
        "spring.jpa.properties.hibernate.default_schema=case_origin_http_test", "spring.datasource.hikari.schema=case_origin_http_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.mfa-key=.runtime/conversations-http-mfa.key", "changeo.files-root=.runtime/conversations-http-artifacts"})
class CaseOriginHttpTest extends ConversationHttpTest {
    @Test
    void shouldNavigateOwnPublicReportToExactGuardedRevision() throws Exception {
        var provider = author(true, 30);
        var reporter = author(false, 30);
        var reference = offer(provider);
        mvc.perform(post("/conversations/cases").session(reporter.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())
                .param("caseKind", "REPORT").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
        var cases = mvc.perform(get("/moderation/cases").session(reporter.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(cases).contains("Consultar revisión del caso", "/marketplace/origin?").doesNotContain(provider.id().toString(), reporter.id().toString());
        var origin = mvc.perform(get("/marketplace/origin").session(reporter.session()).param("kind", reference.ownerKind().name())
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(origin.getContentAsString()).contains(reference.revisionId().toString(), "Impresión 3D sintética").doesNotContain("/moderation/evidence");
        assertThat(origin.getHeader("Cache-Control")).contains("no-store");
    }
    @MockitoSpyBean MessageOwner originMessageOwner;

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_MESSAGE", "PROPOSAL", "QUOTE_DESCRIPTION", "SUPPORT_NOTE"})
    void cnPrivateOriginsRequireActualSideWithoutCaseOrParentDisclosure(String kind) throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var stranger = author(false, 30);
        var offer = offer(provider);
        UUID thread = respond(customer, customer.id(), offer);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var quote = quote(provider, job);
        mvc.perform(accept(customer, customer.id(), quote)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), quote)).andExpect(status().is3xxRedirection());
        ContentReference reference = quote;
        String route = "/agreements/origin";
        if (kind.equals("SUPPORT_NOTE")) {
            mvc.perform(multipart("/jobs/content").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", job.toString())
                    .param("subject", customer.id().toString()).param("kind", kind).param("body", "Ayuda exacta privada").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
            reference = jdbc.queryForObject("select id,current_revision from jobs_authored where job_id=?", (row, index) ->
                    new ContentReference(SurfaceKind.JOB_FEEDBACK, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), job);
            mvc.perform(post("/jobs/submit").session(customer.session()).with(csrf()).param("job", job.toString()).param("subject", customer.id().toString())
                    .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
            approve(reference); route = "/jobs/origin";
        } else if (!kind.equals("QUOTE_DESCRIPTION")) {
            mvc.perform(multipart("/conversations/messages").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                    .param("thread", thread.toString()).param("subject", provider.id().toString()).param("kind", kind)
                    .param("body", "Texto privado <script>exacto</script>").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
            reference = jdbc.queryForObject("select id,current_revision from conversations_authored where thread_id=?", (row, index) ->
                    new ContentReference(SurfaceKind.PRIVATE_MESSAGE, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), thread);
            submit(provider, thread, provider.id(), reference); approve(reference); route = "/conversations/origin";
        }
        var before = originEffects();
        for (var participant : List.of(customer, provider)) {
            var html = mvc.perform(origin(route, reference).session(participant.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains(reference.revisionId().toString(), "Volver a mis casos").doesNotContain(job.toString(), thread.toString(), customer.id().toString(), provider.id().toString(),
                    "/moderation/evidence", "<script>", "action=\"/jobs/", "action=\"/agreements/", "action=\"/conversations/");
        }
        var denied = mvc.perform(origin(route, reference).session(stranger.session())).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(denied).doesNotContain(job.toString(), thread.toString(), customer.id().toString(), provider.id().toString(), "Texto privado", "Ayuda exacta privada", "Pieza y revisión final");
        assertThat(originEffects()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLIC", "PRIVATE"})
    void cnWorkbenchOriginRendersExactBodyWithoutOldMutationOrFileControls(String audience) throws Exception {
        var author = author(false, 30);
        var viewer = author(false, 30);
        var saved = mvc.perform(multipart("/moderation/drafts").session(author.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("text", "Taller exacto <script>texto</script>").param("label", "GENERAL").param("audience", audience)
                .param("participant", audience.equals("PRIVATE") ? viewer.id().toString() : "")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource = UUID.fromString(saved.substring(saved.lastIndexOf('/') + 1));
        UUID revision = jdbc.queryForObject("select current_revision from workbench_resource where id=?", UUID.class, resource);
        var reference = new ContentReference(audience.equals("PUBLIC") ? SurfaceKind.REQUEST_BODY : SurfaceKind.PRIVATE_MESSAGE, resource, revision);
        mvc.perform(post("/moderation/submit").session(author.session()).with(csrf()).param("resource", resource.toString()).param("revision", revision.toString())).andExpect(status().is3xxRedirection());
        approve(reference);
        var html = mvc.perform(origin("/moderation/origin", reference).session(author.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Taller exacto &lt;script&gt;texto&lt;/script&gt;", revision.toString()).doesNotContain("<script>", "Enviar a revisión", "Abrir apelación", "/content/files/");
    }

    @Test
    void cnOriginPreservesRealSql503AndUnknownLabelFailureWithoutDisclosureOrEffects() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var reference = message(provider, thread, provider.id(), "Texto exacto sintético");
        submit(provider, thread, provider.id(), reference); approve(reference);
        var before = originEffects();
        MessageOwner target = AopTestUtils.getUltimateTargetObject(originMessageOwner);
        doAnswer(invocation -> { jdbc.execute("select 1/0"); return null; }).when(target).lockRevision(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(reference));
        try {
            var error = mvc.perform(origin("/conversations/origin", reference).session(provider.session())).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
            assertThat(error.getContentAsString()).doesNotContain("division by zero", "select 1/0", thread.toString(), provider.id().toString());
        } finally { reset(target); }
        assertThat(originEffects()).isEqualTo(before);
        ModerationService metadata = AopTestUtils.getUltimateTargetObject(moderationMetadata);
        doAnswer(invocation -> {
            Optional<ModerationAccess.Approval> found = (Optional<ModerationAccess.Approval>) invocation.callRealMethod();
            return found.map(value -> new ModerationAccess.Approval(value.reference(), value.digest(), value.state(), "UNKNOWN", value.reason()));
        }).when(metadata).findApprovalForUpdate(reference);
        try {
            var unknown = mvc.perform(origin("/conversations/origin", reference).session(provider.session())).andExpect(status().isForbidden()).andReturn().getResponse();
            assertThat(unknown.getContentAsString()).doesNotContain("Texto exacto sintético", thread.toString(), provider.id().toString());
        } finally { reset(metadata); }
        assertThat(originEffects()).isEqualTo(before);
    }

    MockHttpServletRequestBuilder origin(String route, ContentReference reference) {
        return get(route).param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString());
    }

    Map<String, String> originEffects() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename<>'flyway_schema_history' order by tablename", String.class)) {
            if (!table.matches("[a-z_]+")) { throw new IllegalStateException("Unexpected test table"); }
            result.put(table, jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(t)::text,',' order by row_to_json(t)::text),'')) from " + table + " t", String.class));
        }
        return Map.copyOf(result);
    }

}
