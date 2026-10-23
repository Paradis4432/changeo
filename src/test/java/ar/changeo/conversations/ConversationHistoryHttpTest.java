package ar.changeo.conversations;

import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationHistoryHttpTest extends ConversationHttpTest {
    @Test void shouldNavigateEveryApprovedCounterpartMessageInEventOrder() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var references = approvedMessages(provider, thread, 21);
        String recent = observation(customer, thread, null);
        assertThat(revisions(recent)).containsExactlyElementsOf(references.subList(11, 21).stream().map(ContentReference::revisionId).toList());
        String older = observation(customer, thread, history(recent));
        assertThat(revisions(older)).containsExactlyElementsOf(references.subList(1, 11).stream().map(ContentReference::revisionId).toList());
        String oldest = observation(customer, thread, history(older));
        assertThat(revisions(oldest)).containsExactly(references.getFirst().revisionId());
        assertThat(oldest).doesNotContain("id=\"history-older\"").contains("Volver a mensajes recientes");
    }

    @Test void shouldKeepExactBoundaryWindowsAndObserveLaterActivityWithoutMovingSelection() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        assertThat(revisions(observation(customer, thread, null))).isEmpty();
        assertThat(observation(customer, thread, null)).doesNotContain("id=\"history-older\"");
        var first = approvedMessages(provider, thread, 1);
        assertThat(revisions(observation(customer, thread, null))).containsExactly(first.getFirst().revisionId());
        approvedMessages(provider, thread, 9);
        assertThat(revisions(observation(customer, thread, null))).hasSize(10);
        assertThat(observation(customer, thread, null)).doesNotContain("id=\"history-older\"");
        approvedMessages(provider, thread, 1);
        String cursor = history(observation(customer, thread, null));
        var selected = revisions(observation(customer, thread, cursor));
        assertThat(selected).containsExactly(first.getFirst().revisionId());
        approvedMessages(provider, thread, 1);
        String refreshed = observation(customer, thread, cursor);
        assertThat(revisions(refreshed)).isEqualTo(selected);
        assertThat(refreshed).contains("Actividad posterior").doesNotContain("nuevo mensaje");
    }

    @Test void shouldRejectUntrustedNormalCursorsAndRepeatCurrentParticipantProtection() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var stranger = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        approvedMessages(provider, thread, 11);
        String cursor = history(observation(customer, thread, null));
        for (String invalid : List.of("", "0:0", "-1:2", "1", "1:2:3", "01:2", "9223372036854775808:9223372036854775808", "9223372036854775807:9223372036854775807", "2:1")) {
            mvc.perform(get("/conversations/" + thread + "/updates").session(customer.session()).param("subject", customer.id().toString()).param("history", invalid))
                    .andExpect(status().isForbidden()).andExpect(content().string(not(containsString("Historial aprobado"))));
        }
        UUID other = respond(stranger, stranger.id(), offer(provider));
        mvc.perform(get("/conversations/" + other + "/updates").session(stranger.session()).param("subject", stranger.id().toString()).param("history", cursor))
                .andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread + "/updates").session(stranger.session()).param("subject", customer.id().toString()).param("history", cursor))
                .andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread + "/updates").session(customer.session()).param("subject", provider.id().toString()).param("history", cursor))
                .andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread + "/updates").param("subject", customer.id().toString()).param("history", cursor))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString()).param("subject", customer.id().toString()).param("blocked", "true"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/conversations/" + thread + "/updates").session(customer.session()).param("subject", customer.id().toString()).param("history", cursor))
                .andExpect(status().isForbidden());
    }

    @Test void shouldRecheckOldPageApprovalAudienceFilesAndEmptyIntervalContinuation() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var references = approvedMessages(provider, thread, 21);
        String cursor = history(observation(customer, thread, null));
        String oldest = history(observation(customer, thread, cursor));
        for (var reference : references.subList(1, 11)) { operatorProof(); decide(reference, "RECALL", "GENERAL"); }
        String empty = observation(customer, thread, cursor);
        assertThat(revisions(empty)).isEmpty();
        assertThat(history(empty)).isEqualTo(oldest);
        assertThat(revisions(observation(customer, thread, oldest))).containsExactly(references.getFirst().revisionId());
        var first = references.getFirst();
        mvc.perform(file(first).session(customer.session())).andExpect(status().isOk());
        operatorProof(); decide(first, "CORRECT_LABEL", "SENSITIVE");
        accounts.preferences(customer.id(), false, false);
        assertThat(revisions(observation(customer, thread, oldest))).isEmpty();
        mvc.perform(file(first).session(customer.session())).andExpect(status().isForbidden());
        accounts.preferences(customer.id(), true, false);
        assertThat(revisions(observation(customer, thread, oldest))).containsExactly(first.revisionId());
        operatorProof(); decide(first, "RECALL", "GENERAL");
        assertThat(revisions(observation(customer, thread, oldest))).isEmpty();
        mvc.perform(file(first).session(customer.session())).andExpect(status().isForbidden());
    }

    @Test void shouldContinueSelectedBlockedSupportWithExactRetainedSideAndBoundCursor() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        var stranger = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var references = approvedMessages(provider, thread, 12);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var terms = quote(provider, job);
        mvc.perform(accept(customer, customer.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/conversations/block").session(customer.session()).with(csrf()).param("thread", thread.toString()).param("subject", customer.id().toString()).param("blocked", "true"))
                .andExpect(status().is3xxRedirection());
        String route = "/jobs/" + job + "/support";
        String first = mvc.perform(get(route).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(revisions(first)).hasSize(10);
        var match = Pattern.compile("id=\"retained-next\"[^>]*href=\"[^\"]*messageAfter=([^&\"]+)").matcher(first);
        assertThat(match.find()).isTrue();
        String cursor = match.group(1);
        String second = mvc.perform(get(route).session(customer.session()).param("subject", customer.id().toString()).param("messageAfter", cursor))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn().getResponse().getContentAsString();
        assertThat(revisions(second)).hasSize(2);
        var all = new ArrayList<>(revisions(first)); all.addAll(revisions(second));
        assertThat(all).doesNotHaveDuplicates().containsExactlyElementsOf(references.stream().sorted(java.util.Comparator.comparing(value -> value.resourceId().toString())).map(ContentReference::revisionId).toList());
        assertThat(second).doesNotContain("id=\"retained-next\"", "data-refresh-url").contains("Volver al comienzo del historial");
        mvc.perform(get(route).session(stranger.session()).param("subject", customer.id().toString()).param("messageAfter", cursor)).andExpect(status().isForbidden());
        mvc.perform(get(route).session(customer.session()).param("subject", provider.id().toString()).param("messageAfter", cursor)).andExpect(status().isForbidden());
        mvc.perform(get(route).session(customer.session()).param("subject", customer.id().toString()).param("messageAfter", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        UUID other = respond(stranger, stranger.id(), offer(provider));
        var foreign = approvedMessages(provider, other, 1).getFirst();
        mvc.perform(get(route).session(customer.session()).param("subject", customer.id().toString()).param("messageAfter", foreign.resourceId().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread).session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isOk()).andExpect(content().string(not(containsString("data-refresh-url"))));
        mvc.perform(get("/conversations/" + thread + "/poll").session(customer.session()).param("subject", customer.id().toString())).andExpect(status().isForbidden());
    }

    @Test void shouldRetainPagedOriginalGuardianHistoryAfterRevocationAndRelinkingWithoutNormalPolling() throws Exception {
        var provider = author(true, 30);
        var guardian = author(false, 30);
        var child = author(false, 14);
        var replacement = author(false, 30);
        UUID link = guardian(guardian, child);
        UUID thread = respond(guardian, child.id(), offer(provider));
        approvedMessages(provider, thread, 12);
        UUID job = jdbc.queryForObject("select job_id from conversations_thread where id=?", UUID.class, thread);
        var terms = quote(provider, job);
        mvc.perform(accept(guardian, child.id(), terms)).andExpect(status().is3xxRedirection());
        mvc.perform(accept(provider, provider.id(), terms)).andExpect(status().is3xxRedirection());
        String route = "/jobs/" + job + "/support";
        String first = mvc.perform(get(route).session(guardian.session()).param("subject", child.id().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var match = Pattern.compile("id=\"retained-next\"[^>]*href=\"[^\"]*messageAfter=([^&\"]+)").matcher(first);
        assertThat(match.find()).isTrue();
        String cursor = match.group(1);
        mvc.perform(post("/account/guardian/" + link + "/revoke").session(guardian.session()).with(csrf()).param("version", "1")).andExpect(status().is3xxRedirection());
        guardian(replacement, child);
        String retained = mvc.perform(get(route).session(guardian.session()).param("subject", child.id().toString()).param("messageAfter", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(revisions(retained)).hasSize(2);
        for (var denied : List.of(child, replacement)) {
            mvc.perform(get(route).session(denied.session()).param("subject", child.id().toString()).param("messageAfter", cursor)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/conversations/" + thread + "/updates").session(guardian.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/conversations/" + thread).session(guardian.session()).param("subject", child.id().toString())).andExpect(status().isOk()).andExpect(content().string(not(containsString("data-refresh-url"))));
    }

    @Test void shouldUseImmutableEventOrderForEqualTimesAndNeverResurfaceSupersededRevisions() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        List<ContentReference> references = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            clock.advance(61);
            var reference = saveMessage(provider, thread, null, "Entrega simultánea " + index);
            submit(provider, thread, provider.id(), reference); references.add(reference);
        }
        operatorProof();
        approve(references.get(0)); approve(references.get(1));
        for (var reference : references.subList(2, 11)) { operatorProof(); approve(reference); }
        assertThat(jdbc.queryForObject("select count(distinct occurred_at) from conversations_event where thread_id=?", Long.class, thread)).isEqualTo(10);
        String recent = observation(customer, thread, null);
        assertThat(revisions(recent)).containsExactlyElementsOf(references.subList(1, 11).stream().map(ContentReference::revisionId).toList());
        String cursor = history(recent);
        assertThat(revisions(observation(customer, thread, cursor))).containsExactly(references.getFirst().revisionId());
        var edited = saveMessage(provider, thread, references.getFirst(), "Revisión nueva con otro evento");
        assertThat(revisions(observation(customer, thread, cursor))).containsExactly(references.getFirst().revisionId());
        submit(provider, thread, provider.id(), edited); approve(edited);
        assertThat(revisions(observation(customer, thread, cursor))).isEmpty();
        assertThat(observation(customer, thread, cursor)).contains("Actividad posterior");
        assertThat(revisions(observation(customer, thread, null))).contains(edited.revisionId()).doesNotContain(references.getFirst().revisionId());
    }

    @Test void shouldOmitAbsentHistoryFromRefreshAndOtherSamePageUrls() throws Exception {
        var provider = author(true, 30);
        var customer = author(false, 30);
        UUID thread = respond(customer, customer.id(), offer(provider));
        var response = mvc.perform(get("/conversations/" + thread).session(customer.session()).param("subject", customer.id().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var refresh = Pattern.compile("data-refresh-url=\"([^\"]+)\"").matcher(response);
        assertThat(refresh.find()).isTrue();
        assertThat(refresh.group(1)).doesNotContain("history=");
        var links = Pattern.compile("data-history-context[^>]*href=\"([^\"]+)\"").matcher(response).results().map(match -> match.group(1)).toList();
        assertThat(links).isNotEmpty().allSatisfy(link -> assertThat(link).doesNotContain("history="));
        mvc.perform(get("/conversations/" + thread + "/updates").session(customer.session()).param("subject", customer.id().toString()).param("history", ""))
                .andExpect(status().isForbidden());
    }

    private ContentReference saveMessage(AccountFixture actor, UUID thread, ContentReference previous, String body) throws Exception {
        var request = multipart("/conversations/messages").session(actor.session()).with(csrf()).param("command", UUID.randomUUID().toString())
                .param("thread", thread.toString()).param("subject", actor.id().toString()).param("kind", "PRIVATE_MESSAGE").param("body", body).param("label", "GENERAL");
        if (previous != null) {
            request.param("resource", previous.resourceId().toString()).param("version", jdbc.queryForObject("select version from conversations_authored where id=?", Long.class, previous.resourceId()).toString());
        }
        String location = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return authored("conversations_authored", UUID.fromString(location.substring(location.indexOf("&message=") + 9)), SurfaceKind.PRIVATE_MESSAGE);
    }

    private List<ContentReference> approvedMessages(AccountFixture provider, UUID thread, int count) throws Exception {
        List<ContentReference> references = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            clock.advance(61);
            String location = mvc.perform(multipart("/conversations/messages").file(new org.springframework.mock.web.MockMultipartFile("uploads", "history.txt", "text/plain", "synthetic:clear".getBytes(java.nio.charset.StandardCharsets.UTF_8))).session(provider.session()).with(csrf())
                    .param("command", UUID.randomUUID().toString()).param("thread", thread.toString()).param("subject", provider.id().toString())
                    .param("kind", "PRIVATE_MESSAGE").param("body", "Historial aprobado " + index).param("label", "GENERAL"))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            var reference = authored("conversations_authored", UUID.fromString(location.substring(location.indexOf("&message=") + 9)), SurfaceKind.PRIVATE_MESSAGE);
            submit(provider, thread, provider.id(), reference);
            operatorProof();
            approve(reference);
            references.add(reference);
        }
        return List.copyOf(references);
    }

    private String observation(AccountFixture actor, UUID thread, String history) throws Exception {
        MockHttpServletRequestBuilder request = get("/conversations/" + thread + "/updates").session(actor.session()).param("subject", actor.id().toString());
        if (history != null) { request.param("history", history); }
        return mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
    }

    private String history(String html) {
        var match = Pattern.compile("id=\"history-older\"[^>]*href=\"[^\"]*[?&](?:amp;)?history=([^&\"]+)").matcher(html);
        assertThat(match.find()).as("bounded earlier navigation").isTrue();
        return java.net.URLDecoder.decode(match.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<UUID> revisions(String html) {
        return Pattern.compile("data-message-revision=\"([^\"]+)\"").matcher(html).results().map(match -> UUID.fromString(match.group(1))).toList();
    }
}
