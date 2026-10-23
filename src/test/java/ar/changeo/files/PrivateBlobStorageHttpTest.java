package ar.changeo.files;

import ar.changeo.files.api.*;
import ar.changeo.moderation.ModerationFailure;
import ar.changeo.moderation.api.*;
import ar.changeo.security.AccountPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivateBlobStorageHttpTest extends PrivateBlobStorageFixture {
    @Test void shouldDeliverOnlyCurrentVerifiedPrivateBytesThroughExistingController() throws Exception {
        UUID author = verified(30), viewer = verified(30), outsider = verified(30);
        actor.set(author);
        var reference = save("synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.of(viewer), "text/plain");
        approve(reference);
        var file = file(reference);
        actor.set(viewer);
        mvc.perform(get("/content/files/" + file.id()).with(user(principal(viewer)))
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("digest", file.digest()))
                .andExpect(status().isOk()).andExpect(content().bytes(CLEAR))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
        actor.set(outsider);
        mvc.perform(get("/content/files/" + file.id()).with(user(principal(outsider)))
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("digest", file.digest()))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic:clear"))));
        actor.set(viewer);
        protocol().mode = R2PrivateBlobStoreTest.Mode.CHECKSUM;
        mvc.perform(get("/content/files/" + file.id()).with(user(principal(viewer)))
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("digest", file.digest()))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("r2-preparation-secret"))));
    }

    @Test
    @EnabledIfSystemProperty(named = "r2.restore", matches = "true")
    void shouldAuthenticateRestoredFixtureAndDenyUnboundOrphan() throws Exception {
        var values = restored();
        var response = mvc.perform(post("/login").with(csrf()).param("username", values.getProperty("contact")).param("password", values.getProperty("password")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/account")).andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        assertThat(session).isNotNull();
        UUID author = UUID.fromString(values.getProperty("actor"));
        actor.set(author);
        var reference = new ContentReference(SurfaceKind.PRIVATE_MESSAGE, UUID.fromString(values.getProperty("resource")), UUID.fromString(values.getProperty("revision")));
        var file = new FileReference(UUID.fromString(values.getProperty("file")), reference, values.getProperty("digest"));
        mvc.perform(get("/content/files/" + file.id()).session(session)
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("digest", file.digest()))
                .andExpect(status().isOk()).andExpect(content().bytes(CLEAR));
        UUID orphan = UUID.fromString(values.getProperty("orphan"));
        assertThat(protocol().objects).containsKey("/changeo-r2-preparation/private-blobs/" + orphan);
        assertThatThrownBy(() -> files.read(new FileReference(orphan, reference, file.digest()), FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        actor.remove();
        assertThatThrownBy(() -> files.read(file, FileAccess.Purpose.ORDINARY)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @EnabledIfSystemProperty(named = "r2.local.restore", matches = "true")
    void shouldAuthenticateRestoredLocalFixtureAndReadExactApprovedBytes() throws Exception {
        var values = localRestored();
        var response = mvc.perform(post("/login").with(csrf()).param("username", values.getProperty("contact")).param("password", values.getProperty("password")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/account")).andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        assertThat(session).isNotNull();
        actor.set(UUID.fromString(values.getProperty("actor")));
        mvc.perform(get("/content/files/" + values.getProperty("file")).session(session)
                .param("resource", values.getProperty("resource")).param("revision", values.getProperty("revision")).param("digest", values.getProperty("digest")))
                .andExpect(status().isOk()).andExpect(content().bytes(CLEAR));
        assertThat(protocol().count("GET")).isZero();
        assertThat(application.getBeanNamesForType(R2PrivateBlobStore.class)).isEmpty();
    }

    private AccountPrincipal principal(UUID id) { return new AccountPrincipal(id, "framework@example.test", "", accounts.epoch(id)); }
}
