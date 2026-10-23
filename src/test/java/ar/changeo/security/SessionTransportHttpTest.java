package ar.changeo.security;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import ar.changeo.identity.OperatorService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTransportHttpTest {
    private static final String PASSWORD = "Synthetic-runtime-password-12345";

    @Test
    void shouldAuthenticateWithCookiesAndDenyUrlSessionsInTheRealContainer() throws Exception {
        Path root = privateRoot();
        try (var context = start(root, "session_" + random(), Map.of())) {
            String contact = "runtime-" + random() + "@example.test";
            context.getBean(AccountService.class).register(contact, PASSWORD);
            try (var browser = new Browser(port(context)); var encodingBrowser = new Browser(port(context))) {
            var nativeEncoding = encodingBrowser.get("/runtime/native-encoding");
            assertThat(nativeEncoding.body()).isEqualTo("/account");
            var interrupted = browser.get("/account");
            assertThat(interrupted.headers().firstValue("location").orElseThrow()).doesNotContain(";jsessionid");
            var login = browser.get("/login");
            String initial = browser.session();
            var authenticated = browser.post("/login", login.body(), Map.of("username", contact, "password", PASSWORD));
            assertThat(authenticated.statusCode()).isEqualTo(302);
            assertThat(URI.create(authenticated.headers().firstValue("location").orElseThrow()).getPath()).isEqualTo("/account");
            assertThat(browser.session()).isNotEqualTo(initial);
            String cookie = authenticated.headers().firstValue("set-cookie").orElseThrow();
            assertThat(cookie).contains("JSESSIONID=", "Path=/", "HttpOnly", "SameSite=Strict").doesNotContain("Secure", "Max-Age", "Expires");
            assertThat(browser.get("/account").statusCode()).isEqualTo(200);
            assertThat(browser.get("/account/runtime-encoded").body()).isEqualTo("/account");
            try (var withoutCookies = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var stolenUrl = withoutCookies.send(HttpRequest.newBuilder(browser.uri("/account;jsessionid=" + browser.session())).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(stolenUrl.statusCode()).isEqualTo(400);
            }
            var missingCsrf = browser.send("/account/preferences", "sensitive=true");
            assertThat(missingCsrf.statusCode()).isEqualTo(403);
            var account = browser.get("/account");
            assertThat(browser.post("/logout", account.body(), Map.of()).statusCode()).isEqualTo(302);
            assertThat(browser.get("/account").statusCode()).isEqualTo(302);
            }
        }
    }

    @Test
    void shouldRotateGenuineMfaAndPasswordProofThenInvalidateEpochAndLogout() throws Exception {
        Path root = privateRoot();
        try (var context = start(root, "session_mfa_" + random(), Map.of())) {
            String contact = "runtime-operator-" + random() + "@example.test";
            Path enrollment = root.resolve("operator.private");
            context.getBean(OperatorService.class).provision(contact, java.util.Set.of("IDENTITY_ADMIN", "AUDIT_READER"), enrollment);
            Map<String, String> fields = Files.readAllLines(enrollment).stream().filter(line -> line.contains("="))
                    .collect(java.util.stream.Collectors.toMap(line -> line.substring(0, line.indexOf('=')), line -> line.substring(line.indexOf('=') + 1)));
            var browser = new Browser(port(context));
            try {
                assertThat(browser.post("/login", browser.get("/login").body(), Map.of("username", contact, "password", fields.get("password"))).statusCode()).isEqualTo(302);
                assertThat(browser.get("/admin/accounts").statusCode()).isEqualTo(403);
                String loginId = browser.session();
                assertThat(browser.post("/admin/mfa", browser.get("/admin/mfa").body(), Map.of("code", totp(fields.get("totpBase32")))).statusCode()).isEqualTo(302);
                assertThat(browser.session()).isNotEqualTo(loginId);
                assertThat(browser.get("/admin/audit").statusCode()).isEqualTo(200);
                assertThat(browser.get("/admin/accounts").statusCode()).isEqualTo(403);
                String mfaId = browser.session();
                assertThat(browser.post("/admin/reauth", browser.get("/admin/reauth").body(), Map.of("password", fields.get("password"))).statusCode()).isEqualTo(302);
                assertThat(browser.session()).isNotEqualTo(mfaId);
                assertThat(browser.get("/admin/accounts").statusCode()).isEqualTo(200);
                assertThat(browser.send("/logout", "").statusCode()).isEqualTo(403);
                var accounts = context.getBean(AccountService.class);
                accounts.forgot(contact);
                String token;
                try (var mailbox = Files.list(root.resolve("mailbox"))) {
                    token = Files.readString(mailbox.filter(path -> path.getFileName().toString().contains("-RECOVERY-")).findFirst().orElseThrow());
                }
                accounts.reset(token, PASSWORD);
                var expired = browser.get("/account");
                assertThat(expired.statusCode()).isEqualTo(302);
                assertThat(expired.headers().firstValue("location").orElseThrow()).endsWith("/login?expired");
                assertThat(browser.get("/admin/audit").statusCode()).isEqualTo(302);
            } finally { browser.close(); }
        }
    }

    @Test
    void shouldRequireNewLoginAfterCleanRestartWithTheSameNativeSessionStoreBase() throws Exception {
        Path root = privateRoot();
        String schema = "session_restart_" + random();
        String contact = "runtime-restart-" + random() + "@example.test";
        String cookie;
        try (var context = start(root, schema, Map.of()); var browser = new Browser(port(context))) {
            context.getBean(AccountService.class).register(contact, PASSWORD);
            assertThat(browser.post("/login", browser.get("/login").body(), Map.of("username", contact, "password", PASSWORD)).statusCode()).isEqualTo(302);
            assertThat(browser.get("/account").statusCode()).isEqualTo(200);
            cookie = "JSESSIONID=" + browser.session();
        }
        assertThat(root.resolve("tomcat")).isDirectory();
        try (var context = start(root, schema, Map.of()); var browser = new Browser(port(context)); var oldSession = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(browser.uri("/account")).header("Cookie", cookie).GET().build();
            assertThat(oldSession.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(302);
            assertThat(browser.post("/login", browser.get("/login").body(), Map.of("username", contact, "password", PASSWORD)).statusCode()).isEqualTo(302);
            assertThat(browser.get("/account").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void shouldUseTrustedLoopbackTlsAndNativeSecureCookiesWithOrdinaryForms() throws Exception {
        Path root = privateRoot();
        Path privateInputs = Path.of(required("RT1A_PRIVATE_ROOT"));
        String password = Files.readString(privateInputs.resolve("tls.password"));
        var trust = java.security.KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(privateInputs.resolve("trust.p12"))) { trust.load(input, password.toCharArray()); }
        var managers = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        var ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(null, managers.getTrustManagers(), null);
        var tls = Map.<String, Object>of("server.port", "58105", "server.ssl.enabled", "true",
                "server.ssl.key-store", privateInputs.resolve("tls.p12").toUri().toString(),
                "server.ssl.key-store-password", password, "server.ssl.key-store-type", "PKCS12");
        try (var context = start(root, "session_tls_" + random(), tls); var browser = new Browser(port(context), ssl)) {
            String contact = "runtime-tls-" + random() + "@example.test";
            assertThat(browser.post("/register", browser.get("/register").body(), Map.of("contact", contact, "password", PASSWORD)).statusCode()).isEqualTo(302);
            var login = browser.post("/login", browser.get("/login").body(), Map.of("username", contact, "password", PASSWORD));
            assertThat(login.statusCode()).isEqualTo(302);
            assertThat(login.headers().firstValue("set-cookie").orElseThrow()).contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/");
            assertThat(browser.get("/account").statusCode()).isEqualTo(200);
            assertThat(browser.get("/account/runtime-encoded").body()).isEqualTo("/account");
            try (var untrusted = HttpClient.newHttpClient()) {
                assertThatThrownBy(() -> untrusted.send(HttpRequest.newBuilder(browser.uri("/login")).GET().build(), HttpResponse.BodyHandlers.ofString()))
                        .isInstanceOf(javax.net.ssl.SSLHandshakeException.class);
            }
        }
    }

    private static String totp(String base32) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : base32.toCharArray()) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(character);
            bits += 5;
            if (bits >= 8) { bits -= 8; bytes.write((buffer >> bits) & 255); }
        }
        var mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(bytes.toByteArray(), "HmacSHA1"));
        byte[] digest = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(java.time.Instant.now().getEpochSecond() / 30).array());
        int offset = digest[digest.length - 1] & 15;
        return String.format(java.util.Locale.ROOT, "%06d", (java.nio.ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff) % 1_000_000);
    }

    static ConfigurableApplicationContext start(Path root, String schema, Map<String, Object> extra, Class<?>... sources) {
        var application = new SpringApplication(java.util.stream.Stream.concat(java.util.stream.Stream.of(ChangeoApplication.class, EncodingEndpoint.class), java.util.Arrays.stream(sources)).toArray(Class<?>[]::new));
        var properties = new java.util.HashMap<String, Object>();
        properties.put("server.port", "0");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.tomcat.basedir", root.resolve("tomcat").toString());
        properties.put("spring.datasource.url", required("CHANGEO_DATABASE_URL"));
        properties.put("spring.datasource.username", required("CHANGEO_DATABASE_USER"));
        properties.put("spring.datasource.password", required("CHANGEO_DATABASE_PASSWORD"));
        properties.put("spring.flyway.schemas", schema);
        properties.put("spring.flyway.default-schema", schema);
        properties.put("spring.jpa.properties.hibernate.default_schema", schema);
        properties.put("spring.datasource.hikari.schema", schema);
        properties.put("changeo.mfa-key", root.resolve("mfa.key").toString());
        properties.put("changeo.mailbox", root.resolve("mailbox").toString());
        properties.put("changeo.files-root", root.resolve("artifacts").toString());
        properties.put("changeo.synthetic-evidence", "false");
        properties.put("changeo.synthetic-moderation", "false");
        properties.put("changeo.synthetic-payments", "false");
        properties.put("changeo.synthetic-subscriptions", "false");
        properties.put("changeo.moderation-worker", "false");
        properties.put("changeo.notification-worker", "false");
        properties.put("changeo.payments-worker", "false");
        properties.put("changeo.subscription-worker", "false");
        properties.putAll(extra);
        return application.run(properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    static Path privateRoot() throws Exception {
        Path root = Path.of(required("RT1A_PRIVATE_ROOT")).resolve("http-" + random());
        return Files.createDirectory(root, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
    }

    static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Explicit private runtime requires " + name); }
        return value;
    }

    static String random() { return UUID.randomUUID().toString().replace("-", ""); }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class EncodingEndpoint {
        @Bean
        @Order(-1)
        SecurityFilterChain encodingSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/runtime/native-encoding").authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }

        @GetMapping("/runtime/native-encoding")
        String nativeEncode(HttpServletRequest request, HttpServletResponse response) {
            request.getSession();
            return response.encodeURL("/account");
        }

        @GetMapping("/account/runtime-encoded")
        String encode(HttpServletResponse response) { return response.encodeURL("/account"); }
    }

    static final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client;
        private final URI base;

        Browser(int port) { this(URI.create("http://127.0.0.1:" + port), HttpClient.newBuilder()); }
        Browser(int port, javax.net.ssl.SSLContext ssl) { this(URI.create("https://127.0.0.1:" + port), HttpClient.newBuilder().sslContext(ssl)); }
        private Browser(URI base, HttpClient.Builder builder) {
            this.base = base;
            this.client = builder.cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        @Override public void close() { client.close(); }
        URI uri(String path) { return base.resolve(path); }
        String session() { return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
        HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> send(String path, String body) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> post(String path, String html, Map<String, String> fields) throws Exception {
            var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
            assertThat(csrf.find()).as("real rendered synchronizer token").isTrue();
            var form = new java.util.LinkedHashMap<>(fields);
            form.put("_csrf", csrf.group(1));
            String body = form.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
            return send(path, body);
        }
    }
}
