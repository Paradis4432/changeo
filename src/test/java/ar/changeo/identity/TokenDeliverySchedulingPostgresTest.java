package ar.changeo.identity;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.api.ActorId;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import ar.changeo.notifications.NotificationService;
import ar.changeo.notifications.api.NoticeOwner;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {ChangeoApplication.class, TokenDeliverySchedulingPostgresTest.Notices.class},
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TokenDeliverySchedulingPostgresTest {
    private static final String MODE = required("ES1_TOKEN_MODE");
    private static final String SCHEMA = "es1_token_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path MATERIAL = Path.of(required("ES1_PRIVATE_ROOT"), SCHEMA);
    private static final ContentReference REFERENCE = new ContentReference(SurfaceKind.PUBLIC_POST, UUID.randomUUID(), UUID.randomUUID());
    private static final AtomicReference<UUID> RECIPIENT = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicBoolean COMMITTED = new AtomicBoolean();
    private static final AtomicReference<JdbcTemplate> DATABASE = new AtomicReference<>();
    private static HttpServer server;
    private static ExecutorService http;
    private static ConfigurableApplicationContext observedContext;
    private static List<ScheduledThreadPoolExecutor> pools = List.of();
    private static long started;
    @Autowired ConfigurableApplicationContext context;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @MockitoSpyBean(reset = MockReset.BEFORE) NotificationService notices;
    private static final List<String> callbacks = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Notices {
        @Bean NoticeOwner schedulingNoticeOwner() {
            return new NoticeOwner() {
                @Override public Set<SurfaceKind> noticeKinds() { return Set.of(SurfaceKind.PUBLIC_POST); }
                @Override public boolean available(ContentReference reference, ActorId recipient) {
                    return REFERENCE.equals(reference) && recipient.value().equals(RECIPIENT.get());
                }
            };
        }
    }

    @BeforeAll
    static void prepareFixture() throws Exception {
        if (!Set.of("on", "omitted", "false", "local", "defaults", "worker-without-mode").contains(MODE)) { throw new IllegalArgumentException("Unknown scheduling case"); }
        String role = required("ES1_ROLE");
        if (!Set.of("writer", "reviewer").contains(role) || !required("CHANGEO_DATABASE_URL").equals("jdbc:postgresql://127.0.0.1:55460/email_delivery_schedule_" + role)) {
            throw new IllegalStateException("Exact scheduling database required");
        }
        Path expected = Path.of("/home/paradis/changeo/.runtime/email-delivery-preparation-20261019/scheduling-current-20261020", role, "material");
        if (!MATERIAL.getParent().toAbsolutePath().normalize().equals(expected) || Files.isSymbolicLink(expected)
                || !Files.getPosixFilePermissions(expected).equals(PosixFilePermissions.fromString("rwx------"))) { throw new IllegalStateException("Exact private scheduling root required"); }
        Files.createDirectory(MATERIAL, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Set.of("on", "omitted", "false").contains(MODE)) {
            Path spool = MATERIAL.resolve("spool"); Files.createDirectory(spool, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key); privateFile(MATERIAL.resolve("spool.key"), key);
            privateFile(MATERIAL.resolve("credential"), ("synthetic-" + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(http);
            server.createContext("/emails", exchange -> {
                try {
                    REQUESTS.incrementAndGet();
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    assertThat(exchange.getRequestBody().readNBytes(8193)).hasSizeLessThanOrEqualTo(8192);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    String keyHeader = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    UUID id = UUID.fromString(keyHeader.substring("identity-token/".length()));
                    JdbcTemplate database = DATABASE.get();
                    assertThat(database.queryForObject("select state from identity_token_delivery where id=?", String.class, id)).isEqualTo("DISPATCHING");
                    database.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                        try (var statement = connection.prepareStatement("select a.id from identity_account a join identity_token_delivery d on d.account_id=a.id where d.id=? for update of a nowait")) {
                            statement.setObject(1, id); statement.executeQuery().close();
                        }
                        return null;
                    });
                    COMMITTED.set(true);
                    byte[] response = ("{\"id\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            }); server.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.profiles.active", () -> "sandbox");
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("changeo.real-money", () -> "false");
        properties.add("spring.datasource.url", () -> required("CHANGEO_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> required("CHANGEO_DATABASE_USER"));
        properties.add("spring.datasource.password", () -> required("CHANGEO_DATABASE_PASSWORD"));
        for (String name : List.of("spring.flyway.schemas", "spring.flyway.default-schema", "spring.datasource.hikari.schema", "spring.jpa.properties.hibernate.default_schema")) { properties.add(name, () -> SCHEMA); }
        properties.add("changeo.mfa-key", () -> MATERIAL.resolve("mfa.key").toString());
        properties.add("changeo.mailbox", () -> MATERIAL.resolve("mailbox").toString());
        properties.add("changeo.files-root", () -> MATERIAL.resolve("artifacts").toString());
        for (String name : List.of("changeo.synthetic-subscriptions", "changeo.subscription-worker")) { properties.add(name, () -> "false"); }
        if (!MODE.equals("defaults")) {
            for (String name : List.of("changeo.synthetic-moderation", "changeo.moderation-worker", "changeo.synthetic-payments")) { properties.add(name, () -> "false"); }
        }
        if (!MODE.equals("defaults")) { properties.add("changeo.notification-worker", () -> "true"); }
        if (MODE.equals("worker-without-mode")) { properties.add("changeo.payments-worker", () -> "true"); }
        if (Set.of("on", "omitted", "false").contains(MODE)) {
            Path spool = MATERIAL.resolve("spool");
            properties.add("changeo.token-delivery", () -> "resend-preparation");
            properties.add("changeo.token-delivery-spool", spool::toString);
            properties.add("changeo.token-delivery-key", () -> MATERIAL.resolve("spool.key").toString());
            properties.add("changeo.token-delivery-credential", () -> MATERIAL.resolve("credential").toString());
            properties.add("changeo.token-delivery-sender", () -> "sender@example.test");
            properties.add("changeo.token-delivery-endpoint", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        }
        if (Set.of("on", "false", "local").contains(MODE)) { properties.add("changeo.token-delivery-worker", () -> MODE.equals("false") ? "false" : "true"); }
    }

    @Test
    void shouldUseNativeTokenSchedulingAndPreserveIndependentOffAndLocalModes() throws Exception {
        observedContext = context;
        pools = context.getBeansOfType(ThreadPoolTaskScheduler.class).values().stream().map(ThreadPoolTaskScheduler::getScheduledThreadPoolExecutor).toList();
        started = System.nanoTime();
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(2));
        DATABASE.set(jdbc);
        doAnswer(invocation -> { callbacks.add(Thread.currentThread().getName()); return invocation.callRealMethod(); }).when(notices).runOnce();
        var registration = accounts.register("native" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345");
        UUID account = registration.accountId().value(); assertThat(RECIPIENT.compareAndSet(null, account)).isTrue();
        if (!MODE.equals("defaults")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> notices.enqueue(REFERENCE, Set.of(new ActorId(account))));
        }
        if (MODE.equals("on")) {
            await(() -> "ACCEPTED".equals(state(account)), Duration.ofSeconds(12));
            assertThat(COMMITTED).isTrue(); assertThat(REQUESTS).hasValue(1); assertThat(registration.delivered()).isFalse();
            assertThat(jdbc.queryForObject("select attempts from identity_token_delivery where account_id=?", Integer.class, account)).isEqualTo(1);
        } else {
            if (!MODE.equals("defaults")) { await(() -> delivered() == 1, Duration.ofSeconds(12)); }
            int controlBefore = callbacks.size();
            observeNoRequestFor(Duration.ofMillis(6500));
            if (!MODE.equals("defaults")) { assertThat(callbacks.size()).isGreaterThan(controlBefore); }
            assertThat(REQUESTS).hasValue(0);
            if (Set.of("omitted", "false").contains(MODE)) {
                assertThat(state(account)).isEqualTo("PENDING"); assertThat(registration.delivered()).isFalse();
                assertThat(jdbc.queryForObject("select attempts from identity_token_delivery where account_id=?", Integer.class, account)).isZero();
            } else {
                assertThat(registration.delivered()).isTrue(); assertThat(context.getBeansOfType(TokenDeliveryWorker.class)).isEmpty();
                assertThat(jdbc.queryForObject("select count(*) from identity_token_delivery", Integer.class)).isZero();
            }
        }
        assertThat(jdbc.queryForObject("select consumed from identity_contact_token where account_id=?", Boolean.class, account)).isFalse();
        if (!MODE.equals("defaults")) { await(() -> delivered() == 1, Duration.ofSeconds(12)); assertThat(callbacks).isNotEmpty(); }
        else { assertThat(callbacks).isEmpty(); }
        assertThat(context.getBeansOfType(ar.changeo.payments.PaymentWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(ar.changeo.payments.gateway.FinancialGateway.class)).isEmpty();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'payments_%' or tablename like 'subscriptions_%') order by tablename", String.class)) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        }
        var flyway = context.getBean(org.flywaydb.core.Flyway.class);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        flyway.validate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @AfterAll
    static void closeOwnedResources() throws Exception {
        var acquired = new AtomicReference<ConfigurableApplicationContext>(observedContext);
        try (AutoCloseable closeExecutor = () -> { if (http != null) { http.close(); assertThat(http.isTerminated()).isTrue(); } };
                AutoCloseable stopProvider = () -> { if (server != null) { server.stop(0); } };
                AutoCloseable closeContext = () -> { var app = acquired.get(); if (app != null && app.isActive()) { app.close(); } }) {
            var cached = new TestContextManager(TokenDeliverySchedulingPostgresTest.class).getTestContext();
            if (cached.hasApplicationContext()) {
                var app = (ConfigurableApplicationContext) cached.getApplicationContext();
                if (observedContext != null) { assertThat(app).isSameAs(observedContext); }
                acquired.set(app);
                pools = app.getBeansOfType(ThreadPoolTaskScheduler.class).values().stream().map(ThreadPoolTaskScheduler::getScheduledThreadPoolExecutor).toList();
                cached.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
            } else { assertThat(observedContext).as("Established context must remain cached until class teardown").isNull(); }
            var app = acquired.get();
            if (app == null) { return; }
            assertThat(app.isActive()).isFalse();
            assertThat(cached.hasApplicationContext()).isFalse();
            for (var pool : pools) { assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
            int requests = REQUESTS.get(), ticks = callbacks.size();
            TimeUnit.MILLISECONDS.sleep(3200);
            assertThat(REQUESTS).hasValue(requests); assertThat(callbacks).hasSize(ticks);
            if (observedContext != null) {
                privateFile(MATERIAL.resolve("observation.private"), ("mode=" + MODE + "\nelapsedMillis=" + Duration.ofNanos(System.nanoTime() - started).toMillis()
                        + "\nrequests=" + requests + "\nnoticeCallbacks=" + ticks + "\nschedulerThreads=" + new TreeSet<>(callbacks)
                        + "\nbodyObserved=true\ncontextsClosed=" + !app.isActive() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private String state(UUID account) { return jdbc.queryForObject("select state from identity_token_delivery where account_id=?", String.class, account); }
    private int delivered() { return jdbc.queryForObject("select count(*) from notifications_local_delivery", Integer.class); }
    private static void observeNoRequestFor(Duration duration) throws InterruptedException {
        long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) { assertThat(REQUESTS).hasValue(0); TimeUnit.MILLISECONDS.sleep(25); }
    }
    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) { TimeUnit.MILLISECONDS.sleep(25); }
        assertThat(condition.getAsBoolean()).as("native scheduled effect within %s", timeout).isTrue();
    }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), name + " required"); }
    private static void privateFile(Path file, byte[] bytes) throws Exception {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, bytes, StandardOpenOption.WRITE);
    }
}
