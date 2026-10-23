package ar.changeo.config;

import ar.changeo.ChangeoApplication;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = ChangeoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
public class RuntimeReadinessPostgresTest {
    private static final String SCHEMA = "runtime_probe_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired HikariDataSource pool;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    private DatabaseRuntimeReadiness readiness;

    @DynamicPropertySource
    static void resources(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required("CHANGEO_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> required("CHANGEO_DATABASE_USER"));
        properties.add("spring.datasource.password", () -> required("CHANGEO_DATABASE_PASSWORD"));
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("changeo.mfa-key", () -> Path.of(required("RT1A_PRIVATE_ROOT"), "probe-mfa.key").toString());
        properties.add("changeo.mailbox", () -> Path.of(required("RT1A_PRIVATE_ROOT"), "probe-mailbox").toString());
        properties.add("changeo.files-root", () -> Path.of(required("RT1A_PRIVATE_ROOT"), "probe-artifacts").toString());
    }

    @BeforeEach
    void readiness() {
        var availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        readiness = new DatabaseRuntimeReadiness(pool, availability, flyway, new DefaultResourceLoader());
    }

    @Test
    void shouldMatchPackagedSchemaHeadWithoutChangingAnyApplicationRows() {
        var before = rows();
        for (int attempt = 0; attempt < 10; attempt++) { assertThat(readiness.ready()).isTrue(); }
        assertThat(rows()).isEqualTo(before);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void shouldRefuseWrongMissingHistoryAndRecoverWithoutCachingHealth() {
        var before = rows();
        String version = jdbc.queryForObject("select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1", String.class);
        jdbc.update("update flyway_schema_history set version='999999' where version=?", version);
        try { assertThat(readiness.ready()).isFalse(); }
        finally { jdbc.update("update flyway_schema_history set version=? where version='999999'", version); }
        assertThat(readiness.ready()).isTrue();
        jdbc.execute("alter table flyway_schema_history rename to rt1_history_saved");
        try { assertThat(readiness.ready()).isFalse(); }
        finally { jdbc.execute("alter table rt1_history_saved rename to flyway_schema_history"); }
        assertThat(readiness.ready()).isTrue();
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void shouldBoundFullSharedPoolAcquisitionAndRecoverEveryLease() throws Exception {
        var leases = new ArrayList<Connection>();
        try {
            for (int index = 0; index < pool.getMaximumPoolSize(); index++) { leases.add(pool.getConnection()); }
            long started = System.nanoTime();
            assertThat(readiness.ready()).isFalse();
            assertThat(elapsed("pool-saturation", started)).isLessThan(Duration.ofSeconds(2));
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(pool.getMaximumPoolSize());
        } finally { for (var lease : leases) { lease.close(); } }
        assertThat(readiness.ready()).isTrue();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void shouldPreserveOrdinaryQueriesAndObservedRowLocksBeyondTwoSecondsBeforeAndAfterProbes() throws Exception {
        ordinaryQueryAndLock();
        assertThat(readiness.ready()).isTrue();
        ordinaryQueryAndLock();
        jdbc.execute("alter table flyway_schema_history rename to rt1_history_saved");
        try { assertThat(readiness.ready()).isFalse(); }
        finally { jdbc.execute("alter table rt1_history_saved rename to flyway_schema_history"); }
        ordinaryQueryAndLock();
        assertThat(readiness.ready()).isTrue();
        try (var connection = pool.getConnection()) {
            assertThat(connection.getNetworkTimeout()).isZero();
        }
    }

    @Test
    void shouldRestoreSamePhysicalConnectionAfterScopedTimeoutAndAgedValidation() throws Exception {
        try (var single = pool(1, required("CHANGEO_DATABASE_URL"))) {
            var availability = mock(ApplicationAvailability.class);
            when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
            var probe = RuntimeReadinessTest.readiness(single, availability, SCHEMA);
            int backend;
            try (var connection = single.getConnection()) { backend = backend(connection); }
            assertThat(probe.ready()).isTrue();
            TimeUnit.MILLISECONDS.sleep(600);
            try (var connection = single.getConnection()) {
                assertThat(backend(connection)).isEqualTo(backend);
                assertThat(connection.getNetworkTimeout()).isZero();
                try (var statement = connection.createStatement()) {
                    assertThat(statement.getQueryTimeout()).isZero();
                    assertThat(statement.execute("select pg_sleep(2.2)")).isTrue();
                }
            }
            assertThat(probe.ready()).isTrue();
        }
    }

    @Test
    void shouldBoundSilentAuthenticationAndCloseAbandonedNativeWorkersAndSockets() throws Exception {
        try (var listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newSingleThreadExecutor()) {
            var accepted = new AtomicInteger();
            var closed = new AtomicInteger();
            var server = executor.submit(() -> {
                for (int attempt = 0; attempt < 3; attempt++) {
                    try (Socket socket = listener.accept()) {
                        accepted.incrementAndGet();
                        socket.setSoTimeout(4500);
                        while (socket.getInputStream().read() != -1) {}
                        closed.incrementAndGet();
                    }
                }
                return null;
            });
            long workers = driverWorkers();
            var factory = new InitializingPostgresDataSource();
            factory.setUrl("jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/runtime_readiness_qa?sslmode=disable&gssEncMode=disable");
            factory.setLoginTimeout(1);
            for (int attempt = 0; attempt < 3; attempt++) {
                long started = System.nanoTime();
                assertThatThrownBy(() -> factory.getConnection("runtime_readiness_qa", required("CHANGEO_DATABASE_PASSWORD"))).isInstanceOf(SQLException.class);
                assertThat(elapsed("silent-authentication", started)).isLessThan(Duration.ofSeconds(2));
            }
            server.get(5, TimeUnit.SECONDS);
            assertThat(accepted.get()).isEqualTo(3);
            assertThat(closed.get()).isEqualTo(3);
            assertThat(driverWorkers()).isLessThanOrEqualTo(workers);
        }
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void shouldEvictSilentEstablishedConnectionsRecoverAndPreserveOrdinaryWaits() throws Exception {
        try (var relay = new ProbeRelay(); var single = pool(1, relay.url())) {
            var availability = mock(ApplicationAvailability.class);
            when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
            var probe = RuntimeReadinessTest.readiness(single, availability, SCHEMA);
            assertThat(probe.ready()).isTrue();
            for (int attempt = 0; attempt < 3; attempt++) {
                relay.mode = ProbeRelay.Mode.SILENT;
                long started = System.nanoTime();
                try {
                    assertThat(probe.ready()).isFalse();
                    assertThat(elapsed("established-silent-read", started)).isLessThan(Duration.ofSeconds(2));
                    assertThat(single.getHikariPoolMXBean().getActiveConnections()).isZero();
                } finally { relay.mode = ProbeRelay.Mode.FORWARD; }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!probe.ready() && System.nanoTime() < deadline) { Thread.sleep(25); }
                assertThat(probe.ready()).isTrue();
            }
            ordinaryQueryAndLock(single);
            assertThat(probe.ready()).isTrue();
        }
    }

    @Test
    void shouldBoundNativeCancellationCleanupAndRestoreOrdinaryNetworkState() throws Exception {
        try (var relay = new ProbeRelay(); var single = pool(1, relay.url())) {
            var availability = mock(ApplicationAvailability.class);
            when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
            var probe = RuntimeReadinessTest.readiness(single, availability, SCHEMA);
            assertThat(probe.ready()).isTrue();
            jdbc.execute("alter table flyway_schema_history rename to rt1_history_saved");
            jdbc.execute("create function rt1_notice_failure() returns text language plpgsql as $$ begin for counter in 1..15 loop raise notice 'RT1A bounded progress'; perform pg_sleep(0.1); end loop; raise exception 'RT1A fixture failure'; end $$");
            jdbc.execute("create view flyway_schema_history as select 1 as installed_rank, rt1_notice_failure() as version, true as success");
            relay.mode = ProbeRelay.Mode.CANCEL_HOLD;
            try {
                long started = System.nanoTime();
                assertThat(probe.ready()).isFalse();
                assertThat(elapsed("cancellation-cleanup", started)).isLessThan(Duration.ofSeconds(5));
                assertThat(relay.cancellations.get()).isGreaterThanOrEqualTo(1);
                assertThat(single.getHikariPoolMXBean().getActiveConnections()).isZero();
            } finally {
                relay.mode = ProbeRelay.Mode.FORWARD;
                jdbc.execute("drop view flyway_schema_history");
                jdbc.execute("drop function rt1_notice_failure()");
                jdbc.execute("alter table rt1_history_saved rename to flyway_schema_history");
            }
            assertThat(probe.ready()).isTrue();
            ordinaryQueryAndLock(single);
            assertThat(probe.ready()).isTrue();
        }
    }

    @Test
    void shouldClosePhysicalConnectionWhenFactoryCannotRestoreOrdinaryTimeout() throws Exception {
        var factory = new InitializingPostgresDataSource();
        factory.setUrl(socketFaultUrl(FailingFactoryReset.class));
        factory.setLoginTimeout(1);
        assertThatThrownBy(() -> factory.getConnection(required("CHANGEO_DATABASE_USER"), required("CHANGEO_DATABASE_PASSWORD")))
                .isInstanceOf(SQLException.class).hasMessage("Database initialization failed");
        assertThat(FaultSockets.created).isNotEmpty().allMatch(Socket::isClosed);
        ordinaryQueryAndLock();
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void shouldCloseNativePoolSetupFailureWithoutLeakingAUsableConnection() throws Exception {
        try (var failing = new HikariDataSource()) {
            failing.setDataSourceClassName(InitializingPostgresDataSource.class.getName());
            failing.addDataSourceProperty("url", socketFaultUrl(FailingPoolSetup.class));
            failing.setUsername(required("CHANGEO_DATABASE_USER"));
            failing.setPassword(required("CHANGEO_DATABASE_PASSWORD"));
            failing.setSchema(SCHEMA);
            failing.setConnectionTimeout(500);
            failing.setValidationTimeout(250);
            failing.setMaximumPoolSize(1);
            long started = System.nanoTime();
            assertThatThrownBy(failing::getConnection).isInstanceOf(Exception.class);
            assertThat(elapsed("native-first-set-startup", started)).isLessThan(Duration.ofSeconds(5));
        }
        assertThat(FaultSockets.created).isNotEmpty().allMatch(Socket::isClosed);
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void shouldFenceFirstNativeReadSetAndCloseFailuresBeforeAnotherPhysicalSocket() throws Exception {
        for (var hook : List.of(FailingInitialRead.class, FailingPoolSetup.class, FailingSetupAndClose.class)) {
            var factory = new InitializingPostgresDataSource();
            factory.setUrl(socketFaultUrl(hook));
            factory.setLoginTimeout(1);
            int created = FaultSockets.created.size();
            try (var connection = factory.getConnection(required("CHANGEO_DATABASE_USER"), required("CHANGEO_DATABASE_PASSWORD"))) {
                Throwable failure = catchThrowable(() -> {
                    connection.getNetworkTimeout();
                    connection.setNetworkTimeout(Runnable::run, 250);
                });
                assertThat(failure).isInstanceOf(SQLException.class);
                assertThatThrownBy(connection::isReadOnly).isInstanceOf(SQLException.class).hasMessage("Database initialization failed");
                assertThatThrownBy(() -> connection.unwrap(org.postgresql.PGConnection.class)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> factory.getConnection(required("CHANGEO_DATABASE_USER"), required("CHANGEO_DATABASE_PASSWORD")))
                        .isInstanceOf(SQLException.class).hasMessage("Database initialization failed");
                assertThat(FaultSockets.created).hasSize(created + 1);
                if (hook == FailingSetupAndClose.class) {
                    assertThat(failure.getSuppressed()).hasSize(1);
                    assertThat(FaultSockets.created).anyMatch(socket -> !socket.isClosed());
                } else { assertThat(connection.isClosed()).isTrue(); }
            } finally {
                for (var socket : FaultSockets.created) { socket.close(); }
            }
            assertThat(FaultSockets.created).allMatch(Socket::isClosed);
        }
        ordinaryQueryAndLock();
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void shouldPreserveNativeFirstConnectionDelegationAndConfiguredOrdinaryTimeout() throws Exception {
        String url = required("CHANGEO_DATABASE_URL");
        try (var single = pool(1, url + (url.contains("?") ? "&" : "?") + "socketTimeout=4")) {
            var availability = mock(ApplicationAvailability.class);
            when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
            var probe = RuntimeReadinessTest.readiness(single, availability, SCHEMA);
            try (var connection = single.getConnection()) {
                assertThat(connection.getNetworkTimeout()).isEqualTo(4000);
                assertThat(connection.isWrapperFor(org.postgresql.PGConnection.class)).isTrue();
                assertThat(connection.unwrap(org.postgresql.PGConnection.class)).isInstanceOf(org.postgresql.jdbc.PgConnection.class);
                assertThat(connection.equals(connection)).isTrue();
                try (var statement = connection.createStatement()) { assertThat(statement.execute("select pg_sleep(2.2)")).isTrue(); }
                assertThatThrownBy(() -> connection.prepareStatement("select * from rt1_missing_relation").execute())
                        .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("42P01");
            }
            assertThat(probe.ready()).isTrue();
            try (var connection = single.getConnection()) { assertThat(connection.getNetworkTimeout()).isEqualTo(4000); }
        }
    }

    @Test
    void shouldEvictProbeResetFailureAndRecoverWithOrdinaryNetworkTimeout() throws Exception {
        try (var single = pool(1, socketFaultUrl(FailingProbeReset.class))) {
            var availability = mock(ApplicationAvailability.class);
            when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
            var probe = RuntimeReadinessTest.readiness(single, availability, SCHEMA);
            long started = System.nanoTime();
            assertThat(probe.ready()).isFalse();
            assertThat(elapsed("probe-reset-failure", started)).isLessThan(Duration.ofSeconds(5));
            assertThat(single.getHikariPoolMXBean().getActiveConnections()).isZero();
            ordinaryQueryAndLock(single);
        }
        assertThat(FaultSockets.created).isNotEmpty().allMatch(Socket::isClosed);
        assertThat(readiness.ready()).isTrue();
    }

    public static Duration elapsed(String scenario, long started) throws IOException {
        long nanos = System.nanoTime() - started;
        String run = System.getProperty("rt1a.run", "native");
        if (!run.matches("[a-zA-Z0-9-]+") || !scenario.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Invalid private timing label");
        }
        java.nio.file.Files.writeString(Path.of(required("RT1A_PRIVATE_ROOT"), "timings-" + run + ".jsonl"),
                "{\"scenario\":\"" + scenario + "\",\"nanos\":" + nanos + "}\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        return Duration.ofNanos(nanos);
    }

    private static String socketFaultUrl(Class<? extends javax.net.SocketFactory> factory) {
        String url = required("CHANGEO_DATABASE_URL");
        return url + (url.contains("?") ? "&" : "?") + "sslmode=disable&gssEncMode=disable&socketFactory=" + factory.getName();
    }

    private void ordinaryQueryAndLock() throws Exception {
        ordinaryQueryAndLock(pool);
    }

    private void ordinaryQueryAndLock(HikariDataSource ordinary) throws Exception {
        try (var connection = ordinary.getConnection(); var statement = connection.createStatement()) {
            assertThat(connection.getNetworkTimeout()).isZero();
            assertThat(statement.getQueryTimeout()).isZero();
            long started = System.nanoTime();
            statement.execute("select pg_sleep(2.2)");
            assertThat(elapsed("ordinary-query", started)).isGreaterThan(Duration.ofSeconds(2));
        }
        jdbc.execute("create table if not exists rt1_lock (id integer primary key, value integer not null)");
        jdbc.update("insert into rt1_lock values (1,0) on conflict do nothing");
        try (var blocker = pool.getConnection(); var waiter = ordinary.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) { statement.executeUpdate("update rt1_lock set value=value+1 where id=1"); }
            int pid = backend(waiter);
            var entered = new CountDownLatch(1);
            var update = executor.submit(() -> {
                entered.countDown();
                try (var statement = waiter.createStatement()) { return statement.executeUpdate("update rt1_lock set value=value+1 where id=1"); }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0", Boolean.class, pid)) && System.nanoTime() < deadline) { Thread.sleep(10); }
                assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0", Boolean.class, pid)).isTrue();
                TimeUnit.MILLISECONDS.sleep(2200);
                assertThat(update.isDone()).isFalse();
            } finally { blocker.commit(); }
            assertThat(update.get(2, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(waiter.getNetworkTimeout()).isZero();
        }
    }

    private Map<String, List<Map<String, Object>>> rows() {
        var result = new TreeMap<String, List<Map<String, Object>>>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename not like 'rt1_%' order by tablename", String.class)) {
            assertThat(table).matches("[a-z_]+");
            result.put(table, jdbc.queryForList("select to_jsonb(t)::text as row from " + table + " t order by to_jsonb(t)::text"));
        }
        return Map.copyOf(result);
    }

    private static int backend(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("select pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static HikariDataSource pool(int size, String url) {
        var pool = new HikariDataSource();
        pool.setDataSourceClassName(InitializingPostgresDataSource.class.getName());
        pool.addDataSourceProperty("url", url);
        pool.setUsername(required("CHANGEO_DATABASE_USER"));
        pool.setPassword(required("CHANGEO_DATABASE_PASSWORD"));
        pool.setSchema(SCHEMA);
        pool.setConnectionTimeout(500);
        pool.setValidationTimeout(250);
        pool.setMaximumPoolSize(size);
        pool.setMinimumIdle(size);
        try (var ignored = pool.getConnection()) { return pool; }
        catch (SQLException failure) { pool.close(); throw new IllegalStateException("Private fixture initialization failed", failure); }
    }

    private static long driverWorkers() {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().startsWith("PostgreSQL JDBC driver connection thread")).count();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Explicit private runtime requires " + name); }
        return value;
    }

    private static final class ProbeRelay implements AutoCloseable {
        enum Mode { FORWARD, SILENT, CANCEL_HOLD }
        private final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(8);
        private final java.util.Set<Socket> sockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Queue<Throwable> failures = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final AtomicInteger cancellations = new AtomicInteger();
        private volatile Mode mode = Mode.FORWARD;
        private volatile boolean closed;

        ProbeRelay() throws IOException {
            workers.submit(() -> {
                while (!closed) {
                    try {
                        Socket client = listener.accept();
                        sockets.add(client);
                        workers.submit(() -> connect(client));
                    } catch (IOException failure) {
                        if (!closed) { failures.add(failure); }
                    }
                }
            });
        }

        String url() { return "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/runtime_readiness_qa?sslmode=disable&gssEncMode=disable"; }

        private void connect(Socket client) {
            try {
                client.setSoTimeout(2500);
                byte[] header = client.getInputStream().readNBytes(8);
                if (header.length != 8) { return; }
                if (java.nio.ByteBuffer.wrap(header, 4, 4).getInt() == 80877102 && mode == Mode.CANCEL_HOLD) {
                    cancellations.incrementAndGet();
                    TimeUnit.MILLISECONDS.sleep(1300);
                    return;
                }
                Socket backend = new Socket();
                sockets.add(backend);
                backend.connect(new java.net.InetSocketAddress("127.0.0.1", 55464), 1000);
                backend.getOutputStream().write(header);
                backend.getOutputStream().flush();
                client.setSoTimeout(0);
                workers.submit(() -> forward(backend, client, true));
                forward(client, backend, false);
            } catch (IOException failure) {
                if (!closed && !(failure instanceof SocketException)) { failures.add(failure); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { closeSocket(client); }
        }

        private void forward(Socket from, Socket to, boolean response) {
            try {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = from.getInputStream().read(buffer)) != -1) {
                    while (response && mode == Mode.SILENT && !closed) { TimeUnit.MILLISECONDS.sleep(10); }
                    if (closed) { return; }
                    to.getOutputStream().write(buffer, 0, count);
                    to.getOutputStream().flush();
                }
            } catch (IOException failure) {
                if (!closed && !(failure instanceof SocketException)) { failures.add(failure); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                closeSocket(from);
                closeSocket(to);
            }
        }

        private void closeSocket(Socket socket) {
            try { socket.close(); }
            catch (IOException failure) { failures.add(failure); }
            sockets.remove(socket);
        }

        @Override
        public void close() throws Exception {
            closed = true;
            listener.close();
            for (Socket socket : List.copyOf(sockets)) { closeSocket(socket); }
            workers.shutdownNow();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(sockets).isEmpty();
            assertThat(failures).isEmpty();
        }
    }

    public static final class FailingFactoryReset extends FaultSockets {
        @Override boolean reject(int previous, int next) { return previous == 2000 && next == 0; }
    }

    public static final class FailingPoolSetup extends FaultSockets {
        @Override boolean reject(int previous, int next) { return next == 250; }
    }

    public static final class FailingInitialRead extends FaultSockets {
        @Override boolean reject(int previous, int next) { return false; }
        @Override boolean rejectRead() { return true; }
    }

    public static final class FailingSetupAndClose extends FaultSockets {
        @Override boolean reject(int previous, int next) { return next == 250; }
        @Override boolean rejectClose() { return true; }
    }

    public static final class FailingProbeReset extends FaultSockets {
        @Override boolean reject(int previous, int next) { return previous == 500 && next == 0; }
    }

    public abstract static class FaultSockets extends javax.net.SocketFactory {
        private static final java.util.Queue<Socket> created = new java.util.concurrent.ConcurrentLinkedQueue<>();
        public static List<Socket> createdSockets() { return List.copyOf(created); }
        abstract boolean reject(int previous, int next);
        boolean rejectRead() { return false; }
        boolean rejectClose() { return false; }

        @Override
        public Socket createSocket() {
            Socket socket = new Socket() {
                private boolean restored;
                private boolean closeFailed;

                @Override
                public synchronized int getSoTimeout() throws SocketException {
                    if (restored && rejectRead()) { throw new SocketException("RT1C initial timeout read fault"); }
                    return super.getSoTimeout();
                }

                @Override
                public synchronized void setSoTimeout(int timeout) throws SocketException {
                    if (reject(super.getSoTimeout(), timeout)) { throw new SocketException("RT1A bounded socket setting fault"); }
                    super.setSoTimeout(timeout);
                    if (timeout == 0) { restored = true; }
                }

                @Override
                public synchronized void close() throws IOException {
                    if (restored && !closeFailed && rejectClose()) {
                        closeFailed = true;
                        throw new IOException("RT1C initial physical close fault");
                    }
                    super.close();
                }
            };
            created.add(socket);
            return socket;
        }

        @Override public Socket createSocket(String host, int port) throws IOException { return createSocket(InetAddress.getByName(host), port); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return createSocket(InetAddress.getByName(host), port, local, localPort); }
        @Override public Socket createSocket(InetAddress address, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new java.net.InetSocketAddress(address, port), 1000);
            return socket;
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new java.net.InetSocketAddress(local, localPort));
            socket.connect(new java.net.InetSocketAddress(address, port), 1000);
            return socket;
        }
    }
}
