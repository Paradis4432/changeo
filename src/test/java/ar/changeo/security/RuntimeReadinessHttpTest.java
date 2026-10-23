package ar.changeo.security;

import ar.changeo.identity.AccountService;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import static org.assertj.core.api.Assertions.*;

class RuntimeReadinessHttpTest {
    private static final CountDownLatch RUNNER = new CountDownLatch(1);
    private static final CountDownLatch FINISH = new CountDownLatch(1);
    private static final AtomicInteger PORT = new AtomicInteger();
    private static final AtomicInteger DETAILS_CREATED = new AtomicInteger();

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRejectLazyPoolInitializationBeforeNetworking(long timeout) throws Exception {
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var properties = Map.<String, Object>of(
                    "spring.datasource.url", "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/runtime_readiness_qa",
                    "spring.datasource.hikari.initialization-fail-timeout", timeout);
            assertThatThrownBy(() -> SessionTransportHttpTest.start(SessionTransportHttpTest.privateRoot(), "lazy_" + SessionTransportHttpTest.random(), properties))
                    .hasRootCauseMessage("Unsupported database runtime configuration");
            listener.setSoTimeout(150);
            assertThatThrownBy(listener::accept).isInstanceOf(java.net.SocketTimeoutException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "set", "close"})
    void shouldFailNativeInitialSetupAndRecoverOnlyWithFreshContext(String fault) throws Exception {
        var root = SessionTransportHttpTest.privateRoot();
        String schema = "initial_failure_" + SessionTransportHttpTest.random();
        String url = System.getenv("CHANGEO_DATABASE_URL");
        Class<?> hook = switch (fault) {
            case "read" -> ar.changeo.config.RuntimeReadinessPostgresTest.FailingInitialRead.class;
            case "set" -> ar.changeo.config.RuntimeReadinessPostgresTest.FailingPoolSetup.class;
            case "close" -> ar.changeo.config.RuntimeReadinessPostgresTest.FailingSetupAndClose.class;
            default -> throw new IllegalArgumentException(fault);
        };
        String faulty = url + (url.contains("?") ? "&" : "?") + "sslmode=disable&gssEncMode=disable&socketFactory=" + hook.getName();
        int sockets = ar.changeo.config.RuntimeReadinessPostgresTest.FaultSockets.createdSockets().size();
        long workers = poolWorkers();
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> SessionTransportHttpTest.start(root, schema, Map.of("spring.datasource.url", faulty)))
                    .hasStackTraceContaining("Database initialization failed");
            assertThat(ar.changeo.config.RuntimeReadinessPostgresTest.elapsed("native-" + fault + "-context-failure", started)).isLessThan(Duration.ofSeconds(5));
            assertThat(ar.changeo.config.RuntimeReadinessPostgresTest.FaultSockets.createdSockets()).hasSize(sockets + 1);
            assertThat(poolWorkers()).isLessThanOrEqualTo(workers);
        } finally {
            for (var socket : ar.changeo.config.RuntimeReadinessPostgresTest.FaultSockets.createdSockets()) { socket.close(); }
        }
        assertThat(ar.changeo.config.RuntimeReadinessPostgresTest.FaultSockets.createdSockets()).allMatch(java.net.Socket::isClosed);
        try (var context = SessionTransportHttpTest.start(root, schema, Map.of())) {
            int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) context).getWebServer().getPort();
            try (var browser = new SessionTransportHttpTest.Browser(port)) { assertResponse(browser.get("/readyz"), 200); }
            var pool = context.getBean(HikariDataSource.class);
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                assertThat(connection.getNetworkTimeout()).isZero();
                long query = System.nanoTime();
                statement.execute("select pg_sleep(2.2)");
                assertThat(ar.changeo.config.RuntimeReadinessPostgresTest.elapsed("fresh-context-ordinary-query", query)).isGreaterThan(Duration.ofSeconds(2));
            }
            observedLock(pool);
        }
    }

    private static long poolWorkers() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(thread -> thread.getName().contains("housekeeper") || thread.getName().contains("connection-adder")
                        || thread.getName().contains("connection-closer")).count();
    }

    private static void observedLock(HikariDataSource pool) throws Exception {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(pool);
        jdbc.execute("create table rt1_recovery_lock (id integer primary key, value integer not null)");
        jdbc.update("insert into rt1_recovery_lock values(1,0)");
        try (var blocker = pool.getConnection(); var waiter = pool.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) { statement.executeUpdate("update rt1_recovery_lock set value=value+1 where id=1"); }
            int pid;
            try (var statement = waiter.createStatement(); var result = statement.executeQuery("select pg_backend_pid()")) {
                assertThat(result.next()).isTrue();
                pid = result.getInt(1);
            }
            var waiting = executor.submit(() -> {
                try (var statement = waiter.createStatement()) { return statement.executeUpdate("update rt1_recovery_lock set value=value+1 where id=1"); }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0", Boolean.class, pid))
                        && System.nanoTime() < deadline) { Thread.sleep(10); }
                assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0", Boolean.class, pid)).isTrue();
                TimeUnit.MILLISECONDS.sleep(2200);
                assertThat(waiting.isDone()).isFalse();
            } finally { blocker.commit(); }
            assertThat(waiting.get(2, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(waiter.getNetworkTimeout()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "multiple", "prototype"})
    void shouldRejectEveryDeclaredConnectionDetailsOwnerBeforeNetworking(String kind) throws Exception {
        Class<?> source = switch (kind) {
            case "single" -> SingleDetails.class;
            case "multiple" -> MultipleDetails.class;
            case "prototype" -> PrototypeDetails.class;
            default -> throw new IllegalArgumentException(kind);
        };
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var root = SessionTransportHttpTest.privateRoot();
            var properties = Map.<String, Object>of("spring.datasource.url", "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/runtime_readiness_qa");
            int created = DETAILS_CREATED.get();
            assertThatThrownBy(() -> SessionTransportHttpTest.start(root, "details_" + SessionTransportHttpTest.random(), properties, source))
                    .hasRootCauseMessage("Unsupported database runtime configuration");
            listener.setSoTimeout(150);
            assertThatThrownBy(listener::accept).isInstanceOf(java.net.SocketTimeoutException.class);
            assertThat(DETAILS_CREATED.get()).isEqualTo(created);
        }
    }

    @Test
    void shouldFollowNativeStartupReadinessDrainAndClosureWithoutSessions() throws Exception {
        var root = SessionTransportHttpTest.privateRoot();
        var active = new java.util.concurrent.atomic.AtomicReference<org.springframework.context.ConfigurableApplicationContext>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var starting = executor.submit(() -> {
                var context = SessionTransportHttpTest.start(root, "readiness_http_" + SessionTransportHttpTest.random(), Map.of(), LifecycleFixture.class);
                active.set(context);
                return context;
            });
            try {
                assertThat(RUNNER.await(30, TimeUnit.SECONDS)).isTrue();
                assertResponse(request("GET", null), 503);
            } finally { FINISH.countDown(); }
            int port;
            try (var context = starting.get(30, TimeUnit.SECONDS)) {
                assertThat(context.getBeansOfType(JdbcConnectionDetails.class)).isEmpty();
                port = PORT.get();
                assertResponse(request("GET", null), 200);
                assertResponse(request("GET", "JSESSIONID=stale-runtime-cookie"), 200);
                var account = context.getBean(AccountService.class);
                String contact = "readiness-" + SessionTransportHttpTest.random() + "@example.test";
                String password = "Synthetic-runtime-password-12345";
                account.register(contact, password);
                var browser = new SessionTransportHttpTest.Browser(port);
                try {
                assertThat(browser.post("/login", browser.get("/login").body(), Map.of("username", contact, "password", password)).statusCode()).isEqualTo(302);
                String cookie = "JSESSIONID=" + browser.session();
                assertResponse(request("GET", cookie), 200);
                assertThat(browser.get("/account").statusCode()).isEqualTo(200);
                var pool = context.getBean(HikariDataSource.class);
                var leases = new ArrayList<Connection>();
                try {
                    for (int index = 0; index < pool.getMaximumPoolSize(); index++) { leases.add(pool.getConnection()); }
                    for (String supplied : new String[]{"", "JSESSIONID=stale-runtime-cookie", cookie}) {
                        long started = System.nanoTime();
                        assertResponse(request("GET", supplied.isEmpty() ? null : supplied), 503);
                        assertThat(ar.changeo.config.RuntimeReadinessPostgresTest.elapsed("http-cookie-pool-saturation", started)).isLessThan(Duration.ofSeconds(2));
                    }
                } finally { for (var lease : leases) { lease.close(); } }
                assertResponse(request("GET", cookie), 200);
                try (var callers = Executors.newFixedThreadPool(8)) {
                    var responses = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
                    for (int index = 0; index < 32; index++) { responses.add(callers.submit(() -> request("GET", cookie))); }
                    for (var response : responses) {
                        var received = response.get(5, TimeUnit.SECONDS);
                        assertThat(received.statusCode()).isIn(200, 503);
                        assertResponse(received, received.statusCode());
                    }
                }
                assertThat(request("HEAD", null).statusCode()).isEqualTo(302);
                assertThat(request("POST", null).statusCode()).isEqualTo(403);
                AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
                assertResponse(request("GET", cookie), 503);
                AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
                assertResponse(request("GET", cookie), 200);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                } finally { browser.close(); }
            }
            try (var socket = new java.net.Socket()) {
                assertThatThrownBy(() -> socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500)).isInstanceOf(java.net.ConnectException.class);
            }
        } finally {
            FINISH.countDown();
            if (active.get() != null) { active.get().close(); }
        }
    }

    private static HttpResponse<String> request(String method, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT.get() + "/readyz"))
                .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody());
        if (cookie != null) { request.header("Cookie", cookie); }
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertResponse(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo(status == 200 ? "ready\n" : "unavailable\n");
        assertThat(response.headers().firstValue("content-type").orElseThrow()).isEqualToIgnoringCase("text/plain;charset=UTF-8");
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
        assertThat(response.headers().firstValue("location")).isEmpty();
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LifecycleFixture {
        @Bean
        ApplicationListener<WebServerInitializedEvent> listening() { return event -> PORT.set(event.getWebServer().getPort()); }

        @Bean
        ApplicationRunner pendingStartup() {
            return arguments -> {
                RUNNER.countDown();
                if (!FINISH.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("Private startup latch timed out"); }
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SingleDetails {
        @Bean JdbcConnectionDetails explicitDetails() { return details(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MultipleDetails {
        @Bean JdbcConnectionDetails firstExplicitDetails() { return details(); }
        @Bean JdbcConnectionDetails secondExplicitDetails() { return details(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrototypeDetails {
        @Bean @Scope("prototype") JdbcConnectionDetails prototypeDetails() { return details(); }
    }

    private static JdbcConnectionDetails details() {
        DETAILS_CREATED.incrementAndGet();
        return new JdbcConnectionDetails() {
            @Override public String getUsername() { return "runtime_readiness_qa"; }
            @Override public String getPassword() { return "unused-explicit-owner"; }
            @Override public String getJdbcUrl() { return "jdbc:postgresql://127.0.0.1:1/runtime_readiness_qa"; }
        };
    }

}
