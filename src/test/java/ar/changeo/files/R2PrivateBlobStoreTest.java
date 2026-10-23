package ar.changeo.files;

import ar.changeo.moderation.ModerationFailure;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class R2PrivateBlobStoreTest {
    @TempDir(cleanup = CleanupMode.NEVER) Path directory;

    @Test void shouldUseSdkConditionalWireContractAndRetainRemoteRollback() throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var store = server.store(directory.resolve("journal"))) {
            byte[] bytes = "synthetic:clear".getBytes(StandardCharsets.UTF_8);
            UUID id = UUID.randomUUID();
            var blob = store.create(id, "text/plain", bytes);
            var request = server.requests.getFirst();
            assertThat(request.method()).isEqualTo("PUT");
            assertThat(request.path()).isEqualTo("/changeo-r2-preparation/private-blobs/" + id);
            assertThat(request.headers().getFirst("If-None-Match")).isEqualTo("*");
            assertThat(request.headers().getFirst("Content-Length")).isEqualTo(Integer.toString(bytes.length));
            assertThat(request.headers().getFirst("Content-Type")).isEqualTo("text/plain");
            assertThat(request.headers().getFirst("Content-MD5")).isEqualTo(Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)));
            assertThat(request.headers().getFirst("Authorization")).startsWith("AWS4-HMAC-SHA256 ");
            assertThat(request.headers().getFirst("Transfer-Encoding")).isNull();
            assertThat(request.bytes()).isEqualTo(bytes);
            assertThat(store.read(blob, "text/plain", bytes.length)).isEqualTo(bytes);
            assertThat(server.requests.getLast().headers().getFirst("If-Match")).isEqualTo(blob.etag());
            assertThat(store.rollback(blob)).isEqualTo(PrivateBlobStore.Cleanup.RETAINED);
            assertThat(server.objects).hasSize(1);
            assertThat(server.requests).noneMatch(record -> record.method().equals("DELETE"));
            assertThat(Files.readString(directory.resolve("journal").resolve(id + ".attempt"))).startsWith("POSSIBLE_EFFECT\n");
            assertThat(Files.readString(directory.resolve("journal").resolve(id + ".completion"))).startsWith("RETAINED\n");
            assertThat(Files.getPosixFilePermissions(directory.resolve("journal").resolve(id + ".attempt"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"COLLISION", "LOST", "MISSING_ETAG", "ERROR", "ERROR_OVERSIZE", "ERROR_DRIBBLE"})
    void shouldQuarantineOneAttemptWithoutDeleteOrRetry(Mode mode) throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var store = server.store(directory.resolve("journal"))) {
            UUID id = UUID.randomUUID();
            server.mode = mode;
            long start = System.nanoTime();
            assertThatThrownBy(() -> store.create(id, "text/plain", new byte[]{1})).isInstanceOf(ModerationFailure.class).hasMessage("Almacenamiento privado no disponible");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(9));
            assertThat(server.count("PUT")).isEqualTo(1);
            assertThat(server.count("DELETE")).isZero();
            assertThat(Files.readString(directory.resolve("journal").resolve(id + ".outcome"))).startsWith("QUARANTINED\n");
            if (mode == Mode.COLLISION) { assertThat(server.objects).isEmpty(); }
            if (mode == Mode.LOST || mode == Mode.MISSING_ETAG) { assertThat(server.objects).hasSize(1); }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"WRONG_LENGTH", "WRONG_TYPE", "WRONG_ENCODING", "WRONG_ETAG", "SHORT", "OVERSIZE", "STALL", "DRIBBLE", "ERROR", "ERROR_OVERSIZE", "ERROR_DRIBBLE", "REDIRECT", "REGION_REDIRECT", "THROTTLED", "RESET", "EMPTY", "CHUNKED", "CHUNKED_ERROR"})
    void shouldBoundAndDenyInvalidReadWithoutRetry(Mode mode) throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var store = server.store(directory.resolve("journal"))) {
            var blob = store.create(UUID.randomUUID(), "text/plain", new byte[]{1, 2, 3, 4});
            server.mode = mode;
            long start = System.nanoTime();
            assertThatThrownBy(() -> store.read(blob, "text/plain", 4)).isInstanceOf(ModerationFailure.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(9));
            assertThat(server.count("GET")).as(mode.name() + " " + server.requests.stream().filter(request -> request.method().equals("GET")).map(request -> request.headers().getFirst("amz-sdk-request")).toList()).isEqualTo(1);
        }
    }

    @Test void shouldKeepNegativeReadAndLatePutUnknownAndRetryWithFreshIdentity() throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var store = server.store(directory.resolve("journal"))) {
            UUID old = UUID.randomUUID();
            server.mode = Mode.LATE;
            assertThatThrownBy(() -> store.create(old, "text/plain", new byte[]{1})).isInstanceOf(ModerationFailure.class);
            server.mode = Mode.NORMAL;
            assertThatThrownBy(() -> store.read(new PrivateBlobStore.Remote(old, store.storeId(), "private-blobs/" + old, "\"synthetic-tag\""), "text/plain", 1)).isInstanceOf(ModerationFailure.class);
            server.release.countDown();
            assertThat(server.lateFinished.await(3, TimeUnit.SECONDS)).isTrue();
            var fresh = store.create(UUID.randomUUID(), "text/plain", new byte[]{1});
            assertThat(fresh.fileId()).isNotEqualTo(old);
            assertThat(server.objects).hasSize(2);
            assertThat(Files.readString(directory.resolve("journal").resolve(old + ".outcome"))).startsWith("QUARANTINED\n");
            assertThat(server.count("PUT")).isEqualTo(2);
            assertThat(server.count("DELETE")).isZero();
        }
    }

    @Test void shouldAbortInterruptedReadAndCloseOwnedResources() throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var store = server.store(directory.resolve("journal")); var pool = Executors.newSingleThreadExecutor()) {
            var blob = store.create(UUID.randomUUID(), "text/plain", new byte[]{1});
            server.mode = Mode.STALL;
            AtomicReference<Thread> calling = new AtomicReference<>();
            var result = pool.submit(() -> {
                calling.set(Thread.currentThread());
                assertThatThrownBy(() -> store.read(blob, "text/plain", 1)).isInstanceOf(ModerationFailure.class);
                return Thread.currentThread().isInterrupted();
            });
            assertThat(server.readStarted.await(3, TimeUnit.SECONDS)).isTrue();
            calling.get().interrupt();
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(server.count("GET")).isEqualTo(1);
        }
    }

    @Test void shouldFailBeforePutForUnsafeJournalAndDenyChangedStore() throws Exception {
        try (var server = new FakeS3(directory.resolve("server"))) {
            Path unsafe = directory.resolve("unsafe");
            Files.createDirectory(unsafe);
            Files.setPosixFilePermissions(unsafe, PosixFilePermissions.fromString("rwxr-xr-x"));
            try (var store = server.store(unsafe)) {
                assertThatThrownBy(() -> store.create(UUID.randomUUID(), "text/plain", new byte[]{1})).isInstanceOf(ModerationFailure.class);
                assertThat(server.requests).isEmpty();
                UUID id = UUID.randomUUID();
                assertThatThrownBy(() -> store.read(new PrivateBlobStore.Remote(id, "0".repeat(64), "private-blobs/" + id, "tag"), "text/plain", 1)).isInstanceOf(ModerationFailure.class);
                assertThat(server.requests).isEmpty();
            }
            Files.createSymbolicLink(directory.resolve("linked"), unsafe);
            try (var store = server.store(directory.resolve("linked"))) {
                assertThatThrownBy(() -> store.create(UUID.randomUUID(), "text/plain", new byte[]{1})).isInstanceOf(ModerationFailure.class);
                assertThat(server.requests).isEmpty();
            }
        }
    }

    @Test void shouldConstructOnlyLocalDefaultAndRejectMalformedRemoteConfiguration() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PrivateBlobConfiguration.class).withBean(Clock.class, Clock::systemUTC);
        runner.run(context -> {
            assertThat(context).hasSingleBean(LocalPrivateBlobStore.class).doesNotHaveBean(R2PrivateBlobStore.class).doesNotHaveBean(PrivateBlobJournal.class);
        });
        runner.withPropertyValues("changeo.files.storage=unknown").run(context -> assertThat(context).hasFailed());
        for (String endpoint : List.of("https://example.com", "http://localhost:1234", "http://127.0.0.1:1234/path", "http://user@127.0.0.1:1234", "http://127.0.0.1:1234?q=x", "http://127.0.0.1:1234#x")) {
            runner.withPropertyValues("changeo.files.storage=r2-preparation", "changeo.files.r2.endpoint=" + endpoint,
                    "changeo.files.r2.journal-root=/home/paradis/changeo/.runtime/r2-storage-preparation-20261019/never-created",
                    "changeo.files.r2.bucket=changeo-r2-preparation", "changeo.files.r2.access-key=r2-preparation-key", "changeo.files.r2.secret-key=r2-preparation-secret")
                    .run(context -> assertThat(context).hasFailed());
        }
        runner.withPropertyValues("changeo.files.storage=r2-preparation").run(context -> assertThat(context).hasFailed());
    }

    @Test void shouldRecoverAllPoolSlotsAfterAbortBoundAcquisitionAndCloseActiveCalls() throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var store = server.store(directory.resolve("journal")); var pool = Executors.newFixedThreadPool(21)) {
            var blob = store.create(UUID.randomUUID(), "text/plain", new byte[]{1});
            assertThat(store.read(blob, "text/plain", 1)).containsExactly(1);
            server.mode = Mode.WRONG_TYPE;
            for (int index = 0; index < 30; index++) {
                assertThatThrownBy(() -> store.read(blob, "text/plain", 1)).isInstanceOf(ModerationFailure.class);
            }
            var gate = new FakeS3.Gate(new CountDownLatch(20), new CountDownLatch(1));
            server.gate = gate;
            server.mode = Mode.HELD;
            var active = new ArrayList<Future<byte[]>>();
            try {
                for (int index = 0; index < 20; index++) { active.add(pool.submit(() -> store.read(blob, "text/plain", 1))); }
                assertThat(gate.entered().await(1, TimeUnit.SECONDS)).isTrue();
                long before = server.count("GET");
                var exhausted = pool.submit(() -> {
                    assertThatThrownBy(() -> store.read(blob, "text/plain", 1)).isInstanceOf(ModerationFailure.class);
                });
                exhausted.get(1800, TimeUnit.MILLISECONDS);
                assertThat(server.count("GET")).isEqualTo(before);
            } finally { gate.release().countDown(); }
            for (var result : active) { assertThat(result.get(3, TimeUnit.SECONDS)).containsExactly(1); }
            var closeGate = new FakeS3.Gate(new CountDownLatch(1), new CountDownLatch(1));
            server.gate = closeGate;
            var closing = pool.submit(() -> store.read(blob, "text/plain", 1));
            try {
                assertThat(closeGate.entered().await(1, TimeUnit.SECONDS)).isTrue();
                long before = server.count("GET");
                store.close();
                assertThatThrownBy(() -> closing.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ModerationFailure.class);
                assertThatThrownBy(() -> store.read(blob, "text/plain", 1)).isInstanceOf(ModerationFailure.class);
                assertThat(server.count("GET")).isEqualTo(before);
            } finally { closeGate.release().countDown(); }
        }
    }

    @Test void shouldPreserveForcedAttemptAcrossAbruptProcessLoss() throws Exception {
        UUID id = UUID.randomUUID();
        Path journal = directory.resolve("crash-journal");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                CrashWriter.class.getName(), journal.toString(), id.toString())
                .redirectOutput(directory.resolve("crash-process.out").toFile()).redirectError(directory.resolve("crash-process.err").toFile()).start();
        try {
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
        }
        assertThat(Files.readString(journal.resolve(id + ".attempt"))).startsWith("POSSIBLE_EFFECT\n");
        assertThat(Files.exists(journal.resolve(id + ".outcome"))).isFalse();
        assertThat(Files.exists(journal.resolve(id + ".completion"))).isFalse();
    }

    @Test void shouldRetainPutWhenOutcomeJournalCannotComplete() throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var store = server.store(directory.resolve("journal"))) {
            UUID id = UUID.randomUUID();
            LocalPrivateBlobStore.directory(directory.resolve("journal"));
            Files.writeString(directory.resolve("journal").resolve(id + ".outcome"), "retained-test-observation", StandardOpenOption.CREATE_NEW);
            assertThatThrownBy(() -> store.create(id, "text/plain", new byte[]{1})).isInstanceOf(ModerationFailure.class);
            assertThat(server.count("PUT")).isEqualTo(1);
            assertThat(server.objects).hasSize(1);
            assertThat(Files.readString(directory.resolve("journal").resolve(id + ".attempt"))).startsWith("POSSIBLE_EFFECT\n");
            store.create(UUID.randomUUID(), "text/plain", new byte[]{1});
            assertThat(server.objects).hasSize(2);
            assertThat(server.count("DELETE")).isZero();
        }
    }

    public static final class CrashWriter {
        public static void main(String[] args) {
            new PrivateBlobJournal(Path.of(args[0]), Clock.systemUTC()).attempt(UUID.fromString(args[1]), "a".repeat(64), "text/plain", new byte[]{1});
            Runtime.getRuntime().halt(0);
        }
    }

    @Test void shouldPreserveKnownConditionalCollisionAcrossIndependentJournals() throws Exception {
        try (var server = new FakeS3(directory.resolve("server")); var first = server.store(directory.resolve("first")); var second = server.store(directory.resolve("second"))) {
            UUID id = UUID.randomUUID();
            var original = first.create(id, "text/plain", new byte[]{1});
            assertThatThrownBy(() -> second.create(id, "text/plain", new byte[]{2})).isInstanceOf(ModerationFailure.class);
            assertThat(first.read(original, "text/plain", 1)).containsExactly(1);
            assertThat(server.count("PUT")).isEqualTo(2);
            assertThat(server.objects).hasSize(1);
            assertThat(server.count("DELETE")).isZero();
        }
    }

    @Test void shouldRejectRawMalformedHeadersWithOneWireRequest() throws Exception {
        try (var listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); var executor = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(8000);
            var requests = new CopyOnWriteArrayList<String>();
            var server = executor.submit(() -> {
                try {
                    try (var connection = listener.accept()) {
                        connection.setSoTimeout(8000);
                        var input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                        rawRequest(input, requests);
                        reply(connection, "Content-Length: 0\r\nConnection: close\r\nETag: \"raw-tag\"\r\n", "");
                    }
                    try (var connection = listener.accept()) {
                        connection.setSoTimeout(8000);
                        rawRequest(new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII)), requests);
                        reply(connection, "Content-Length: invalid\r\nContent-Type: text/plain\r\n", "x");
                    }
                    return null;
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            try (var store = new R2PrivateBlobStore(URI.create("http://127.0.0.1:" + listener.getLocalPort()), "changeo-r2-preparation", "r2-preparation-key", "r2-preparation-secret", new PrivateBlobJournal(directory.resolve("raw-journal"), Clock.systemUTC()))) {
                var blob = store.create(UUID.randomUUID(), "text/plain", new byte[]{120});
                assertThatThrownBy(() -> store.read(blob, "text/plain", 1)).isInstanceOf(ModerationFailure.class);
                server.get(3, TimeUnit.SECONDS);
                assertThat(requests.stream().filter(value -> value.startsWith("PUT ")).count()).isEqualTo(1);
                assertThat(requests.stream().filter(value -> value.startsWith("GET ")).count()).isEqualTo(1);
            } finally { listener.close(); server.cancel(true); }
        }
    }

    @Test void shouldBoundClosedPooledConnectionWithoutReplayingAnUnacknowledgedGet() throws Exception {
        try (var listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); var executor = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(3000);
            var requests = new CopyOnWriteArrayList<String>();
            var closed = new CountDownLatch(1);
            var server = executor.submit(() -> {
                try {
                    try (var connection = listener.accept()) {
                        connection.setSoTimeout(3000);
                        var input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                        rawRequest(input, requests);
                        reply(connection, "Content-Length: 0\r\nETag: \"raw-tag\"\r\n", "");
                        rawRequest(input, requests);
                        reply(connection, "Content-Length: 1\r\nContent-Type: text/plain\r\nETag: \"raw-tag\"\r\n", "x");
                    }
                    closed.countDown();
                    try (var connection = listener.accept()) {
                        connection.setSoTimeout(3000);
                        rawRequest(new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII)), requests);
                        reply(connection, "Content-Length: 1\r\nContent-Type: text/plain\r\nETag: \"raw-tag\"\r\n", "x");
                    } catch (SocketTimeoutException expectedWhenStaleCallFailsClosed) { }
                    return null;
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            try (var store = new R2PrivateBlobStore(URI.create("http://127.0.0.1:" + listener.getLocalPort()), "changeo-r2-preparation", "r2-preparation-key", "r2-preparation-secret", new PrivateBlobJournal(directory.resolve("stale-journal"), Clock.systemUTC()))) {
                var blob = store.create(UUID.randomUUID(), "text/plain", new byte[]{120});
                assertThat(store.read(blob, "text/plain", 1)).containsExactly(120);
                assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                boolean delivered;
                try { assertThat(store.read(blob, "text/plain", 1)).containsExactly(120); delivered = true; }
                catch (ModerationFailure unavailable) { delivered = false; }
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(9));
                server.get(4, TimeUnit.SECONDS);
                long gets = requests.stream().filter(value -> value.startsWith("GET ")).count();
                assertThat(gets).isBetween(1L, 2L);
                if (delivered) { assertThat(gets).isEqualTo(2); }
                assertThat(requests.stream().filter(value -> value.startsWith("PUT ")).count()).isEqualTo(1);
                Files.writeString(directory.resolve("stale-wire-result.private"), "GET wire count=" + gets + "; second verified delivery=" + delivered);
            } finally { listener.close(); server.cancel(true); }
        }
    }

    private static void rawRequest(BufferedReader input, List<String> requests) throws IOException {
        String line = input.readLine();
        if (line == null) { throw new EOFException(); }
        requests.add(line);
        int length = 0;
        while (!(line = input.readLine()).isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) { length = Integer.parseInt(line.substring(15).trim()); }
        }
        for (int index = 0; index < length; index++) { if (input.read() < 0) { throw new EOFException(); } }
    }

    private static void reply(Socket connection, String headers, String body) throws IOException {
        connection.getOutputStream().write(("HTTP/1.1 200 OK\r\n" + headers + "\r\n" + body).getBytes(StandardCharsets.US_ASCII));
        connection.getOutputStream().flush();
    }

    enum Mode { NORMAL, COLLISION, LOST, MISSING_ETAG, ERROR, ERROR_OVERSIZE, ERROR_DRIBBLE, WRONG_LENGTH, WRONG_TYPE, WRONG_ENCODING, WRONG_ETAG, SHORT, OVERSIZE, STALL, DRIBBLE, REDIRECT, LATE, REGION_REDIRECT, THROTTLED, RESET, EMPTY, CHUNKED, CHUNKED_ERROR, CHECKSUM, HELD }

    static final class FakeS3 implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final ConcurrentMap<String, Stored> objects = new ConcurrentHashMap<>();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch lateFinished = new CountDownLatch(1);
        final CountDownLatch readStarted = new CountDownLatch(1);
        final AtomicInteger disconnected = new AtomicInteger();
        volatile Mode mode = Mode.NORMAL;
        volatile Gate gate;
        final Path root;

        FakeS3(Path root) throws IOException { this(root, 0); }

        FakeS3(Path root, int port) throws IOException {
            this.root = root;
            LocalPrivateBlobStore.directory(root.toAbsolutePath());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        R2PrivateBlobStore store(Path journal) { return new R2PrivateBlobStore(endpoint(), "changeo-r2-preparation", "r2-preparation-key", "r2-preparation-secret", new PrivateBlobJournal(journal, Clock.systemUTC())); }
        long count(String method) { return requests.stream().filter(value -> value.method().equals(method)).count(); }

        private void handle(HttpExchange exchange) {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readNBytes(PrivateBlobStore.MAX_BYTES + 1);
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                requests.add(new Request(method, path, exchange.getRequestHeaders(), body));
                Mode response = mode;
                if (method.equals("PUT")) {
                    if (!"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match")) || response == Mode.COLLISION || objects.containsKey(path)) { exchange.sendResponseHeaders(412, -1); return; }
                    if (response == Mode.ERROR || response == Mode.ERROR_OVERSIZE || response == Mode.ERROR_DRIBBLE) { error(exchange, response); return; }
                    if (response == Mode.LATE) {
                        if (!release.await(10, TimeUnit.SECONDS)) { throw new IOException("late test release absent"); }
                    }
                    if (response == Mode.HELD) {
                        Gate active = gate;
                        active.entered().countDown();
                        if (!active.release().await(8, TimeUnit.SECONDS)) { throw new IOException("put gate not released"); }
                    }
                    Stored value = new Stored(body, exchange.getRequestHeaders().getFirst("Content-Type"), "\"synthetic-tag\"");
                    if (objects.putIfAbsent(path, value) != null) { exchange.sendResponseHeaders(412, -1); return; }
                    String id = path.substring(path.lastIndexOf('/') + 1);
                    Files.write(root.resolve(id), body, StandardOpenOption.CREATE_NEW);
                    Files.setPosixFilePermissions(root.resolve(id), PosixFilePermissions.fromString("rw-------"));
                    var metadata = new Properties();
                    metadata.setProperty("type", value.type());
                    metadata.setProperty("etag", value.etag());
                    try (var output = Files.newOutputStream(root.resolve(id + ".meta"), StandardOpenOption.CREATE_NEW)) { metadata.store(output, null); }
                    Files.setPosixFilePermissions(root.resolve(id + ".meta"), PosixFilePermissions.fromString("rw-------"));
                    if (response == Mode.LATE) { lateFinished.countDown(); }
                    if (response == Mode.LOST) { return; }
                    if (response != Mode.MISSING_ETAG) { exchange.getResponseHeaders().add("ETag", value.etag()); }
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                if (!method.equals("GET")) { exchange.sendResponseHeaders(405, -1); return; }
                readStarted.countDown();
                Stored value = objects.get(path);
                if (value == null) { exchange.sendResponseHeaders(404, -1); return; }
                if (!value.etag().equals(exchange.getRequestHeaders().getFirst("If-Match"))) { exchange.sendResponseHeaders(412, -1); return; }
                if (response == Mode.RESET) { return; }
                if (response == Mode.EMPTY) { exchange.sendResponseHeaders(204, -1); return; }
                if (response == Mode.REDIRECT || response == Mode.REGION_REDIRECT) {
                    exchange.getResponseHeaders().add("Location", endpoint() + path);
                    exchange.getResponseHeaders().add("x-amz-bucket-region", "us-west-2");
                    exchange.sendResponseHeaders(response == Mode.REDIRECT ? 307 : 301, -1); return;
                }
                if (response == Mode.THROTTLED) { exchange.getResponseHeaders().add("Retry-After", "1"); exchange.sendResponseHeaders(429, -1); return; }
                if (response == Mode.CHUNKED_ERROR) { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().write(new byte[100000]); return; }
                if (response == Mode.ERROR || response == Mode.ERROR_OVERSIZE || response == Mode.ERROR_DRIBBLE) { error(exchange, response); return; }
                exchange.getResponseHeaders().add("Content-Type", response == Mode.WRONG_TYPE ? "image/png" : value.type());
                exchange.getResponseHeaders().add("ETag", response == Mode.WRONG_ETAG ? "wrong" : value.etag());
                if (response == Mode.WRONG_ENCODING) { exchange.getResponseHeaders().add("Content-Encoding", "gzip"); }
                if (response == Mode.CHECKSUM) { exchange.getResponseHeaders().add("Content-MD5", "not-a-valid-checksum"); }
                long length = response == Mode.WRONG_LENGTH || response == Mode.OVERSIZE ? value.bytes().length + 1L : value.bytes().length;
                exchange.sendResponseHeaders(200, response == Mode.CHUNKED ? 0 : length);
                if (response == Mode.STALL) { release.await(10, TimeUnit.SECONDS); return; }
                if (response == Mode.HELD) {
                    Gate active = gate;
                    active.entered().countDown();
                    if (!active.release().await(8, TimeUnit.SECONDS)) { throw new IOException("body gate not released"); }
                }
                if (response == Mode.DRIBBLE) {
                    for (byte item : value.bytes()) { exchange.getResponseBody().write(item); exchange.getResponseBody().flush(); Thread.sleep(1800); }
                    return;
                }
                if (response == Mode.SHORT) { exchange.getResponseBody().write(value.bytes(), 0, value.bytes().length - 1); return; }
                if (response == Mode.CHECKSUM) { byte[] corrupt = value.bytes().clone(); corrupt[0] ^= 1; exchange.getResponseBody().write(corrupt); return; }
                exchange.getResponseBody().write(value.bytes());
                if (response == Mode.OVERSIZE) { exchange.getResponseBody().write(1); }
            } catch (IOException expected) { disconnected.incrementAndGet(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        }

        private void error(HttpExchange exchange, Mode response) throws IOException, InterruptedException {
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            if (response == Mode.ERROR) { exchange.sendResponseHeaders(500, -1); return; }
            exchange.sendResponseHeaders(500, 100000);
            exchange.getResponseBody().write("<Error><Message>".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[response == Mode.ERROR_DRIBBLE ? 1 : 1024];
            Arrays.fill(chunk, (byte) 'a');
            for (int sent = 0; sent < 100000; sent += chunk.length) {
                exchange.getResponseBody().write(chunk);
                exchange.getResponseBody().flush();
                if (response == Mode.ERROR_DRIBBLE) { Thread.sleep(20); }
            }
        }

        void load() throws IOException {
            try (var paths = Files.list(root)) {
                for (Path path : paths.filter(value -> value.getFileName().toString().matches("[a-f0-9-]{36}")).toList()) {
                    var metadata = new Properties();
                    try (var input = Files.newInputStream(root.resolve(path.getFileName() + ".meta"))) { metadata.load(input); }
                    objects.put("/changeo-r2-preparation/private-blobs/" + path.getFileName(), new Stored(Files.readAllBytes(path), metadata.getProperty("type"), metadata.getProperty("etag")));
                }
            }
        }

        @Override public void close() throws InterruptedException {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        record Stored(byte[] bytes, String type, String etag) { }
        record Request(String method, String path, Headers headers, byte[] bytes) { }
        record Gate(CountDownLatch entered, CountDownLatch release) { }
    }
}
