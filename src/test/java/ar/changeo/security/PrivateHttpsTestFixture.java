package ar.changeo.security;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import ar.changeo.identity.OperatorService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.catalina.valves.RemoteCIDRValve;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

final class PrivateHttpsTestFixture {
    static final int HTTP_PORT = 58106;
    static final int DIRECT_PORT = 58107;
    static final int BACKEND_PORT = 58108;
    static final int EDGE_PORT = 58109;
    static final String DIRECT_ORIGIN = "https://127.0.0.1:" + DIRECT_PORT;
    static final String EDGE_ORIGIN = "https://127.0.0.1:" + EDGE_PORT;
    static final String PASSWORD = accountPassword();
    static final int MAX_RESPONSE = 2 * 1024 * 1024;
    static final Path ROOT = requiredRoot();

    private PrivateHttpsTestFixture() {}

    enum Mode { DIRECT, HTTP_COMPONENT, PROXY, ORDINARY }

    record Settings(Mode mode, String host, int port, String origin, String schema, Path directory) {}

    static App start(Mode mode) throws Exception {
        int port = switch (mode) {
            case DIRECT -> DIRECT_PORT;
            case PROXY -> BACKEND_PORT;
            default -> HTTP_PORT;
        };
        return start(mode, "127.0.0.1", port, mode == Mode.PROXY ? EDGE_ORIGIN : DIRECT_ORIGIN, Map.of());
    }

    static App start(Mode mode, String host, int port, String origin, Map<String, String> overrides) throws Exception {
        return start(mode, host, port, origin, overrides, new Class<?>[0]);
    }

    static App start(Mode mode, String host, int port, String origin, Map<String, String> overrides, Class<?>... additionalSources) throws Exception {
        tls();
        String run = UUID.randomUUID().toString().replace("-", "");
        Path directory = ROOT.resolve("app-" + run);
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var settings = new Settings(mode, host, port, origin, "ph1_" + run, directory);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("server.address", host);
        properties.put("server.port", Integer.toString(port));
        properties.put("server.tomcat.basedir", directory.resolve("tomcat").toString());
        properties.put("server.forward-headers-strategy", "none");
        properties.put("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55465/" + requiredDatabase());
        properties.put("spring.datasource.username", "private_https_origin_qa");
        properties.put("spring.datasource.password", Files.readString(ROOT.resolve("private/db.password")).strip());
        for (String property : List.of("spring.flyway.schemas", "spring.flyway.default-schema",
                "spring.jpa.properties.hibernate.default_schema", "spring.datasource.hikari.schema")) {
            properties.put(property, settings.schema());
        }
        properties.put("changeo.mfa-key", directory.resolve("mfa.key").toString());
        properties.put("changeo.mailbox", directory.resolve("mailbox").toString());
        properties.put("changeo.files-root", directory.resolve("artifacts").toString());
        for (String property : List.of("changeo.synthetic-evidence", "changeo.synthetic-moderation", "changeo.moderation-worker",
                "changeo.notification-worker", "changeo.synthetic-payments", "changeo.payments-worker",
                "changeo.synthetic-subscriptions", "changeo.subscription-worker", "changeo.real-money")) {
            properties.put(property, "false");
        }
        if (mode == Mode.DIRECT) {
            properties.put("changeo.private-https.origin", origin);
            properties.put("server.ssl.enabled", "true");
            properties.put("server.ssl.key-store", ROOT.resolve("tls/server.p12").toUri().toString());
            properties.put("server.ssl.key-store-type", "PKCS12");
            properties.put("server.ssl.key-store-password", Files.readString(ROOT.resolve("tls/password.private")).strip());
        }
        properties.putAll(overrides);
        var application = new SpringApplication(ChangeoApplication.class, FixtureConfiguration.class);
        application.addPrimarySources(List.of(additionalSources));
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("ph1Settings", settings));
        application.setRegisterShutdownHook(false);
        String[] arguments = properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return new App(application.run(arguments), settings);
    }

    static final class App implements AutoCloseable {
        final ConfigurableApplicationContext context;
        final Settings settings;
        final Counters counters;

        App(ConfigurableApplicationContext context, Settings settings) {
            this.context = context;
            this.settings = settings;
            this.counters = context.getBean(Counters.class);
        }

        Account actor() {
            String contact = "ph1-" + UUID.randomUUID() + "@example.test";
            UUID id = context.getBean(AccountService.class).register(contact, PASSWORD).accountId().value();
            return new Account(id, contact, PASSWORD, null);
        }

        Account operator() throws Exception {
            String contact = "ph1-operator-" + UUID.randomUUID() + "@example.test";
            Path enrollment = settings.directory().resolve("operator.private");
            context.getBean(OperatorService.class).provision(contact, Set.of("IDENTITY_ADMIN", "AUDIT_READER"), enrollment);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(enrollment)) {
                int equals = line.indexOf('=');
                if (equals > 0) { values.put(line.substring(0, equals), line.substring(equals + 1)); }
            }
            return new Account(UUID.fromString(values.get("account")), contact, values.get("password"), values.get("totpBase32"));
        }

        Map<String, String> rows(boolean identity) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Map<String, String> result = new LinkedHashMap<>();
            var tables = jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() order by tablename", String.class);
            for (String table : tables) {
                assertThat(table).matches("[a-z0-9_]+");
                if (identity || !table.startsWith("identity_")) {
                    result.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from " + table + " t", String.class));
                }
            }
            return Map.copyOf(result);
        }

        TestClock clock() { return context.getBean(TestClock.class); }

        @Override
        public void close() throws IOException {
            context.close();
            assertThat(context.isActive()).isFalse();
            assertThat(context.getBeanFactory().getSingleton("dataSource") == null
                    || ((HikariDataSource) context.getBeanFactory().getSingleton("dataSource")).isClosed()).isTrue();
            Files.writeString(settings.directory().resolve("context-closed.private"), "native Spring context close completed\n");
        }
    }

    record Account(UUID id, String contact, String password, String factor) {}

    static final class Counters implements HttpSessionListener, HttpSessionIdListener {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger downstream = new AtomicInteger();
        final AtomicInteger sessions = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        final AtomicInteger rotations = new AtomicInteger();
        final AtomicReference<String> remote = new AtomicReference<>();

        List<Integer> effects() {
            return List.of(acquired.get(), downstream.get(), sessions.get(), destroyed.get(), rotations.get());
        }

        @Override public void sessionCreated(HttpSessionEvent event) { sessions.incrementAndGet(); }
        @Override public void sessionDestroyed(HttpSessionEvent event) { destroyed.incrementAndGet(); }
        @Override public void sessionIdChanged(HttpSessionEvent event, String previous) { rotations.incrementAndGet(); }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean Counters ph1Counters() { return new Counters(); }
        @Bean @Primary TestClock ph1Clock() { return new TestClock(); }

        @Bean
        static BeanPostProcessor ph1PoolMetrics(Counters counters) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof HikariDataSource pool) {
                        pool.setMetricsTrackerFactory((poolName, statistics) -> new IMetricsTracker() {
                            @Override public void recordConnectionAcquiredNanos(long elapsed) { counters.acquired.incrementAndGet(); }
                        });
                    }
                    return bean;
                }
            };
        }

        @Bean
        ServletListenerRegistrationBean<Counters> ph1SessionListener(Counters counters) {
            return new ServletListenerRegistrationBean<>(counters);
        }

        @Bean
        FilterRegistrationBean<Filter> ph1Observation(Counters counters) {
            Filter observer = (request, response, chain) -> {
                counters.downstream.incrementAndGet();
                counters.remote.set(request.getRemoteAddr());
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(observer);
            registration.setOrder(-119);
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ERROR,
                    jakarta.servlet.DispatcherType.ASYNC);
            return registration;
        }

        @Bean
        FilterRegistrationBean<PrivateHttpsOriginFilter> ph1ComponentPolicy(Settings settings) {
            var registration = new FilterRegistrationBean<>(new PrivateHttpsOriginFilter(PrivateHttpsOrigin.parse(settings.origin())));
            registration.setEnabled(settings.mode() == Mode.PROXY || settings.mode() == Mode.HTTP_COMPONENT);
            registration.setOrder(-120);
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ERROR,
                    jakarta.servlet.DispatcherType.ASYNC);
            return registration;
        }

        @Bean
        org.springframework.boot.web.servlet.ServletRegistrationBean<jakarta.servlet.http.HttpServlet> ph1NativeDispatch() {
            jakarta.servlet.http.HttpServlet servlet = new jakarta.servlet.http.HttpServlet() {
                @Override
                protected void doGet(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response)
                        throws ServletException {
                    if (request.getPathInfo().equals("/async")) {
                        request.startAsync().dispatch("/account");
                    } else {
                        throw new ServletException("supplied-private-exception-marker");
                    }
                }
            };
            var registration = new org.springframework.boot.web.servlet.ServletRegistrationBean<>(servlet, "/ph1-native/*");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> ph1NativeProxy(Settings settings) {
            return factory -> {
                if (settings.mode() != Mode.PROXY) { return; }
                var peer = new RemoteCIDRValve();
                peer.setAllow("127.0.0.1/32");
                peer.setDeny("");
                peer.setUsePeerAddress(true);
                peer.setAddConnectorPort(false);
                peer.setInvalidAuthenticationWhenDeny(false);
                peer.setDenyStatus(403);
                var remote = new RemoteIpValve();
                remote.setInternalProxies("127\\.0\\.0\\.1");
                remote.setTrustedProxies("");
                remote.setRemoteIpHeader("X-Forwarded-For");
                remote.setProtocolHeader("X-Forwarded-Proto");
                remote.setProtocolHeaderHttpsValue("https");
                remote.setHttpsServerPort(EDGE_PORT);
                remote.setHostHeader(null);
                remote.setPortHeader(null);
                remote.setChangeLocalName(false);
                remote.setChangeLocalPort(false);
                factory.addEngineValves(peer, new ForwardingGate(), remote);
                factory.addConnectorCustomizers(connector -> connector.setEnableLookups(false));
                factory.addContextCustomizers(context -> {
                    var reports = new ErrorReportValve();
                    reports.setShowReport(false);
                    reports.setShowServerInfo(false);
                    context.getParent().getPipeline().addValve(reports);
                });
            };
        }
    }

    static final class ForwardingGate extends ValveBase {
        ForwardingGate() { super(true); }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            String forwardedFor = one(request.getHeaders("X-Forwarded-For"));
            String protocol = one(request.getHeaders("X-Forwarded-Proto"));
            boolean valid = "https".equals(protocol) && validChain(forwardedFor);
            var names = request.getHeaderNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement().toLowerCase(Locale.ROOT);
                if (name.equals("forwarded") || name.startsWith("x-forwarded-")
                        && !name.equals("x-forwarded-for") && !name.equals("x-forwarded-proto")) { valid = false; }
            }
            if (!valid) {
                response.setStatus(403);
                response.setContentType("text/plain;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.getWriter().write("forbidden\n");
                return;
            }
            getNext().invoke(request, response);
        }

        private static String one(java.util.Enumeration<String> values) {
            if (values == null || !values.hasMoreElements()) { return null; }
            String value = values.nextElement();
            return values.hasMoreElements() ? null : value;
        }

        private static boolean validChain(String value) {
            if (value == null || value.isEmpty() || value.length() > 256) { return false; }
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) < 32 || value.charAt(index) > 126) { return false; }
            }
            String[] addresses = value.split(",", -1);
            if (addresses.length > 4) { return false; }
            for (String address : addresses) {
                String literal = address.strip();
                if (!literal.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) { return false; }
                try {
                    if (!(InetAddress.ofLiteral(literal) instanceof java.net.Inet4Address)) { return false; }
                } catch (IllegalArgumentException failure) {
                    return false;
                }
            }
            return true;
        }
    }

    static final class Browser {
        final int port;
        final boolean secure;
        final String source;
        final String authority;
        final List<String> transportHeaders;
        String cookie;

        Browser(int port, boolean secure, String source) {
            this(port, secure, source, "127.0.0.1:" + port, List.of());
        }

        Browser(int port, boolean secure, String source, String authority, List<String> transportHeaders) {
            this.port = port;
            this.secure = secure;
            this.source = source;
            this.authority = authority;
            this.transportHeaders = List.copyOf(transportHeaders);
        }

        Reply get(String path) throws Exception { return request("GET", path, List.of(), ""); }

        Reply post(String path, String form) throws Exception {
            return request("POST", path, List.of("Content-Type: application/x-www-form-urlencoded"), form);
        }

        Reply request(String method, String path, List<String> headers, String body) throws Exception {
            List<String> complete = new ArrayList<>();
            complete.add("Host: " + authority);
            complete.addAll(transportHeaders);
            if (cookie != null) { complete.add("Cookie: " + cookie); }
            complete.addAll(headers);
            Reply reply = wire(port, secure, source, method + " " + path + " HTTP/1.1", complete, body);
            for (String value : reply.headers().getOrDefault("set-cookie", List.of())) {
                if (value.startsWith("JSESSIONID=")) { cookie = value.substring(0, value.indexOf(';')); }
            }
            return reply;
        }

        String csrf(String page) throws Exception {
            Reply reply = get(page);
            assertThat(reply.status()).isEqualTo(200);
            var matcher = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(reply.body());
            assertThat(matcher.find()).as("real server-rendered synchronizer token").isTrue();
            return matcher.group(1);
        }

        Reply login(Account account) throws Exception {
            return post("/login", form(Map.of("username", account.contact(), "password", account.password(), "_csrf", csrf("/login"))));
        }

    }

    record Reply(int status, Map<String, List<String>> headers, byte[] bytes) {
        String body() { return new String(bytes, StandardCharsets.UTF_8); }
        String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of("")).getFirst(); }
    }

    static Reply wire(int port, boolean secure, String source, String requestLine, List<String> headers, String body) throws Exception {
        return wireTo("127.0.0.1", port, secure, source, requestLine, headers, body);
    }

    static Reply wireTo(String target, int port, boolean secure, String source, String requestLine, List<String> headers, String body) throws Exception {
        try (Socket socket = secure ? tls().getSocketFactory().createSocket() : new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.ofLiteral(source), 0));
            socket.connect(new InetSocketAddress(InetAddress.ofLiteral(target), port), 2000);
            socket.setSoTimeout(2000);
            if (socket instanceof SSLSocket ssl) {
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String message = requestLine + "\r\n" + String.join("\r\n", headers)
                    + "\r\nConnection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n";
            socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            return readReply(socket.getInputStream());
        }
    }

    static Reply readReply(InputStream input) throws IOException {
        String status = line(input, 4096);
        if (status == null || !status.matches("HTTP/1\\.[01] [0-9]{3}.*")) { throw new EOFException("No completed HTTP response"); }
        Map<String, List<String>> headers = headers(input);
        byte[] bytes;
        if (headers.containsKey("transfer-encoding")) {
            if (!headers.get("transfer-encoding").equals(List.of("chunked")) || headers.containsKey("content-length")) {
                throw new IOException("Invalid response framing");
            }
            var body = new ByteArrayOutputStream();
            while (true) {
                String size = line(input, 128);
                int length = responseLength(size.split(";", 2)[0], 16);
                if (length == 0) { headers(input); break; }
                if (length < 0 || length > MAX_RESPONSE - body.size()) { throw new IOException("Response exceeds fixture bound"); }
                body.write(exact(input, length));
                if (!"".equals(line(input, 2))) { throw new IOException("Invalid chunk terminator"); }
            }
            bytes = body.toByteArray();
        } else if (headers.containsKey("content-length")) {
            if (headers.get("content-length").size() != 1) { throw new IOException("Duplicate response framing"); }
            int length = responseLength(headers.get("content-length").getFirst(), 10);
            if (length < 0 || length > MAX_RESPONSE) { throw new IOException("Response exceeds fixture bound"); }
            bytes = exact(input, length);
        } else {
            bytes = input.readNBytes(MAX_RESPONSE + 1);
            if (bytes.length > MAX_RESPONSE) { throw new IOException("Response exceeds fixture bound"); }
        }
        return new Reply(Integer.parseInt(status.substring(9, 12)), Map.copyOf(headers), bytes);
    }

    private static int responseLength(String value, int radix) throws IOException {
        try { return Integer.parseInt(value, radix); }
        catch (NumberFormatException failure) { throw new IOException("Invalid response framing"); }
    }

    static Map<String, List<String>> headers(InputStream input) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int total = 0;
        for (int count = 0; count < 100; count++) {
            String line = line(input, 8192);
            if (line == null) { throw new EOFException("Incomplete headers"); }
            if (line.isEmpty()) { return headers; }
            total += line.length();
            int colon = line.indexOf(':');
            if (total > 16384 || colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IOException("Invalid bounded headers");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(line.substring(colon + 1).strip());
        }
        throw new IOException("Too many headers");
    }

    static String line(InputStream input, int maximum) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int count = 0; count <= maximum; count++) {
            int value = input.read();
            if (value == -1) { return bytes.size() == 0 ? null : incomplete(); }
            if (value == '\r') {
                if (input.read() != '\n') { throw new IOException("Invalid line ending"); }
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            if (value < 32 || value > 126) { throw new IOException("Invalid header byte"); }
            bytes.write(value);
        }
        throw new IOException("Header exceeds fixture bound");
    }

    private static String incomplete() throws EOFException { throw new EOFException("Incomplete line"); }

    static byte[] exact(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) { throw new EOFException("Incomplete body"); }
        return bytes;
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }

    static String totp(Account account, TestClock clock) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var bytes = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : account.factor().toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(character);
            bits += 5;
            if (bits >= 8) { bits -= 8; bytes.write((buffer >> bits) & 255); }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(bytes.toByteArray(), "HmacSHA1"));
        byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond() / 30).array());
        int value = ByteBuffer.wrap(digest, digest[digest.length - 1] & 15, 4).getInt() & 0x7fffffff;
        return String.format(Locale.ROOT, "%06d", value % 1000000);
    }

    static synchronized SSLContext tls() throws Exception {
        Path key = ROOT.resolve("tls/server.p12");
        Path passwordFile = ROOT.resolve("tls/password.private");
        if (!Files.exists(key)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Files.writeString(passwordFile, password + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                    "-keystore", key.toString(), "-storepass:file", passwordFile.toString(), "-dname", "CN=Private Loopback Fixture",
                    "-ext", "SAN=ip:127.0.0.1,ip:::1", "-validity", "2", "-noprompt");
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ROOT.resolve("private/keytool.log").toFile()).start();
            int exit = process.waitFor();
            Files.writeString(ROOT.resolve("private/keytool.exit"), Integer.toString(exit));
            assertThat(exit).isZero();
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        }
        char[] password = Files.readString(passwordFile).strip().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(key)) { store.load(input, password); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static Path requiredRoot() {
        String value = System.getProperty("ph1.root");
        if (value == null) { throw new IllegalStateException("Explicit private PH1 test root required"); }
        Path root = Path.of(value).toAbsolutePath().normalize();
        if (!root.getFileName().toString().equals("private-https-origin-proxy-20261020")
                || !root.getParent().getFileName().toString().equals(".runtime") || Files.isSymbolicLink(root)) {
            throw new IllegalStateException("Invalid private PH1 test root");
        }
        return root;
    }

    private static String accountPassword() {
        Path file = requiredRoot().resolve("private/account-password.private");
        try {
            if (!Files.exists(file)) {
                byte[] bytes = new byte[24];
                new SecureRandom().nextBytes(bytes);
                Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            return Files.readString(file);
        } catch (IOException failure) {
            throw new IllegalStateException("Private PH1 account credential unavailable");
        }
    }

    private static String requiredDatabase() {
        String database = System.getProperty("ph1.database", "private_https_origin_qa");
        if (!Set.of("private_https_origin_qa", "private_https_origin_review_qa").contains(database)) {
            throw new IllegalStateException("Invalid private PH1 database");
        }
        return database;
    }
}
