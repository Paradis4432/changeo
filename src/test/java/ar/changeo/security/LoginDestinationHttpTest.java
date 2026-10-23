package ar.changeo.security;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ChangeoApplication.class, properties = {
        "changeo.synthetic-evidence=false", "changeo.synthetic-moderation=false",
        "changeo.synthetic-payments=false", "changeo.synthetic-subscriptions=false",
        "changeo.moderation-worker=false", "changeo.notification-worker=false",
        "changeo.payments-worker=false", "changeo.subscription-worker=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LoginDestinationHttpTest {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "login_destination_" + RUN;
    private static final Path RUNTIME = Path.of(".runtime", "login-destination", RUN);
    private static final String PASSWORD = "Synthetic-password-12345";
    private static final AtomicInteger SOURCE = new AtomicInteger();
    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    private String contact;
    private String source;

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

    @BeforeEach
    void registerAccount() {
        contact = "destination-" + UUID.randomUUID() + "@example.test";
        accounts.register(contact, PASSWORD);
        source = "127.88.0." + SOURCE.incrementAndGet();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/marketplace/create?type=REQUEST", "/marketplace/create?type=OFFER",
            "/account/preferences?tab=privacy&tag=one&tag=two&returnUrl=https://example.test/help",
            "/account/preferences?continue=one&continue=two"})
    void shouldResumeExactProtectedGetAndConsumeDestination(String target) throws Exception {
        MockHttpSession session = interrupted(target);
        String previousId = session.getId();
        login(session, PASSWORD).andExpect(redirectedUrl(target));
        assertThat(session.getId()).isNotEqualTo(previousId);
        assertThat(session.getAttribute(SAVED_REQUEST)).isNull();
        mvc.perform(get(URI.create(target)).session(session)).andExpect(status().isOk());
        login(session, PASSWORD).andExpect(redirectedUrl("/account"));
    }

    @Test
    void shouldPreserveDestinationAcrossRenderedLoginFailureAndRetry() throws Exception {
        String target = "/marketplace/create?type=REQUEST";
        MockHttpSession session = interrupted(target);
        mvc.perform(get("/login").session(session)).andExpect(status().isOk());
        login(session, "Incorrect-password-12345").andExpect(redirectedUrl("/login?error"));
        mvc.perform(get("/login?error").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Revisá tus datos")));
        assertThat(session.getAttribute(SAVED_REQUEST)).isNotNull();
        login(session, PASSWORD).andExpect(redirectedUrl(target));
    }

    @Test
    void shouldKeepDestinationsInsideTheirInitiatingSession() throws Exception {
        MockHttpSession requestSession = interrupted("/marketplace/create?type=REQUEST");
        MockHttpSession offerSession = interrupted("/marketplace/create?type=OFFER");
        login(new MockHttpSession(), PASSWORD).andExpect(redirectedUrl("/account"));
        login(offerSession, PASSWORD).andExpect(redirectedUrl("/marketplace/create?type=OFFER"));
        login(requestSession, PASSWORD).andExpect(redirectedUrl("/marketplace/create?type=REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/", "//evil.example/", "/\\evil.example/", "%0d%0aLocation:evil"})
    void shouldIgnoreClientChosenLoginDestinationsAndReferrer(String target) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/login").session(session).param("next", target).param("redirect", target)
                        .param("returnUrl", target).header("Referer", "https://evil.example/"))
                .andExpect(status().isOk());
        mvc.perform(loginRequest(session, PASSWORD).param("next", target).param("redirect", target)
                        .param("returnUrl", target).header("Referer", "https://evil.example/"))
                .andExpect(redirectedUrl("/account"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void shouldNeverCacheUnsafeMethodsOrMutationParameters(String method) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(request(method, URI.create("/account/preferences")).session(session).with(csrf())
                        .param("sensitive", "true").content("private-mutation-body"))
                .andExpect(status().is3xxRedirection());
        assertThat(session.getAttribute(SAVED_REQUEST)).isNull();
        login(session, PASSWORD).andExpect(redirectedUrl("/account"));
        mvc.perform(get("/account/preferences").session(session)).andExpect(status().isOk());
    }

    @Test
    void shouldPreserveCsrfAndInvalidateSavedNavigationAtLogout() throws Exception {
        MockHttpSession session = interrupted("/marketplace/create?type=REQUEST");
        mvc.perform(post("/login").session(session).param("username", contact).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(session.getAttribute(SAVED_REQUEST)).isNotNull();
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
        login(new MockHttpSession(), PASSWORD).andExpect(redirectedUrl("/account"));
    }

    @Test
    void shouldDiscardSavedOriginAndForwardedHeaderTargets() throws Exception {
        MockHttpSession session = session(mvc.perform(get("/account/preferences?tab=privacy")
                        .with(request -> { request.setServerName("evil.example"); request.setServerPort(8443); request.setScheme("https"); return request; })
                        .header("Host", "evil.example:8443").header("Forwarded", "host=evil.example;proto=https")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(redirectedUrl("/login")).andReturn());
        login(session, PASSWORD).andExpect(redirectedUrl("/account/preferences?tab=privacy"));
        assertThat(session.getAttribute(SAVED_REQUEST)).isNull();
    }

    @Test
    void shouldKeepContextPathAndExactQueryOnce() throws Exception {
        MockHttpSession session = session(mvc.perform(get("/changeo/account/preferences?tab=privacy&tag=one&tag=two")
                        .contextPath("/changeo").servletPath("/account/preferences"))
                .andExpect(status().is3xxRedirection()).andReturn());
        mvc.perform(post("/changeo/login").contextPath("/changeo").servletPath("/login").session(session)
                        .with(csrf()).param("username", contact).param("password", PASSWORD))
                .andExpect(redirectedUrl("/changeo/account/preferences?tab=privacy&tag=one&tag=two"));
        mvc.perform(post("/changeo/login").contextPath("/changeo").servletPath("/login").with(csrf())
                        .param("username", contact).param("password", PASSWORD))
                .andExpect(redirectedUrl("/changeo/account"));
    }

    @Test
    void shouldNotGrantAdminProofWhenResumingAdminDestination() throws Exception {
        MockHttpSession session = interrupted("/admin/accounts");
        login(session, PASSWORD).andExpect(redirectedUrl("/admin/accounts"));
        assertThat(session.getAttribute("mfaEpoch")).isNull();
        assertThat(session.getAttribute("passwordEpoch")).isNull();
        mvc.perform(get("/admin/accounts").session(session)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.example/account", "/%2f%2fevil.example/", "/%5cevil.example/", "/account%0d%0aLocation:evil",
            "/account/../admin", "/account%252f%252fevil"})
    void shouldRejectUnsafeProtectedRequestTargetsAtTheFirewall(String target) throws Exception {
        mvc.perform(get(URI.create(target.startsWith("//") ? "http://localhost" + target : target)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.example/", "https://user@evil.example/account", "ftp://evil.example/account",
            "https://evil.example//other", "https://evil.example/%2fother", "https://evil.example/%5cother",
            "https://evil.example/account?value=%0d%0aLocation:evil", "https://evil.example/account#fragment",
            "https://evil.example/account/../admin", "https://evil.example/%252fother", "not a valid URI", "https://"})
    void shouldFallBackForUnsafeStrategyTargets(String target) throws Exception {
        var request = new MockHttpServletRequest();
        request.setContextPath("/changeo");
        var response = new MockHttpServletResponse();
        new LocalLoginRedirectStrategy(new HttpSessionRequestCache()).sendRedirect(request, response, target);
        assertThat(response.getRedirectedUrl()).isEqualTo("/changeo/account");
    }

    @Test
    void shouldPreserveEncodedQueryAndDiscardOnlyTheOriginAtRedirectBoundary() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        new LocalLoginRedirectStrategy(new HttpSessionRequestCache()).sendRedirect(request, response,
                "https://evil.example:8443/account?next=https%3A%2F%2Fexample.test%2Fhelp&tag=one&tag=two");
        assertThat(response.getRedirectedUrl())
                .isEqualTo("/account?next=https%3A%2F%2Fexample.test%2Fhelp&tag=one&tag=two");
    }

    @Test
    void shouldRejectBackslashRequestAtTheFirewall() throws Exception {
        mvc.perform(get("/account").with(request -> { request.setRequestURI("/account\\evil"); return request; }))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"favicon", "xhr", "json", "multipart", "events", "websocket"})
    void shouldKeepIncidentalRequestsFromOverwritingNavigation(String kind) throws Exception {
        String target = "/marketplace/create?type=REQUEST";
        MockHttpSession session = interrupted(target);
        var incidental = switch (kind) {
            case "favicon" -> get("/favicon.ico");
            case "xhr" -> get("/account/preferences").header("X-Requested-With", "XMLHttpRequest");
            case "json" -> get("/account/preferences").accept("application/json");
            case "multipart" -> get("/account/preferences").accept("multipart/form-data");
            case "events" -> get("/account/preferences").accept("text/event-stream");
            case "websocket" -> get("/account/preferences").header("Upgrade", "websocket");
            default -> throw new IllegalArgumentException(kind);
        };
        mvc.perform(incidental.session(session)).andExpect(status().is3xxRedirection());
        login(session, PASSWORD).andExpect(redirectedUrl(target));
    }

    private MockHttpSession interrupted(String target) throws Exception {
        return session(mvc.perform(get(URI.create(target))).andExpect(status().is3xxRedirection()).andReturn());
    }

    private ResultActions login(MockHttpSession session, String password) throws Exception {
        return mvc.perform(loginRequest(session, password));
    }

    private MockHttpServletRequestBuilder loginRequest(MockHttpSession session, String password) {
        return post("/login").servletPath("/login").session(session).with(csrf())
                .with(request -> { request.setRemoteAddr(source); return request; })
                .param("username", contact).param("password", password);
    }

    private static MockHttpSession session(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Explicit caller-owned test database requires " + name);
        }
        return value;
    }
}
