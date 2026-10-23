package ar.changeo.web;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ChangeoApplication.class, properties = {
        "changeo.synthetic-evidence=false", "changeo.synthetic-moderation=false",
        "changeo.synthetic-payments=false", "changeo.synthetic-subscriptions=false",
        "changeo.moderation-worker=false", "changeo.payments-worker=false", "changeo.subscription-worker=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AccountRecoveryHttpTest {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "account_recovery_" + RUN;
    private static final Path RUNTIME = Path.of(".runtime", "account-recovery-tests", RUN);
    private static final String PASSWORD = "Synthetic-password-12345";

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void isolatedResources(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> requiredEnvironment("CHANGEO_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> requiredEnvironment("CHANGEO_DATABASE_USER"));
        properties.add("spring.datasource.password", () -> requiredEnvironment("CHANGEO_DATABASE_PASSWORD"));
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("changeo.mfa-key", () -> RUNTIME.resolve("mfa.key").toString());
        properties.add("changeo.mailbox", () -> RUNTIME.resolve("mailbox").toString());
        properties.add("changeo.files-root", () -> RUNTIME.resolve("artifacts").toString());
    }

    @Test
    void shouldRecoverRegistrationWithEscapedBoundedContactAndFreshPassword() throws Exception {
        String contact = "<img src=x onerror=alert(1)>\"&@example.com";
        MvcResult result = perform(post("/register").param("contact", contact).param("password", PASSWORD), 403);
        String html = html(result);
        assertForm(html, "/register");
        assertThat(value(html, "contact")).isEqualTo(contact);
        assertThat(html).doesNotContain("<img");
        assertSecretsAbsent(result, PASSWORD);
        assertThat(value(html, "password")).isEmpty();
        String large = "x".repeat(400) + "@example.com";
        assertThat(value(html(perform(post("/register").param("contact", large).param("password", PASSWORD), 403)), "contact"))
                .hasSize(254);
        String valid = contact();
        mvc.perform(source(post("/register")).with(csrf()).param("contact", valid).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login"));
        login(valid, PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/register", "/password/forgot", "/password/reset"})
    void shouldRecoverMissingPublicFieldsWithoutRetainingSecrets(String route) throws Exception {
        MvcResult result = perform(post(route), 400);
        assertForm(html(result), route);
        assertThat(html(result)).contains("role=\"alert\"");
    }

    @Test
    void shouldKeepForgotAcknowledgmentGenericAndRecoverRateLimitWithSafeContact() throws Exception {
        String known = register();
        Object notice = null;
        for (String contact : new String[]{known, contact(), "malformed"}) {
            MvcResult result = mvc.perform(source(post("/password/forgot")).with(csrf()).param("contact", contact))
                    .andExpect(redirectedUrl("/password/forgot")).andReturn();
            if (notice == null) { notice = result.getFlashMap().get("notice"); }
            assertThat(result.getFlashMap().get("notice")).isEqualTo(notice);
        }
        Path mailbox = RUNTIME.resolve("mailbox");
        Path saved = RUNTIME.resolve("mailbox-saved-" + UUID.randomUUID());
        Files.move(mailbox, saved);
        Files.createFile(mailbox);
        try {
            var pending = mvc.perform(source(post("/password/forgot")).with(csrf()).param("contact", known))
                    .andExpect(redirectedUrl("/password/forgot")).andReturn();
            assertThat(pending.getFlashMap().get("notice")).isEqualTo(notice);
        } finally {
            Files.move(mailbox, RUNTIME.resolve("mailbox-unavailable-" + UUID.randomUUID()));
            Files.move(saved, mailbox);
        }
        String input = "<retry>\"&@example.test";
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(source(post("/password/forgot")).with(csrf()).param("contact", input))
                    .andExpect(redirectedUrl("/password/forgot"));
        }
        String html = html(perform(post("/password/forgot").param("contact", input), 403));
        assertForm(html, "/password/forgot");
        assertThat(value(html, "contact")).isEqualTo(input);
        assertThat(html).doesNotContain("<retry>");
    }

    @Test
    void shouldRecoverResetAndPreserveGenerationOneUseAndSessionInvalidation() throws Exception {
        String contact = register();
        UUID id = accounts.credentials(contact).orElseThrow().id();
        MockHttpSession current = login(contact, PASSWORD);
        MockHttpSession stale = login(contact, PASSWORD);
        String invalid = "invalid-private-code";
        MvcResult failure = perform(post("/password/reset").session(current).param("token", invalid).param("password", PASSWORD), 403);
        assertForm(html(failure), "/password/reset");
        assertThat(html(failure)).contains("href=\"/password/forgot\"");
        assertSecretsAbsent(failure, invalid, PASSWORD);
        assertThat(value(html(failure), "token")).isEmpty();
        assertThat(value(html(failure), "password")).isEmpty();
        accounts.forgot(contact);
        String old = token(id, "RECOVERY");
        accounts.forgot(contact);
        String fresh = token(id, "RECOVERY");
        assertSecretsAbsent(perform(post("/password/reset").param("token", old).param("password", PASSWORD), 403), old, PASSWORD);
        String replacement = "Replacement-password-12345";
        mvc.perform(source(post("/password/reset")).session(current).with(csrf()).param("token", fresh).param("password", replacement))
                .andExpect(redirectedUrl("/login"));
        assertThat(current.isInvalid()).isTrue();
        mvc.perform(get("/account").session(stale)).andExpect(redirectedUrl("/login?expired"));
        assertSecretsAbsent(perform(post("/password/reset").param("token", fresh).param("password", PASSWORD), 403), fresh, PASSWORD);
        login(contact, replacement);
    }

    @Test
    void shouldKeepContactRecoveryActorOwnedAndAllowRenderedCsrfCorrection() throws Exception {
        String contact = register();
        String other = register();
        UUID id = accounts.credentials(contact).orElseThrow().id();
        UUID otherId = accounts.credentials(other).orElseThrow().id();
        MockHttpSession session = login(contact, PASSWORD);
        String foreignToken = token(otherId, "CONTACT");
        String page = html(mvc.perform(get("/account").session(session)).andExpect(status().isOk()).andReturn());
        MvcResult failure = mvc.perform(source(post("/account/contact/verify")).session(session)
                        .param("_csrf", value(page, "_csrf")).param("token", foreignToken).param("id", otherId.toString()))
                .andExpect(status().isForbidden()).andReturn();
        String html = html(failure);
        assertForm(html, "/account/contact/verify");
        assertForm(html, "/account/contact/resend");
        assertSecretsAbsent(failure, foreignToken);
        assertThat(html).contains(id.toString()).doesNotContain(otherId.toString());
        mvc.perform(post("/account/contact/resend").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/account/contact/resend").session(session).param("_csrf", value(html, "_csrf")))
                .andExpect(redirectedUrl("/account"));
        String fresh = token(id, "CONTACT");
        mvc.perform(source(post("/account/contact/verify")).session(session).with(csrf()).param("token", fresh))
                .andExpect(redirectedUrl("/account"));
        String verified = html(mvc.perform(get("/account").session(session)).andExpect(status().isOk()).andReturn());
        assertThat(verified).doesNotContain("action=\"/account/contact/verify\"");
        assertSecretsAbsent(perform(post("/account/contact/verify").session(session).param("token", fresh), 403), fresh);
    }

    @Test
    void shouldKeepContactFormsAvailableAfterMissingCodeOrResendLimit() throws Exception {
        MockHttpSession session = login(register(), PASSWORD);
        assertForm(html(perform(post("/account/contact/verify").session(session), 400)), "/account/contact/verify");
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(source(post("/account/contact/resend")).session(session).with(csrf()))
                    .andExpect(redirectedUrl("/account"));
        }
        String limited = html(perform(post("/account/contact/resend").session(session), 403));
        assertForm(limited, "/account/contact/verify");
        assertForm(limited, "/account/contact/resend");
        assertThat(limited).contains("Demasiados intentos");
    }

    @Test
    void shouldRetainUnsavedPreferencesWhileFreshGetReflectsOnlyCommittedState() throws Exception {
        MockHttpSession session = login(register(), PASSWORD);
        MvcResult failure = perform(post("/account/preferences").session(session).param("sensitive", "true").param("adult", "true"), 403);
        assertForm(html(failure), "/account/preferences");
        assertThat(input(html(failure), "sensitive")).contains("checked");
        assertThat(input(html(failure), "adult")).contains("checked");
        assertThat(html(failure)).contains("no se guardaron");
        String persisted = html(mvc.perform(get("/account").session(session)).andExpect(status().isOk()).andReturn());
        assertThat(input(persisted, "sensitive")).doesNotContain("checked");
        assertThat(input(persisted, "adult")).doesNotContain("checked");
        mvc.perform(source(post("/account/preferences")).session(session).with(csrf()).param("sensitive", "true"))
                .andExpect(redirectedUrl("/account"));
        String saved = html(mvc.perform(get("/account").session(session)).andExpect(status().isOk()).andReturn());
        assertThat(input(saved, "sensitive")).contains("checked");
        assertThat(input(saved, "adult")).doesNotContain("checked");
        perform(post("/account/preferences").session(session).param("adult", "invalid"), 400);
    }

    @Test
    void shouldRecoverGuardianParsingAndDenialWithoutCreatingAuthority() throws Exception {
        String contact = register();
        MockHttpSession session = login(contact, PASSWORD);
        String input = "<other>\"&";
        String invalid = html(perform(post("/account/guardian/request").session(session)
                .param("other", input).param("actingAsGuardian", "true"), 400));
        assertForm(invalid, "/account/guardian/request");
        assertThat(value(invalid, "other")).isEqualTo(input);
        assertThat(invalid).doesNotContain("<other>").contains("value=\"true\" selected=\"selected\"");
        assertForm(html(perform(post("/account/guardian/request").session(session).param("other", UUID.randomUUID().toString()), 400)),
                "/account/guardian/request");
        String self = accounts.credentials(contact).orElseThrow().id().toString();
        String denied = html(perform(post("/account/guardian/request").session(session).param("other", self)
                .param("actingAsGuardian", "true"), 403));
        assertThat(value(denied, "other")).isEqualTo(self);
        assertThat(denied).contains("Participantes distintos", "No hay relaciones registradas.")
                .doesNotContain("Registrar consentimiento de prueba", "Revocar relación");
        assertThat(jdbc.queryForObject("select count(*) from identity_guardian_link", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/account/contact/verify", "/account/preferences", "/account/guardian/request"})
    void shouldKeepProtectedFormsBehindAuthenticationAndCsrf(String route) throws Exception {
        mvc.perform(source(post(route)).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(source(post(route)).session(login(register(), PASSWORD))).andExpect(status().isForbidden());
    }

    private String register() {
        String contact = contact();
        accounts.register(contact, PASSWORD);
        return contact;
    }

    private MockHttpSession login(String contact, String password) throws Exception {
        return (MockHttpSession) mvc.perform(source(post("/login")).with(csrf()).param("username", contact).param("password", password))
                .andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, int expected) throws Exception {
        return mvc.perform(source(request).with(csrf())).andExpect(status().is(expected)).andReturn();
    }

    private static MockHttpServletRequestBuilder source(MockHttpServletRequestBuilder request) {
        return request.with(value -> { value.setRemoteAddr(UUID.randomUUID().toString()); return value; });
    }

    private static String contact() { return "recovery-" + UUID.randomUUID() + "@example.test"; }

    private static String token(UUID id, String purpose) throws Exception {
        try (var files = Files.list(RUNTIME.resolve("mailbox"))) {
            Path latest = files.filter(path -> path.getFileName().toString().startsWith(id + "-" + purpose + "-"))
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified())).orElseThrow();
            return Files.readString(latest);
        }
    }

    private static void assertForm(String html, String action) {
        assertThat(html).contains("action=\"" + action + "\"", "method=\"post\"");
        assertThat(value(html, "_csrf")).isNotBlank();
    }

    private static void assertSecretsAbsent(MvcResult result, String... secrets) throws Exception {
        String rendered = html(result) + result.getModelAndView().getModel() + result.getFlashMap();
        if (result.getRequest().getSession(false) instanceof MockHttpSession session) {
            for (String name : java.util.Collections.list(session.getAttributeNames())) {
                rendered += session.getAttribute(name);
            }
        }
        for (String secret : secrets) { assertThat(rendered.contains(secret)).as("No secret retained in response/model/flash").isFalse(); }
    }

    private static String input(String html, String name) {
        var match = Pattern.compile("<input\\b[^>]*\\bname=\"" + Pattern.quote(name) + "\"[^>]*>").matcher(html);
        assertThat(match.find()).as("Rendered input %s", name).isTrue();
        return match.group();
    }

    private static String value(String html, String name) {
        var match = Pattern.compile("\\bvalue=\"([^\"]*)\"").matcher(input(html, name));
        return match.find() ? HtmlUtils.htmlUnescape(match.group(1)) : "";
    }

    private static String html(MvcResult result) throws Exception { return result.getResponse().getContentAsString(); }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Explicit caller-owned database requires " + name); }
        return value;
    }
}
