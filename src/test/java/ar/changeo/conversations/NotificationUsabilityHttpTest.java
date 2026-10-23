package ar.changeo.conversations;

import ar.changeo.ChangeoApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {ChangeoApplication.class, ConversationHttpTest.Configuration.class}, properties = {
        "spring.flyway.schemas=notification_usability_http_test", "spring.flyway.default-schema=notification_usability_http_test",
        "spring.jpa.properties.hibernate.default_schema=notification_usability_http_test", "spring.datasource.hikari.schema=notification_usability_http_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.notification-worker=false", "changeo.synthetic-payments=false", "changeo.synthetic-subscriptions=false",
        "changeo.mfa-key=.runtime/conversations-http-mfa.key", "changeo.files-root=.runtime/conversations-http-artifacts"})
class NotificationUsabilityHttpTest extends ConversationHttpTest {
    @MockitoSpyBean MessageReader noticeReader;
    @Autowired ar.changeo.notifications.NotificationService notices;

    @org.junit.jupiter.api.BeforeEach
    void clearOwnNotificationFixtures() {
        jdbc.update("delete from notifications_local_delivery");
        jdbc.update("delete from notifications_intent");
    }

    @Test
    void shouldReturn503WhenCurrentNoticeOwnerHasRealDatabaseFailure() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var reference = message(provider, thread, provider.id(), "Aviso privado sintético");
        submit(provider, thread, provider.id(), reference); approve(reference);
        MessageReader target = AopTestUtils.getUltimateTargetObject(noticeReader);
        doAnswer(invocation -> { jdbc.execute("select 1/0"); return false; }).when(target).available(eq(reference), any());
        try {
            var response = mvc.perform(get("/notifications").session(customer.session())).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("division by zero", "select 1/0", "Aviso privado sintético");
        } finally { reset(target); }
    }
    @Test
    void shouldNamePendingRetryAfterCurrentBlockIsRemoved() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var reference = message(provider, thread, provider.id(), "Aviso privado para reintento");
        submit(provider, thread, provider.id(), reference); approve(reference);
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString())
                .param("subject", customer.id().toString()).param("blocked", "true")).andExpect(status().is3xxRedirection());
        for (int index = 0; index < 10; index++) { if (!notices.runOnce()) { break; } }
        assertThat(jdbc.queryForList("select state from notifications_intent where revision_id=?", String.class, reference.revisionId()))
                .containsExactly("RETRY_WAIT", "RETRY_WAIT");
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString())
                .param("subject", customer.id().toString()).param("blocked", "false")).andExpect(status().is3xxRedirection());
        var response = mvc.perform(get("/notifications").session(customer.session())).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsString()).containsIgnoringCase("reintento").doesNotContain("Estado pendiente de confirmar", "Aviso privado para reintento");
    }

    @Test
    void shouldRenderOnlyReferenceNavigationAndRecheckTheDestination() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var stranger = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var reference = message(provider, thread, provider.id(), "Cuerpo privado ausente de avisos");
        submit(provider, thread, provider.id(), reference); approve(reference);
        var response = mvc.perform(get("/notifications").session(customer.session())).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentAsString()).contains("Consultar contenido del aviso", "/conversations/origin?kind=PRIVATE_MESSAGE", reference.revisionId().toString())
                .doesNotContain("Cuerpo privado ausente de avisos", thread.toString(), customer.id().toString(), provider.id().toString());
        var strangerPage = mvc.perform(get("/notifications").session(stranger.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(strangerPage).doesNotContain(reference.resourceId().toString(), reference.revisionId().toString(), "Consultar contenido del aviso");
        mvc.perform(get("/conversations/origin").session(customer.session()).param("kind", reference.ownerKind().name())
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().isOk());
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString())
                .param("subject", customer.id().toString()).param("blocked", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/conversations/origin").session(customer.session()).param("kind", reference.ownerKind().name())
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString())).andExpect(status().isForbidden());
        var blocked = mvc.perform(get("/notifications").session(customer.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(blocked).contains("No hay avisos actualmente disponibles en esta página.").doesNotContain(reference.resourceId().toString(), "Consultar contenido del aviso");
    }

    @Test
    void shouldRejectForeignMissingAndMalformedCursorWithoutContextDisclosure() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var reference = message(provider, thread, provider.id(), "Contenido privado de cursor");
        submit(provider, thread, provider.id(), reference); approve(reference);
        UUID foreign = jdbc.queryForObject("select id from notifications_intent where recipient_id=? and revision_id=?", UUID.class, provider.id(), reference.revisionId());
        for (String cursor : java.util.List.of(foreign.toString(), UUID.randomUUID().toString(), "invalid")) {
            var html = mvc.perform(get("/notifications").session(customer.session()).param("after", cursor))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("Revisá los campos y el estado actual.")
                    .doesNotContain(foreign.toString(), thread.toString(), customer.id().toString(), provider.id().toString(), "Contenido privado de cursor");
        }
    }
}
