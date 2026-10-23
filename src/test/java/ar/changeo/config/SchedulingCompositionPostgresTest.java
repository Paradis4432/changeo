package ar.changeo.config;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import ar.changeo.identity.api.ActorId;
import ar.changeo.moderation.ModerationWorker;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import ar.changeo.notifications.NotificationService;
import ar.changeo.notifications.api.NoticeOwner;
import ar.changeo.payments.PaymentWorker;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {ChangeoApplication.class, SchedulingCompositionPostgresTest.Notices.class},
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SchedulingCompositionPostgresTest {
    private static final String MODE = required("ES1_COMPOSITION_MODE");
    private static final String SCHEMA = "es1_composition_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path MATERIAL = Path.of(required("ES1_PRIVATE_ROOT"), SCHEMA);
    private static final ContentReference AVAILABLE = new ContentReference(SurfaceKind.PUBLIC_POST, UUID.randomUUID(), UUID.randomUUID());
    private static final ContentReference UNAVAILABLE = new ContentReference(SurfaceKind.PUBLIC_POST, UUID.randomUUID(), UUID.randomUUID());
    private static final AtomicReference<UUID> RECIPIENT = new AtomicReference<>();
    private static ConfigurableApplicationContext observedContext;
    private static List<ScheduledThreadPoolExecutor> pools = List.of();
    private static long started;
    @Autowired ConfigurableApplicationContext context;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean(reset = MockReset.BEFORE) NotificationService notices;
    @MockitoSpyBean(reset = MockReset.BEFORE) PaymentWorker payments;
    @MockitoSpyBean(reset = MockReset.BEFORE) ModerationWorker moderation;
    private static final List<Invocation> paymentCalls = new CopyOnWriteArrayList<>();
    private static final List<Invocation> moderationCalls = new CopyOnWriteArrayList<>();
    private static final List<Invocation> noticeCalls = new CopyOnWriteArrayList<>();
    private record Invocation(long at, String thread) {
        static Invocation now() { return new Invocation(System.nanoTime(), Thread.currentThread().getName()); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Notices {
        @Bean NoticeOwner schedulingNoticeOwner() {
            return new NoticeOwner() {
                @Override public Set<SurfaceKind> noticeKinds() { return Set.of(SurfaceKind.PUBLIC_POST); }
                @Override public boolean available(ContentReference reference, ActorId recipient) {
                    return AVAILABLE.equals(reference) && recipient.value().equals(RECIPIENT.get());
                }
            };
        }
    }

    @BeforeAll
    static void prepareFixture() throws Exception {
        if (!Set.of("notice", "notice-off-missing", "notice-off-false", "payments-false", "moderation-on", "moderation-mode-only", "moderation-worker-only").contains(MODE)) {
            throw new IllegalArgumentException("Unknown composition case");
        }
        String role = required("ES1_ROLE");
        if (!Set.of("writer", "reviewer").contains(role) || !required("CHANGEO_DATABASE_URL").equals("jdbc:postgresql://127.0.0.1:55460/email_delivery_schedule_" + role)) {
            throw new IllegalStateException("Exact scheduling database required");
        }
        Path expected = Path.of("/home/paradis/changeo/.runtime/email-delivery-preparation-20261019/scheduling-current-20261020", role, "material");
        if (!MATERIAL.getParent().toAbsolutePath().normalize().equals(expected) || Files.isSymbolicLink(expected)
                || !Files.getPosixFilePermissions(expected).equals(PosixFilePermissions.fromString("rwx------"))) { throw new IllegalStateException("Exact private scheduling root required"); }
        Files.createDirectory(MATERIAL, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
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
        properties.add("changeo.payments-auth-key", () -> MATERIAL.resolve("gateway.key").toString());
        properties.add("changeo.synthetic-payments", () -> "true");
        properties.add("changeo.synthetic-subscriptions", () -> "false");
        properties.add("changeo.subscription-worker", () -> "false");
        if (!MODE.equals("moderation-worker-only")) { properties.add("changeo.synthetic-moderation", () -> Set.of("moderation-on", "moderation-mode-only").contains(MODE) ? "true" : "false"); }
        if (!MODE.equals("moderation-mode-only")) { properties.add("changeo.moderation-worker", () -> Set.of("moderation-on", "moderation-worker-only").contains(MODE) ? "true" : "false"); }
        if (!MODE.equals("notice-off-missing")) { properties.add("changeo.notification-worker", () -> MODE.equals("notice-off-false") ? "false" : "true"); }
        if (MODE.equals("notice-off-false")) { properties.add("changeo.payments-worker", () -> "true"); }
        else if (!Set.of("notice", "notice-off-missing").contains(MODE)) { properties.add("changeo.payments-worker", () -> "false"); }
    }

    @Test
    void shouldScheduleOnlyAdmittedRealOwnersWithIndependentNativeControls() throws Exception {
        observedContext = context;
        pools = context.getBeansOfType(ThreadPoolTaskScheduler.class).values().stream().map(ThreadPoolTaskScheduler::getScheduledThreadPoolExecutor).toList();
        started = System.nanoTime();
        doAnswer(invocation -> { paymentCalls.add(Invocation.now()); return invocation.callRealMethod(); }).when(payments).processOne();
        doAnswer(invocation -> { moderationCalls.add(Invocation.now()); return invocation.callRealMethod(); }).when(moderation).processOne();
        doAnswer(invocation -> { noticeCalls.add(Invocation.now()); return invocation.callRealMethod(); }).when(notices).runOnce();
        UUID account = accounts.register("schedule" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345").accountId().value();
        assertThat(RECIPIENT.compareAndSet(null, account)).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            notices.enqueue(AVAILABLE, Set.of(new ActorId(account)));
            notices.enqueue(UNAVAILABLE, Set.of(new ActorId(account)));
        });
        boolean noticesOn = !Set.of("notice-off-missing", "notice-off-false").contains(MODE);
        boolean paymentsOn = Set.of("notice", "notice-off-missing", "notice-off-false").contains(MODE);
        if (noticesOn) {
            await(() -> state(AVAILABLE).equals("DELIVERED") && state(UNAVAILABLE).equals("RETRY_WAIT"), Duration.ofSeconds(12));
            assertThat(jdbc.queryForObject("select count(*) from notifications_local_delivery", Integer.class)).isEqualTo(1);
        }
        if (paymentsOn) { await(() -> !paymentCalls.isEmpty(), Duration.ofSeconds(12)); }
        if (MODE.equals("moderation-on")) { await(() -> moderationCalls.size() >= 2, Duration.ofSeconds(12)); }
        int paymentBefore = paymentCalls.size(), noticeBefore = noticeCalls.size();
        long observed = System.nanoTime();
        while (System.nanoTime() - observed < TimeUnit.MILLISECONDS.toNanos(6500)) {
            if (!noticesOn) {
                assertThat(state(AVAILABLE)).isEqualTo("PENDING"); assertThat(state(UNAVAILABLE)).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("select count(*) from notifications_local_delivery", Integer.class)).isZero();
            }
            if (!paymentsOn) { assertThat(paymentCalls).isEmpty(); }
            if (!MODE.equals("moderation-on")) { assertThat(moderationCalls).isEmpty(); }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        if (paymentsOn) { assertCadence(paymentCalls); }
        if (paymentsOn) { assertThat(paymentCalls.size()).isGreaterThan(paymentBefore); }
        if (noticesOn) { assertThat(noticeCalls.size()).isGreaterThan(noticeBefore); }
        if (MODE.equals("moderation-on")) { assertCadence(moderationCalls); }
        if (!noticesOn) { assertThat(noticeCalls).isEmpty(); }
        assertThat(jdbc.queryForObject("select count(*) from payments_capital", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments_ledger", Integer.class)).isEqualTo(2);
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'payments_%' or tablename like 'subscriptions_%') and tablename not in ('payments_capital','payments_ledger') order by tablename", String.class)) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from moderation_task", Integer.class)).isZero();
        var flyway = context.getBean(org.flywaydb.core.Flyway.class);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        flyway.validate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @AfterAll
    static void closeOwnedResources() throws Exception {
        var acquired = new AtomicReference<ConfigurableApplicationContext>(observedContext);
        try (AutoCloseable closeContext = () -> { var app = acquired.get(); if (app != null && app.isActive()) { app.close(); } }) {
            var cached = new TestContextManager(SchedulingCompositionPostgresTest.class).getTestContext();
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
            int paymentCount = paymentCalls.size(), moderationCount = moderationCalls.size(), noticeCount = noticeCalls.size();
            TimeUnit.MILLISECONDS.sleep(3200);
            assertThat(paymentCalls).hasSize(paymentCount); assertThat(moderationCalls).hasSize(moderationCount); assertThat(noticeCalls).hasSize(noticeCount);
            if (observedContext != null) {
                String observation = "mode=" + MODE + "\nelapsedMillis=" + Duration.ofNanos(System.nanoTime() - started).toMillis()
                        + "\npaymentCalls=" + paymentCount + "\nmoderationCalls=" + moderationCount + "\nnoticeCalls=" + noticeCount
                        + "\nschedulerThreads=" + paymentCalls.stream().map(Invocation::thread).distinct().toList()
                        + moderationCalls.stream().map(Invocation::thread).distinct().toList() + noticeCalls.stream().map(Invocation::thread).distinct().toList()
                        + "\nbodyObserved=true\ncontextsClosed=" + !app.isActive() + "\n";
                Path observationFile = MATERIAL.resolve("observation.private");
                Files.createFile(observationFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(observationFile, observation, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
            }
        }
    }

    private String state(ContentReference reference) { return jdbc.queryForObject("select state from notifications_intent where resource_id=? and revision_id=?", String.class, reference.resourceId(), reference.revisionId()); }
    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) { TimeUnit.MILLISECONDS.sleep(25); }
        assertThat(condition.getAsBoolean()).as("native scheduled effect within %s", timeout).isTrue();
    }
    private static void assertCadence(List<Invocation> calls) {
        assertThat(calls.size()).isGreaterThanOrEqualTo(2);
        for (int index = 1; index < calls.size(); index++) { assertThat(Duration.ofNanos(calls.get(index).at() - calls.get(index - 1).at())).isGreaterThanOrEqualTo(Duration.ofMillis(800)); }
    }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), name + " required"); }
}
