package ar.changeo.conversations;

import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationRefreshHttpTest extends ConversationHttpTest {
    @Test void shouldRefreshOnlyCurrentApprovedEscapedObservationWithGuardedFilesAndReport() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var listing = offer(provider);
        UUID thread = respond(customer, customer.id(), listing);
        var message = refreshMessage(provider, thread, "Privado <script>sin ejecutar</script>");
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Privado &lt;script&gt;"))));
        mvc.perform(file(message).session(customer.session())).andExpect(status().isForbidden());
        submit(provider, thread, provider.id(), message);
        approve(message);
        var result = mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(content().string(containsString("Privado &lt;script&gt;sin ejecutar&lt;/script&gt;")))
                .andExpect(content().string(containsString("Impresión 3D sintética"))).andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("id=\"conversation-updates\"", "id=\"delivered-messages\"", "Descargar archivo aprobado", "Reportar esta revisión")
                .doesNotContain("<script>", "<header", "name=\"body\"", "name=\"uploads\"", "name=\"password\"");
        mvc.perform(file(message).session(customer.session())).andExpect(status().isOk())
                .andExpect(content().bytes("synthetic:clear".getBytes(StandardCharsets.UTF_8)));
        var token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).isTrue();
        var report = post("/conversations/cases").session(customer.session()).param("command", UUID.randomUUID().toString())
                .param("kind", message.ownerKind().name()).param("resource", message.resourceId().toString())
                .param("revision", message.revisionId().toString()).param("caseKind", "REPORT").param("reason", "SAFETY");
        mvc.perform(report).andExpect(status().isForbidden());
        mvc.perform(report.param("_csrf", token.group(1))).andExpect(status().is3xxRedirection());
    }

    @Test void shouldRefreshDenyStrangerSpoofBlockedLeftAndUnauthenticatedRequests() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var stranger = author(false, 30);
        var listing = offer(provider);
        UUID thread = respond(customer, customer.id(), listing);
        mvc.perform(refresh(stranger, thread, customer.id())).andExpect(status().isForbidden());
        mvc.perform(refresh(customer, thread, provider.id())).andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread + "/updates").param("subject", customer.id().toString())
                .header("X-Requested-With", "XMLHttpRequest")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString())
                .param("subject", customer.id().toString()).param("blocked", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(refresh(provider, thread, provider.id())).andExpect(status().isForbidden());
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString())
                .param("subject", customer.id().toString()).param("blocked", "false")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/conversations/leave").session(customer.session()).with(csrf()).param("thread", thread.toString())
                .param("subject", customer.id().toString())).andExpect(status().is3xxRedirection());
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isForbidden());
    }

    @Test void shouldRefreshSuppressCurrentLabelsPreferencesAndRecallWithoutRewritingBindings() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var listing = offer(provider);
        UUID thread = respond(customer, customer.id(), listing);
        var message = refreshMessage(provider, thread, "Mensaje con audiencia actual");
        submit(provider, thread, provider.id(), message);
        approve(message);
        accounts.preferences(customer.id(), true, false);
        decide(message, "CORRECT_LABEL", "ADULT");
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Mensaje con audiencia actual"))));
        decide(listing, "CORRECT_LABEL", "ADULT");
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Impresión 3D sintética"))))
                .andExpect(content().string(containsString("El texto original no está disponible")));
        decide(listing, "CORRECT_LABEL", "GENERAL");
        decide(message, "CORRECT_LABEL", "GENERAL");
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mensaje con audiencia actual")));
        decide(message, "RECALL", "GENERAL");
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Mensaje con audiencia actual"))));
        decide(listing, "RECALL", "GENERAL");
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Impresión 3D sintética"))));
        assertThat(jdbc.queryForObject("select listing_revision from conversations_thread where id=?", UUID.class, thread)).isEqualTo(listing.revisionId());
    }

    @Test void shouldRefreshDenyRevokedGuardianNormalObservationWhileKeepingSelectedHistory() throws Exception {
        var provider = author(true, 30);
        var guardian = author(false, 30);
        var child = author(false, 14);
        UUID relationship = guardian(guardian, child);
        var listing = offer(provider);
        UUID thread = respond(guardian, child.id(), listing);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var terms = quote(provider, job);
        mvc.perform(accept(guardian, child.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(refresh(guardian, thread, child.id())).andExpect(status().isOk());
        mvc.perform(post("/account/guardian/" + relationship + "/revoke").session(guardian.session()).with(csrf())
                .param("version", "1")).andExpect(status().is3xxRedirection());
        mvc.perform(refresh(guardian, thread, child.id())).andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread).session(guardian.session()).param("subject", child.id().toString()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("data-refresh-url"))));
        mvc.perform(get("/jobs/" + job + "/support").session(guardian.session()).param("subject", child.id().toString()))
                .andExpect(status().isOk());
    }

    @Test void shouldRefreshFailUnavailableOnRealOwnerMetadataFailureAndRecoverOnNextObservation() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var listing = offer(provider);
        UUID thread = respond(customer, customer.id(), listing);
        ar.changeo.moderation.ModerationService target = AopTestUtils.getUltimateTargetObject(moderationMetadata);
        doThrow(new TransientDataAccessResourceException("Synthetic metadata unavailable")).when(target).approvalsForUpdate(Set.of(listing));
        try {
            mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(not(containsString("Impresión 3D sintética"))));
        } finally {
            reset(target);
        }
        mvc.perform(refresh(customer, thread, customer.id())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Impresión 3D sintética")));
    }

    private MockHttpServletRequestBuilder refresh(AccountFixture actor, UUID thread, UUID subject) {
        return get("/conversations/" + thread + "/updates").session(actor.session()).param("subject", subject.toString())
                .header("X-Requested-With", "XMLHttpRequest");
    }

    private ContentReference refreshMessage(AccountFixture provider, UUID thread, String body) throws Exception {
        String location = mvc.perform(multipart("/conversations/messages")
                .file(new MockMultipartFile("uploads", "synthetic.txt", "text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8)))
                .session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("thread", thread.toString())
                .param("subject", provider.id().toString()).param("kind", "PRIVATE_MESSAGE").param("body", body).param("label", "GENERAL"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource = UUID.fromString(location.substring(location.indexOf("&message=") + 9));
        return authored("conversations_authored", resource, SurfaceKind.PRIVATE_MESSAGE);
    }
}
