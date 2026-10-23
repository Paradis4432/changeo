package ar.changeo.conversations;

import ar.changeo.moderation.api.ContentReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MessageActivationHttpTest extends ConversationHttpTest {
    @Test void shouldSettleBlockedCorrectionBeforeRestoringExactRetainedFiles() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var stranger = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var message = message(customer, thread, customer.id(), "Mensaje conservado y moderado");
        submit(customer, thread, customer.id(), message); approve(message);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var terms = quote(provider, job);
        mvc.perform(accept(customer, customer.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString())
                .param("subject", customer.id().toString()).param("blocked", "true")).andExpect(status().is3xxRedirection());
        long version = jdbc.queryForObject("select version from conversations_authored where id=?", Long.class, message.resourceId());
        decide(message, "CORRECT_LABEL", "SENSITIVE"); settle(message);
        mvc.perform(file(message).session(customer.session())).andExpect(status().isOk());
        accounts.preferences(customer.id(), false, false);
        mvc.perform(file(message).session(customer.session())).andExpect(status().isForbidden());
        accounts.preferences(customer.id(), true, false);
        mvc.perform(file(message).session(customer.session())).andExpect(status().isOk());
        mvc.perform(file(message).session(stranger.session())).andExpect(status().isForbidden());
        mvc.perform(file(message)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/jobs/" + job + "/support").session(customer.session()).param("subject", provider.id().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread + "/updates").session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isForbidden());
        decide(message, "CORRECT_LABEL", "ADULT"); settle(message);
        mvc.perform(file(message).session(customer.session())).andExpect(status().isForbidden());
        accounts.preferences(customer.id(), true, true);
        mvc.perform(file(message).session(customer.session())).andExpect(status().isOk());
        decide(message, "CORRECT_LABEL", "GENERAL"); settle(message);
        assertThat(jdbc.queryForObject("select version from conversations_authored where id=?", Long.class, message.resourceId())).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from conversations_event where resource_id=? and kind='DELIVERED'", Long.class, message.resourceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where resource_id=?", Long.class, message.resourceId())).isEqualTo(2);
        decide(message, "RECALL", "GENERAL");
        mvc.perform(file(message).session(customer.session())).andExpect(status().isForbidden());
    }

    void settle(ContentReference reference) {
        for (int attempt = 0; attempt < 30; attempt++) { if (!worker.processOne()) { break; } }
        assertThat(jdbc.queryForList("select t.state from moderation_task t join moderation_snapshot p on p.id=t.submission_id where p.revision_id=?", String.class, reference.revisionId())).containsOnly("COMPLETE");
        assertThat(jdbc.queryForObject("select s.state from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, reference.revisionId())).isEqualTo("APPROVED");
    }
}
