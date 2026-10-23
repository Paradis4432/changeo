package ar.changeo.conversations;

import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.identity.api.ActorId;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

class ReportAuthorizationHttpTest extends ConversationHttpTest {
    @MockitoSpyBean ConversationAccess reportAccess;
    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_MESSAGE", "PROPOSAL", "QUOTE_DESCRIPTION", "SUPPORT_NOTE"})
    void shouldRejectUnrelatedReportThroughAdmittedSession(String kind) throws Exception {
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
        if (kind.equals("SUPPORT_NOTE")) {
            mvc.perform(multipart("/jobs/content").session(customer.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                    .param("job", job.toString()).param("subject", customer.id().toString()).param("kind", kind)
                    .param("body", "Ayuda privada sintética").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
            reference = jdbc.queryForObject("select id,current_revision from jobs_authored where job_id=?", (row, index) ->
                    new ContentReference(SurfaceKind.JOB_FEEDBACK, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), job);
            mvc.perform(post("/jobs/submit").session(customer.session()).with(csrf()).param("job", job.toString()).param("subject", customer.id().toString())
                    .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().is3xxRedirection());
            approve(reference);
        } else if (!kind.equals("QUOTE_DESCRIPTION")) {
            mvc.perform(multipart("/conversations/messages").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                    .param("thread", thread.toString()).param("subject", provider.id().toString()).param("kind", kind)
                    .param("body", "Contenido privado sintético").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
            reference = jdbc.queryForObject("select id,current_revision from conversations_authored where thread_id=?", (row, index) ->
                    new ContentReference(SurfaceKind.PRIVATE_MESSAGE, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), thread);
            submit(provider, thread, provider.id(), reference);
            approve(reference);
        }
        mvc.perform(get("/conversations/" + thread).session(stranger.session()).param("subject", stranger.id().toString())).andExpect(status().isForbidden());
        long before = jdbc.queryForObject("select count(*) from moderation_case", Long.class);
        var response = mvc.perform(post("/conversations/cases").session(stranger.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())
                .param("caseKind", "REPORT").param("reason", "SAFETY").param("note", "Informe sintético")).andReturn().getResponse();
        long after = jdbc.queryForObject("select count(*) from moderation_case", Long.class);
        System.out.println("REPORT_AUTHORIZATION_HTTP_BASELINE " + kind + " status=" + response.getStatus() + " caseDelta=" + (after - before));
        assertAll(
                () -> assertThat(response.getStatus()).isEqualTo(403),
                () -> assertThat(after).isEqualTo(before));
    }

    @Test
    void shouldRequireCsrfAndKeepPublicReportsAvailable() throws Exception {
        var provider = author(true, 30);
        var stranger = author(false, 30);
        var offer = offer(provider);
        long before = jdbc.queryForObject("select count(*) from moderation_case", Long.class);
        for (boolean authenticated : List.of(false, true)) {
            var request = post("/conversations/cases").param("command", UUID.randomUUID().toString()).param("kind", offer.ownerKind().name())
                    .param("resource", offer.resourceId().toString()).param("revision", offer.revisionId().toString()).param("caseKind", "REPORT").param("reason", "SAFETY");
            if (authenticated) { request.session(stranger.session()); }
            mvc.perform(request).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select count(*) from moderation_case", Long.class)).isEqualTo(before);
        mvc.perform(post("/conversations/cases").session(stranger.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("kind", offer.ownerKind().name()).param("resource", offer.resourceId().toString()).param("revision", offer.revisionId().toString())
                .param("caseKind", "REPORT").param("reason", "SAFETY")).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select count(*) from moderation_case", Long.class)).isEqualTo(before + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "REVOKED", "RELINKED", "RESTRICTED"})
    void raAdmittedSessionRetainsExactGuardianReportsWithoutGrantingNewComposeOrSuccessors(String change) throws Exception {
        var provider = author(true, 30);
        var controller = author(false, 30);
        var child = author(false, 14);
        var successor = author(false, 30);
        UUID relationship = guardian(controller, child);
        UUID thread = respond(controller, child.id(), offer(provider));
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var quote = quote(provider, job);
        mvc.perform(accept(controller, child.id(), quote)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), quote)).andExpect(status().is3xxRedirection());
        var message = message(provider, thread, provider.id(), "Mensaje retenido sintético");
        submit(provider, thread, provider.id(), message);
        approve(message);
        clock.advance(61);
        switch (change) {
            case "BLOCK" -> mvc.perform(post("/conversations/block").session(controller.session()).with(csrf()).param("thread", thread.toString())
                    .param("subject", child.id().toString()).param("blocked", "true")).andExpect(status().is3xxRedirection());
            case "REVOKED", "RELINKED" -> {
                mvc.perform(post("/account/guardian/" + relationship + "/revoke").session(controller.session()).with(csrf()).param("version", "1")).andExpect(status().is3xxRedirection());
                if (change.equals("RELINKED")) { guardian(successor, child); }
            }
            case "RESTRICTED" -> mvc.perform(post("/admin/accounts/" + controller.id() + "/restriction").session(operator).with(csrf()).param("active", "true")).andExpect(status().is3xxRedirection());
            default -> throw new IllegalArgumentException();
        }
        mvc.perform(multipart("/conversations/messages").session(controller.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("thread", thread.toString()).param("subject", child.id().toString()).param("kind", "PRIVATE_MESSAGE")
                .param("body", "Nueva comunicación denegada").param("label", "GENERAL")).andExpect(status().isForbidden());
        for (var reference : List.of(message, quote)) {
            clock.advance(61);
            for (var reporter : List.of(controller, provider)) {
                UUID command = UUID.randomUUID();
                mvc.perform(report(reporter, reference, command)).andExpect(status().is3xxRedirection());
                var before = effects();
                mvc.perform(report(reporter, reference, command)).andExpect(status().is3xxRedirection());
                assertThat(effects()).isEqualTo(before);
            }
            var focusedOperator = new AccountFixture(accounts.credentials(enrollment.getProperty("contact")).orElseThrow().id(), operator);
            for (var denied : List.of(child, successor, focusedOperator)) {
                var before = effects();
                mvc.perform(report(denied, reference, UUID.randomUUID())).andExpect(status().isForbidden());
                assertThat(effects()).isEqualTo(before);
            }
        }
        decide(message, "CORRECT_LABEL", "ADULT");
        var before = effects();
        mvc.perform(report(controller, message, UUID.randomUUID())).andExpect(status().isForbidden());
        assertThat(effects()).isEqualTo(before);
        mvc.perform(report(provider, message, UUID.randomUUID())).andExpect(status().is3xxRedirection());
    }

    @Test
    void raRealDatabaseFailureReturns503WithoutCreatingCaseOrExposingTechnicalText() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var reference = message(provider, thread, provider.id(), "Mensaje privado aprobado");
        submit(provider, thread, provider.id(), reference);
        approve(reference);
        var before = effects();
        ConversationAccess target = AopTestUtils.getUltimateTargetObject(reportAccess);
        doAnswer(invocation -> { jdbc.execute("select 1/0"); return null; }).when(target)
                .requireReportable(thread, new ActorId(customer.id()), "GENERAL", Optional.empty());
        try {
            var response = mvc.perform(report(customer, reference, UUID.randomUUID())).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("division by zero", "select 1/0", "org.postgresql");
        } finally { reset(target); }
        assertThat(effects()).isEqualTo(before);
    }

    MockHttpServletRequestBuilder report(AccountFixture reporter, ContentReference reference, UUID command) {
        return post("/conversations/cases").session(reporter.session()).with(csrf()).param("command", command.toString())
                .param("kind", reference.ownerKind().name()).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())
                .param("caseKind", "REPORT").param("reason", "SAFETY");
    }

    Map<String, String> effects() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename<>'flyway_schema_history' order by tablename", String.class)) {
            if (!table.matches("[a-z_]+")) { throw new IllegalStateException("Unexpected test table"); }
            result.put(table, jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(t)::text,',' order by row_to_json(t)::text),'')) from " + table + " t", String.class));
        }
        return Map.copyOf(result);
    }
}
