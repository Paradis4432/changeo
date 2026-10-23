package ar.changeo.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

import static ar.changeo.security.PrivateHttpsTestFixture.*;
import static org.assertj.core.api.Assertions.*;

class PrivateHttpsOriginHttpTest {
    @Test
    void shouldAdmitNativeTlsAndRejectBeforeSessionsSecuritySourcesAndDatabase() throws Exception {
        try (App app = start(Mode.DIRECT)) {
            var browser = new Browser(DIRECT_PORT, true, "127.0.0.1");
            var ready = browser.get("/readyz");
            assertThat(ready.status()).isEqualTo(200);
            assertThat(ready.body()).isEqualTo("ready\n");
            assertThat(ready.header("referrer-policy")).isEqualTo("same-origin");
            assertThat(ready.header("cache-control")).isEqualTo("no-store");
            assertThat(ready.header("set-cookie")).isEmpty();
            var ordinary = app.actor();
            assertThat(redirect(browser.login(ordinary))).isEqualTo("/account");
            String valid = browser.cookie;
            var stale = new Browser(DIRECT_PORT, true, "127.0.0.1");
            var staleAccount = app.actor();
            assertThat(stale.login(staleAccount).status()).isEqualTo(302);
            app.context.getBean(ar.changeo.identity.AccountService.class).forgot(staleAccount.contact());
            String token;
            try (var files = java.nio.file.Files.list(app.settings.directory().resolve("mailbox"))) {
                token = java.nio.file.Files.readString(files.filter(path -> path.getFileName().toString().startsWith(staleAccount.id() + "-RECOVERY-")).findFirst().orElseThrow()).strip();
            }
            app.context.getBean(ar.changeo.identity.AccountService.class).reset(token, PASSWORD);
            Map<String, String> before = app.rows(true);
            for (String cookie : new String[]{null, valid, stale.cookie}) {
                List<String> cookies = cookie == null ? List.of() : List.of("Cookie: " + cookie);
                var effects = app.counters.effects();
                for (String host : List.of("evil.test:" + DIRECT_PORT, "127.0.0.1", "127.0.0.1:443",
                        "127.0.0.1.evil.test:" + DIRECT_PORT)) {
                    List<String> headers = new ArrayList<>(cookies);
                    headers.add("Host: " + host);
                    rejection(wire(DIRECT_PORT, true, "127.0.0.1", "GET /readyz HTTP/1.1", headers, ""), 400);
                }
                for (String origin : List.of("null", "https://evil.test", DIRECT_ORIGIN + "/", DIRECT_ORIGIN + "," + DIRECT_ORIGIN,
                        DIRECT_ORIGIN + " " + DIRECT_ORIGIN, "http://127.0.0.1:" + DIRECT_PORT, "x".repeat(129))) {
                    List<String> headers = new ArrayList<>(cookies);
                    headers.add("Host: 127.0.0.1:" + DIRECT_PORT);
                    headers.add("Origin: " + origin);
                    rejection(wire(DIRECT_PORT, true, "127.0.0.1", "POST /moderation/own HTTP/1.1", headers, "not-a-form"), 403);
                }
                rejection(wire(DIRECT_PORT, true, "127.0.0.1", "POST /login HTTP/1.1",
                        List.of("Host: 127.0.0.1:" + DIRECT_PORT, "Origin: " + DIRECT_ORIGIN, "Origin: " + DIRECT_ORIGIN), ""), 403);
                assertThat(app.counters.effects()).isEqualTo(effects);
            }
            assertThat(app.rows(true)).isEqualTo(before);
            assertThat(redirect(stale.get("/account"))).isEqualTo("/login?expired");
            for (int attempt = 0; attempt < 20; attempt++) {
                assertThat(browser.post("/moderation/own", "").status()).isEqualTo(403);
            }
            assertThat(browser.post("/moderation/own", "").status()).isEqualTo(429);
        }
    }

    @Test
    void shouldPreserveRealFormCsrfLocalNavigationFixationMfaPasswordRolesAndLogout() throws Exception {
        try (App app = start(Mode.DIRECT)) {
            var operator = app.operator();
            var browser = new Browser(DIRECT_PORT, true, "127.0.0.1");
            String destination = "/account/preferences?tag=one&tag=two";
            assertThat(browser.get(destination).header("location")).endsWith("/login");
            String before = browser.cookie;
            String csrf = browser.csrf("/login");
            var noCsrf = browser.post("/login", form(Map.of("username", operator.contact(), "password", operator.password())));
            assertThat(noCsrf.status()).isEqualTo(403);
            assertThat(browser.request("POST", "/login", List.of("Content-Type: application/x-www-form-urlencoded", "Origin: " + DIRECT_ORIGIN),
                    form(Map.of("username", operator.contact(), "password", operator.password()))).status()).isEqualTo(403);
            var login = browser.request("POST", "/login", List.of("Content-Type: application/x-www-form-urlencoded", "Origin: " + DIRECT_ORIGIN),
                    form(Map.of("username", operator.contact(), "password", operator.password(), "_csrf", csrf)));
            assertThat(redirect(login)).isEqualTo(destination);
            assertThat(browser.cookie).isNotEqualTo(before);
            assertThat(login.header("set-cookie")).contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/").doesNotContain("Max-Age", "Domain=");
            assertThat(browser.get("/admin/accounts").status()).isEqualTo(403);
            String code = totp(operator, app.clock());
            before = browser.cookie;
            assertThat(redirect(browser.post("/admin/mfa", form(Map.of("code", code, "_csrf", browser.csrf("/admin/mfa"))))))
                    .isEqualTo("/admin/reauth");
            assertThat(browser.cookie).isNotEqualTo(before);
            assertThat(browser.get("/admin/audit").status()).isEqualTo(200);
            assertThat(browser.get("/admin/accounts").status()).isEqualTo(403);
            before = browser.cookie;
            assertThat(redirect(browser.post("/admin/reauth", form(Map.of("password", operator.password(), "_csrf", browser.csrf("/admin/reauth"))))))
                    .isEqualTo("/admin/accounts");
            assertThat(browser.cookie).isNotEqualTo(before);
            assertThat(browser.get("/admin/accounts").status()).isEqualTo(200);
            assertThat(browser.get("/ph1-native/async").status()).isEqualTo(200);
            Reply error = browser.get("/ph1-native/error");
            assertThat(error.status()).isEqualTo(500);
            assertThat(error.body()).doesNotContain("supplied-private-exception-marker", "ServletException");
            assertThat(browser.post("/admin/mfa", form(Map.of("code", code, "_csrf", browser.csrf("/admin/mfa")))).status()).isEqualTo(403);
            assertThat(browser.post("/logout", "").status()).isEqualTo(403);
            assertThat(redirect(browser.post("/logout", form(Map.of("_csrf", browser.csrf("/account")))))).isEqualTo("/");
            assertThat(browser.get("/account").header("location")).endsWith("/login");
            var cookiesDisabled = new Browser(DIRECT_PORT, true, "127.0.0.1");
            assertThat(cookiesDisabled.get("/account;jsessionid=" + before.substring("JSESSIONID=".length())).status()).isNotEqualTo(200);
        }
    }

    @Test
    void shouldDenyActualInsecureNativeHttpDespiteForgedForwardingWithoutDownstreamEffects() throws Exception {
        try (App app = start(Mode.HTTP_COMPONENT)) {
            var rows = app.rows(true);
            var effects = app.counters.effects();
            rejection(wire(HTTP_PORT, false, "127.0.0.1", "GET /readyz HTTP/1.1",
                    List.of("Host: 127.0.0.1:" + DIRECT_PORT, "X-Forwarded-Proto: https"), ""), 403);
            rejection(wire(HTTP_PORT, false, "127.0.0.1", "POST /login HTTP/1.1",
                    List.of("Host: evil.test", "Origin: null", "X-Forwarded-Proto: https"), ""), 400);
            assertThat(app.counters.effects()).isEqualTo(effects);
            assertThat(app.rows(true)).isEqualTo(rows);
        }
    }

    @Test
    void shouldKeepNativeParserFailuresPrivateAndSeparatePlaintextTlsFailure() throws Exception {
        try (App app = start(Mode.DIRECT)) {
            var effects = app.counters.effects();
            for (List<String> headers : List.of(List.<String>of(),
                    List.of("Host: 127.0.0.1:" + DIRECT_PORT, "Host: 127.0.0.1:" + DIRECT_PORT),
                    List.of("Host: supplied-private-host-should-not-appear/invalid"),
                    List.of("Host: user@127.0.0.1:" + DIRECT_PORT), List.of("Host: 127.0.0.1%2f:" + DIRECT_PORT),
                    List.of("Host: 127.0.0.1:65536"), List.of("Host: [::1"))) {
                Reply reply = wire(DIRECT_PORT, true, "127.0.0.1", "GET /supplied-private-path HTTP/1.1", headers, "");
                assertThat(reply.status()).isEqualTo(400);
                assertThat(reply.body()).doesNotContain("supplied-private", "Tomcat", "Exception");
                assertThat(reply.header("set-cookie")).isEmpty();
            }
            Reply conflicting = wire(DIRECT_PORT, true, "127.0.0.1", "GET https://supplied-private-host.example.test/ HTTP/1.1",
                    List.of("Host: 127.0.0.1:" + DIRECT_PORT), "");
            assertThat(conflicting.status()).isEqualTo(400);
            assertThat(conflicting.body()).doesNotContain("supplied-private", "Exception", "Tomcat");
            assertThat(app.counters.effects()).isEqualTo(effects);
            try {
                Reply plaintext = wire(DIRECT_PORT, false, "127.0.0.1", "GET / HTTP/1.1", List.of("Host: 127.0.0.1:" + DIRECT_PORT), "");
                assertThat(plaintext.status()).isEqualTo(400);
                assertThat(plaintext.body()).doesNotContain("forbidden");
            } catch (java.io.EOFException | java.net.SocketException protocolTermination) {
                assertThat(app.counters.effects()).isEqualTo(effects);
            }
        }
    }

    @Test
    void shouldFailInvalidSelectedConfigurationAndKeepAbsentHttpCompatible() throws Exception {
        for (String value : List.of("", "false", "https://localhost:58107", "https://127.0.0.1:58107/")) {
            assertThatThrownBy(() -> start(Mode.DIRECT, "127.0.0.1", DIRECT_PORT, value, Map.of()))
                    .hasRootCauseMessage("Invalid private HTTPS configuration");
        }
        for (Map<String, String> overrides : List.of(Map.of("server.ssl.enabled", "false"),
                Map.of("server.port", Integer.toString(HTTP_PORT)), Map.of("server.servlet.context-path", "/other"),
                Map.of("server.forward-headers-strategy", "native"), Map.of("server.address", "127.0.0.2"))) {
            assertThatThrownBy(() -> start(Mode.DIRECT, "127.0.0.1", DIRECT_PORT, DIRECT_ORIGIN, overrides))
                    .hasRootCauseMessage("Invalid private HTTPS configuration");
        }
        try (App app = start(Mode.ORDINARY)) {
            assertThat(app.context.getBeansOfType(PrivateHttpsOrigin.class)).isEmpty();
            Reply reply = wire(HTTP_PORT, false, "127.0.0.1", "GET /login HTTP/1.1", List.of("Host: foreign.test"), "");
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.body()).contains("_csrf");
            assertThat(reply.header("referrer-policy")).isEqualTo("same-origin");
        }
    }

    @Test
    void shouldUseNativeIpv6LiteralWithMatchingSanAndVerifiedHostname() throws Exception {
        try (App app = start(Mode.DIRECT, "::1", DIRECT_PORT, "https://[::1]:" + DIRECT_PORT, Map.of())) {
            Reply admitted = wireTo("::1", DIRECT_PORT, true, "::1", "GET /readyz HTTP/1.1", List.of("Host: [::1]:" + DIRECT_PORT), "");
            assertThat(admitted.status()).isEqualTo(200);
            assertThat(admitted.body()).isEqualTo("ready\n");
            rejection(wireTo("::1", DIRECT_PORT, true, "::1", "GET /readyz HTTP/1.1",
                    List.of("Host: [0:0:0:0:0:0:0:1]:" + DIRECT_PORT), ""), 400);
        }
    }

    @Test
    void shouldRejectUntrustedCertificateAndWrongHostnameWithoutDisablingVerification() throws Exception {
        try (App app = start(Mode.DIRECT)) {
            for (boolean untrusted : List.of(true, false)) {
                SSLContext trust = untrusted ? SSLContext.getDefault() : tls();
                try (var raw = new java.net.Socket()) {
                    raw.connect(new java.net.InetSocketAddress(java.net.InetAddress.ofLiteral("127.0.0.1"), DIRECT_PORT), 2000);
                    try (var ssl = (javax.net.ssl.SSLSocket) trust.getSocketFactory().createSocket(raw,
                            untrusted ? "127.0.0.1" : "mismatch.invalid", DIRECT_PORT, true)) {
                        ssl.setSoTimeout(2000);
                        var parameters = ssl.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        ssl.setSSLParameters(parameters);
                        assertThatThrownBy(ssl::startHandshake).isInstanceOf(SSLHandshakeException.class);
                    }
                }
            }
            assertThat(new Browser(DIRECT_PORT, true, "127.0.0.1").get("/readyz").status()).isEqualTo(200);
        }
    }

    @Test
    void shouldRejectActualNativeOverridesAndRegisteredFrameworkForwarding() {
        for (Class<?> configuration : List.of(ProxyOverride.class, AdditionalConnector.class,
                BindOverride.class, ForwardedRegistration.class, NativeForwardingRegistration.class, NativeSslForwarding.class)) {
            assertThatThrownBy(() -> start(Mode.DIRECT, "127.0.0.1", DIRECT_PORT, DIRECT_ORIGIN, Map.of(), configuration))
                    .hasRootCauseMessage("Invalid private HTTPS configuration");
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ProxyOverride {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> connectorProxy() {
            return factory -> factory.addConnectorCustomizers(connector -> connector.setProxyName("untrusted.invalid"));
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class AdditionalConnector {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> extraConnector() {
            return factory -> {
                var connector = new org.apache.catalina.connector.Connector();
                connector.setPort(BACKEND_PORT);
                connector.setProperty("address", "127.0.0.1");
                factory.addAdditionalConnectors(connector);
            };
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class BindOverride {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> wrongNativeAddress() {
            return factory -> factory.setAddress(java.net.InetAddress.ofLiteral("127.0.0.2"));
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ForwardedRegistration {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.ForwardedHeaderFilter> frameworkForwarding() {
            return new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new org.springframework.web.filter.ForwardedHeaderFilter());
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class NativeForwardingRegistration {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<org.apache.catalina.filters.RemoteIpFilter> nativeForwarding() {
            return new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new org.apache.catalina.filters.RemoteIpFilter());
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class NativeSslForwarding {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> sslForwarding() {
            return factory -> factory.addEngineValves(new org.apache.catalina.valves.SSLValve());
        }
    }

    static void rejection(Reply reply, int expected) {
        assertThat(reply.status()).isEqualTo(expected);
        assertThat(reply.body()).isEqualTo(expected == 400 ? "bad request\n" : "forbidden\n");
        assertThat(reply.header("cache-control")).isEqualTo("no-store");
        assertThat(reply.header("content-type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(reply.header("x-content-type-options")).isEqualTo("nosniff");
        for (String header : List.of("set-cookie", "location", "access-control-allow-origin", "access-control-allow-credentials")) {
            assertThat(reply.header(header)).isEmpty();
        }
    }

    static String redirect(Reply reply) {
        var destination = java.net.URI.create(reply.header("location"));
        if (destination.isAbsolute()) {
            assertThat(destination.getScheme()).isEqualTo("https");
            assertThat(destination.getHost()).isEqualTo("127.0.0.1");
            assertThat(destination.getPort()).isIn(DIRECT_PORT, EDGE_PORT);
        }
        return destination.getRawPath() + (destination.getRawQuery() == null ? "" : "?" + destination.getRawQuery());
    }
}
