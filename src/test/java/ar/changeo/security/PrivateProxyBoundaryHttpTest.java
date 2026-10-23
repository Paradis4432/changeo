package ar.changeo.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.valves.RemoteCIDRValve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import static ar.changeo.security.PrivateHttpsTestFixture.*;
import static ar.changeo.security.PrivateHttpsOriginHttpTest.rejection;
import static org.assertj.core.api.Assertions.*;

class PrivateProxyBoundaryHttpTest {
    @Test
    void shouldEnforceActualPeerThenHeaderContractBeforeNativeRewritingAndAllApplicationEffects() throws Exception {
        try (App app = start(Mode.PROXY)) {
            var server = (TomcatWebServer) ((ServletWebServerApplicationContext) app.context).getWebServer();
            var valves = server.getTomcat().getEngine().getPipeline().getValves();
            assertThat(valves[0]).isInstanceOf(RemoteCIDRValve.class);
            assertThat(valves[1]).isInstanceOf(ForwardingGate.class);
            assertThat(valves[2]).isInstanceOf(RemoteIpValve.class);
            assertThat(java.util.Arrays.stream(valves).filter(RemoteIpValve.class::isInstance).count()).isEqualTo(1);
            var rows = app.rows(true);
            var effects = app.counters.effects();
            Reply untrusted = backend("127.0.0.2", "GET /readyz HTTP/1.1",
                    List.of("X-Forwarded-For: 127.0.0.1", "X-Forwarded-Proto: https"), "");
            assertThat(untrusted.status()).isEqualTo(403);
            assertThat(untrusted.body()).doesNotContain("Tomcat", "Exception", "127.0.0.2");
            assertThat(untrusted.header("set-cookie")).isEmpty();
            List<List<String>> invalid = List.of(List.of(), List.of("X-Forwarded-For: 127.0.0.2"),
                    List.of("X-Forwarded-For: 127.0.0.2", "X-Forwarded-Proto: http"),
                    List.of("X-Forwarded-For: 127.0.0.2", "X-Forwarded-Proto: https", "X-Forwarded-Proto: https"),
                    List.of("X-Forwarded-For: 127.0.0.2", "X-Forwarded-For: 127.0.0.2", "X-Forwarded-Proto: https"));
            for (List<String> headers : invalid) {
                rejection(backend("127.0.0.1", "POST /moderation/own HTTP/1.1", headers, ""), 403);
            }
            for (String value : List.of("", "localhost", "999.0.0.1", "127.0.0.01", "\"127.0.0.2\"", "127.0.0.2:1",
                    "unknown", "127.0.0.2,", "127.0.0.2,,127.0.0.3", "1.1.1.1,2.2.2.2,3.3.3.3,4.4.4.4,5.5.5.5", "1".repeat(257))) {
                rejection(backend("127.0.0.1", "POST /moderation/own HTTP/1.1",
                        List.of("X-Forwarded-For: " + value, "X-Forwarded-Proto: https"), ""), 403);
            }
            for (String name : List.of("Forwarded", "X-Forwarded-Host", "X-Forwarded-Port", "X-Forwarded-Prefix",
                    "X-Forwarded-By", "X-Forwarded-Ssl", "X-Forwarded-Server", "X-Forwarded-Other")) {
                rejection(backend("127.0.0.1", "POST /moderation/own HTTP/1.1",
                        List.of("X-Forwarded-For: 127.0.0.2", "X-Forwarded-Proto: https", name + ": ignored"), ""), 403);
            }
            assertThat(app.counters.effects()).isEqualTo(effects);
            assertThat(app.rows(true)).isEqualTo(rows);
            Reply admitted = backend("127.0.0.1", "GET /readyz HTTP/1.1",
                    List.of("X-Forwarded-For: 198.51.100.1, 203.0.113.9", "X-Forwarded-Proto: https"), "");
            assertThat(admitted.status()).isEqualTo(200);
            assertThat(admitted.body()).isEqualTo("ready\n");
            assertThat(app.counters.remote.get()).isEqualTo("203.0.113.9");
            for (int attempt = 0; attempt < 10; attempt++) {
                var chained = chain("198.51.100." + (attempt + 1) + ", 203.0.113.9");
                assertThat(chained.post("/login", form(Map.of("username", "chain-" + attempt + "@example.test",
                        "password", "Incorrect-password-12345", "_csrf", chained.csrf("/login")))).header("location"))
                        .endsWith("/login?error");
            }
            var sameRightmost = chain("198.51.100.99, 203.0.113.9");
            assertThat(sameRightmost.post("/login", form(Map.of("username", "chain-other@example.test",
                    "password", "Incorrect-password-12345", "_csrf", sameRightmost.csrf("/login")))).header("location"))
                    .endsWith("/login?limited");
            var differentRightmost = chain("198.51.100.99, 203.0.113.10");
            assertThat(differentRightmost.post("/login", form(Map.of("username", "chain-independent@example.test",
                    "password", "Incorrect-password-12345", "_csrf", differentRightmost.csrf("/login")))).header("location"))
                    .endsWith("/login?error");
        }
    }

    @Test
    void shouldResetEdgeHeadersAndFeedGenuineDistinctSourcesToRealLoginAndModerationLimits() throws Exception {
        try (App app = start(Mode.PROXY); Edge edge = new Edge(BACKEND_PORT)) {
            var one = new Browser(EDGE_PORT, true, "127.0.0.2");
            var two = new Browser(EDGE_PORT, true, "127.0.0.3");
            Reply forged = one.request("GET", "/readyz", List.of("Forwarded: for=192.0.2.99;proto=http",
                    "X-Forwarded-For: 192.0.2.99", "X-Forwarded-Proto: http", "X-Forwarded-Host: evil.test",
                    "X-Forwarded-Port: 80", "X-Forwarded-Prefix: /evil", "Connection: X-Forwarded-Other",
                    "X-Forwarded-Other: evil"), "");
            assertThat(forged.status()).isEqualTo(200);
            assertThat(app.counters.remote.get()).isEqualTo("127.0.0.2");
            String csrf = one.csrf("/login");
            for (int attempt = 0; attempt < 10; attempt++) {
                Reply denied = one.post("/login", form(Map.of("username", "missing-" + attempt + "@example.test",
                        "password", "Incorrect-password-12345", "_csrf", csrf)));
                assertThat(denied.header("location")).endsWith("/login?error");
            }
            assertThat(one.post("/login", form(Map.of("username", "other@example.test", "password", PASSWORD, "_csrf", csrf)))
                    .header("location")).endsWith("/login?limited");
            Account actor = app.actor();
            assertThat(two.login(actor).header("location")).endsWith("/account");
            assertThat(app.counters.remote.get()).isEqualTo("127.0.0.3");
            app.clock().advance(61);
            String shared = "same-account@example.test";
            for (int attempt = 0; attempt < 10; attempt++) {
                Browser source = attempt % 2 == 0 ? one : two;
                assertThat(source.post("/login", form(Map.of("username", attempt % 2 == 0 ? shared : " SAME-ACCOUNT@EXAMPLE.TEST ",
                        "password", PASSWORD, "_csrf", source.csrf("/login")))).header("location")).endsWith("/login?error");
            }
            assertThat(two.post("/login", form(Map.of("username", shared, "password", PASSWORD, "_csrf", two.csrf("/login")))))
                    .extracting(reply -> reply.header("location")).asString().endsWith("/login?limited");
            app.clock().advance(61);
            for (int attempt = 0; attempt < 20; attempt++) {
                assertThat(one.post("/moderation/own", "").status()).isEqualTo(403);
            }
            assertThat(one.post("/moderation/own", "").status()).isEqualTo(429);
            assertThat(two.post("/moderation/own", "").status()).isEqualTo(403);
            assertThat(edge.maximumActive.get()).isLessThanOrEqualTo(4);
            assertThat(edge.maximumQueued.get()).isLessThanOrEqualTo(8);
        }
    }

    @Test
    void shouldApplyMfaSourceAcrossActorsAndAccountLimitsAcrossGenuineSources() throws Exception {
        try (App app = start(Mode.PROXY); Edge edge = new Edge(BACKEND_PORT)) {
            var operator = app.operator();
            List<Account> ordinary = new ArrayList<>();
            for (int index = 0; index < 5; index++) { ordinary.add(app.actor()); }
            for (Account account : ordinary) {
                var browser = new Browser(EDGE_PORT, true, "127.0.0.2");
                assertThat(browser.login(account).status()).isEqualTo(302);
                Reply denied = browser.post("/admin/mfa", form(Map.of("code", "000000", "_csrf", browser.csrf("/admin/mfa"))));
                assertThat(denied.status()).isEqualTo(403);
                assertThat(denied.body()).contains("MFA no disponible");
            }
            var blocked = new Browser(EDGE_PORT, true, "127.0.0.2");
            assertThat(blocked.login(operator).status()).isEqualTo(302);
            Reply denied = blocked.post("/admin/mfa", form(Map.of("code", totp(operator, app.clock()), "_csrf", blocked.csrf("/admin/mfa"))));
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.body()).contains("Demasiados intentos");
            var independent = new Browser(EDGE_PORT, true, "127.0.0.3");
            assertThat(independent.login(operator).status()).isEqualTo(302);
            assertThat(independent.post("/admin/mfa", form(Map.of("code", totp(operator, app.clock()),
                    "_csrf", independent.csrf("/admin/mfa")))).header("location")).endsWith("/admin/reauth");
            app.clock().advance(61);
            var sourceOne = new Browser(EDGE_PORT, true, "127.0.0.2");
            var sourceTwo = new Browser(EDGE_PORT, true, "127.0.0.3");
            assertThat(sourceOne.login(ordinary.getFirst()).status()).isEqualTo(302);
            assertThat(sourceTwo.login(ordinary.getFirst()).status()).isEqualTo(302);
            for (int attempt = 0; attempt < 5; attempt++) {
                Browser source = attempt % 2 == 0 ? sourceOne : sourceTwo;
                assertThat(source.post("/admin/mfa", form(Map.of("code", "000000", "_csrf", source.csrf("/admin/mfa")))).body())
                        .contains("MFA no disponible");
            }
            assertThat(sourceTwo.post("/admin/mfa", form(Map.of("code", "000000", "_csrf", sourceTwo.csrf("/admin/mfa")))).body())
                    .contains("Demasiados intentos");
        }
    }

    private static Reply backend(String source, String request, List<String> headers, String body) throws Exception {
        List<String> complete = new ArrayList<>();
        complete.add("Host: 127.0.0.1:" + EDGE_PORT);
        complete.addAll(headers);
        return wire(BACKEND_PORT, false, source, request, complete, body);
    }

    private static Browser chain(String chain) {
        return new Browser(BACKEND_PORT, false, "127.0.0.1", "127.0.0.1:" + EDGE_PORT,
                List.of("X-Forwarded-For: " + chain, "X-Forwarded-Proto: https"));
    }

    @Test
    void shouldBoundEdgeBodiesDeadlinesConcurrencyAndRecoverAfterRepeatedFaults() throws Exception {
        try (FaultBackend backend = new FaultBackend(); Edge edge = new Edge(backend.port())) {
            var browser = new Browser(EDGE_PORT, true, "127.0.0.2");
            assertThat(browser.post("/login", "x".repeat(65537)).status()).isEqualTo(413);
            assertThat(backend.connections.get()).isZero();
            assertThat(wire(EDGE_PORT, true, "127.0.0.2", "GET /readyz HTTP/1.1",
                    List.of("Host: 127.0.0.1:" + EDGE_PORT, "Host: 127.0.0.1:" + EDGE_PORT), "").status()).isEqualTo(400);
            assertThat(backend.connections.get()).isZero();
            backend.response.set(Fault.OVERSIZED);
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThat(browser.get("/readyz").status()).isEqualTo(502);
            }
            backend.response.set(Fault.EXACT);
            assertThat(browser.get("/readyz").bytes()).hasSize(MAX_RESPONSE);
            backend.response.set(Fault.DRIBBLE);
            long started = System.nanoTime();
            assertThatThrownBy(() -> slowEdgeRequest("GET /readyz HTTP/1.1\r\nHost: 127.0.0.1:" + EDGE_PORT
                    + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")).isInstanceOf(IOException.class);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsed).isBetween(4000L, 5000L);
            backend.response.set(Fault.SMALL);
            assertThat(browser.get("/readyz").body()).isEqualTo("ready\n");
            long bodyStarted = System.nanoTime();
            assertThat(slowEdgeRequest("POST /login HTTP/1.1\r\nHost: 127.0.0.1:" + EDGE_PORT
                    + "\r\nConnection: close\r\nContent-Length: 100\r\n\r\nx").status()).isEqualTo(502);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bodyStarted)).isLessThan(3000L);
            assertThat(browser.get("/readyz").status()).isEqualTo(200);
            java.nio.file.Files.writeString(ROOT.resolve("private/edge-fault-timing.private"), "dribbleDeadlineMillis=" + elapsed + "\n");
        }
        try (FaultBackend backend = new FaultBackend(); Edge edge = new Edge(backend.port())) {
            List<Socket> clients = new ArrayList<>();
            try {
                for (int index = 0; index < 13; index++) {
                    var client = new Socket();
                    client.connect(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), EDGE_PORT), 1000);
                    clients.add(client);
                    long acceptedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (edge.accepted.get() < index + 1 && System.nanoTime() < acceptedDeadline) { Thread.sleep(2); }
                    assertThat(edge.accepted.get()).isEqualTo(index + 1);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (edge.rejected.get() == 0 && System.nanoTime() < deadline) { Thread.sleep(5); }
                assertThat(edge.rejected.get()).isGreaterThanOrEqualTo(1);
                assertThat(edge.maximumActive.get()).isEqualTo(4);
                assertThat(edge.maximumQueued.get()).isEqualTo(8);
            } finally {
                for (Socket client : clients) { client.close(); }
            }
        }
    }

    private static Reply slowEdgeRequest(String request) throws Exception {
        try (var socket = (javax.net.ssl.SSLSocket) tls().getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), EDGE_PORT), 2000);
            socket.setSoTimeout(6000);
            var parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readReply(socket.getInputStream());
        }
    }

    enum Fault { SMALL, OVERSIZED, EXACT, DRIBBLE }

    static final class FaultBackend implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("ph1-fault-backend-", 0).factory());
        private final Thread acceptor;
        final AtomicReference<Fault> response = new AtomicReference<>(Fault.SMALL);
        final AtomicInteger connections = new AtomicInteger();

        FaultBackend() throws IOException {
            listener.bind(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0));
            acceptor = Thread.ofPlatform().name("ph1-fault-accept").start(() -> {
                while (!closed.get()) {
                    try {
                        Socket socket = listener.accept();
                        socket.setSoTimeout(2000);
                        sockets.add(socket);
                        connections.incrementAndGet();
                        Fault fault = response.get();
                        workers.submit(() -> respond(socket, fault));
                    } catch (IOException failure) {
                        if (!closed.get()) { throw new java.io.UncheckedIOException(failure); }
                    }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        private void respond(Socket socket, Fault fault) {
            try (socket) {
                line(socket.getInputStream(), 4096);
                headers(socket.getInputStream());
                int size = switch (fault) {
                    case OVERSIZED -> MAX_RESPONSE + 1;
                    case EXACT -> MAX_RESPONSE;
                    default -> 6;
                };
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + size + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                if (fault == Fault.EXACT) {
                    socket.getOutputStream().write(new byte[MAX_RESPONSE]);
                } else if (fault == Fault.DRIBBLE) {
                    for (int index = 0; index < 6 && !closed.get(); index++) {
                        socket.getOutputStream().write('x');
                        socket.getOutputStream().flush();
                        Thread.sleep(1000);
                    }
                } else if (fault == Fault.SMALL) {
                    socket.getOutputStream().write("ready\n".getBytes(StandardCharsets.US_ASCII));
                }
                socket.getOutputStream().flush();
            } catch (IOException expectedFixtureTermination) {
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { sockets.remove(socket); }
        }

        @Override
        public void close() throws Exception {
            closed.set(true);
            listener.close();
            for (Socket socket : sockets) { socket.close(); }
            workers.shutdownNow();
            acceptor.join(Duration.ofSeconds(3));
            assertThat(acceptor.isAlive()).isFalse();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(sockets).isEmpty();
        }
    }

    static final class Edge implements AutoCloseable {
        private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                "te", "trailer", "transfer-encoding", "upgrade", "content-length", "expect");
        private final ServerSocket listener;
        private final int backendPort;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ThreadPoolExecutor exchanges = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("ph1-edge-exchange-", 0).factory());
        private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("ph1-edge-deadline-", 0).factory());
        private final Thread acceptor;
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumActive = new AtomicInteger();
        final AtomicInteger maximumQueued = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Edge(int backendPort) throws Exception {
            this.backendPort = backendPort;
            deadlines.setRemoveOnCancelPolicy(true);
            listener = tls().getServerSocketFactory().createServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), EDGE_PORT), 8);
            acceptor = Thread.ofPlatform().name("ph1-edge-accept").start(this::accept);
        }

        private void accept() {
            while (!closed.get()) {
                try {
                    Socket client = listener.accept();
                    accepted.incrementAndGet();
                    client.setSoTimeout(2000);
                    sockets.add(client);
                    var backend = new AtomicReference<Socket>();
                    var deadline = deadlines.schedule(() -> {
                        closeSocket(client);
                        closeSocket(backend.get());
                    }, 4500, TimeUnit.MILLISECONDS);
                    try {
                        exchanges.execute(() -> {
                            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                            try (client) {
                                try {
                                    exchange(client, backend);
                                } catch (IOException failure) {
                                    if (!closed.get() && !client.isClosed()) { fixed(client, 502); }
                                }
                            } catch (IOException failure) {
                                unexpected.compareAndSet(null, failure);
                            } finally {
                                deadline.cancel(false);
                                closeSocket(backend.get());
                                sockets.remove(client);
                                active.decrementAndGet();
                            }
                        });
                        maximumQueued.accumulateAndGet(exchanges.getQueue().size(), Math::max);
                    } catch (RejectedExecutionException full) {
                        rejected.incrementAndGet();
                        deadline.cancel(false);
                        closeSocket(client);
                        sockets.remove(client);
                    }
                } catch (IOException failure) {
                    if (!closed.get()) { unexpected.compareAndSet(null, failure); }
                }
            }
        }

        private void exchange(Socket client, AtomicReference<Socket> backendReference) throws IOException {
            String request = line(client.getInputStream(), 4096);
            if (request == null) { return; }
            String[] parts = request.split(" ", -1);
            Map<String, List<String>> headers = headers(client.getInputStream());
            List<String> host = headers.getOrDefault("host", List.of());
            if (parts.length != 3 || !parts[0].matches("GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE")
                    || !parts[1].startsWith("/") || parts[1].startsWith("//") || !parts[2].equals("HTTP/1.1")
                    || host.size() != 1 || !host.getFirst().equals("127.0.0.1:" + EDGE_PORT)
                    || headers.containsKey("transfer-encoding") || headers.containsKey("upgrade") || headers.containsKey("expect")
                    || headers.getOrDefault("content-length", List.of("0")).size() != 1) {
                fixed(client, 400);
                return;
            }
            int length;
            try { length = Integer.parseInt(headers.getOrDefault("content-length", List.of("0")).getFirst()); }
            catch (NumberFormatException failure) { fixed(client, 400); return; }
            if (length < 0 || length > 65536) { fixed(client, 413); return; }
            byte[] body = exact(client.getInputStream(), length);
            Set<String> remove = new HashSet<>(HOP);
            for (String value : headers.getOrDefault("connection", List.of())) {
                for (String name : value.split(",")) { remove.add(name.strip().toLowerCase(Locale.ROOT)); }
            }
            var outbound = new StringBuilder(request).append("\r\nHost: 127.0.0.1:").append(EDGE_PORT).append("\r\n");
            headers.forEach((name, values) -> {
                if (!remove.contains(name) && !name.equals("host") && !name.equals("forwarded") && !name.startsWith("x-forwarded-")) {
                    values.forEach(value -> outbound.append(name).append(": ").append(value).append("\r\n"));
                }
            });
            outbound.append("X-Forwarded-For: ").append(client.getInetAddress().getHostAddress())
                    .append("\r\nX-Forwarded-Proto: https\r\nConnection: close\r\nContent-Length: ").append(body.length).append("\r\n\r\n");
            try (Socket backend = new Socket()) {
                sockets.add(backend);
                backendReference.set(backend);
                try {
                    backend.connect(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), backendPort), 2000);
                    backend.setSoTimeout(2000);
                    backend.getOutputStream().write(outbound.toString().getBytes(StandardCharsets.US_ASCII));
                    backend.getOutputStream().write(body);
                    backend.getOutputStream().flush();
                    Reply response = readReply(backend.getInputStream());
                    StringBuilder output = new StringBuilder("HTTP/1.1 ").append(response.status()).append(" Response\r\n");
                    Set<String> responseRemove = new HashSet<>(HOP);
                    for (String value : response.headers().getOrDefault("connection", List.of())) {
                        for (String name : value.split(",")) { responseRemove.add(name.strip().toLowerCase(Locale.ROOT)); }
                    }
                    response.headers().forEach((name, values) -> {
                        if (!responseRemove.contains(name)) {
                            values.forEach(value -> output.append(name).append(": ").append(value).append("\r\n"));
                        }
                    });
                    output.append("Connection: close\r\nContent-Length: ").append(response.bytes().length).append("\r\n\r\n");
                    client.getOutputStream().write(output.toString().getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(response.bytes());
                    client.getOutputStream().flush();
                } finally { sockets.remove(backend); }
            }
        }

        private static void fixed(Socket client, int status) {
            try {
                client.getOutputStream().write(("HTTP/1.1 " + status + " Rejected\r\nContent-Type: text/plain;charset=UTF-8\r\n"
                        + "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\nContent-Length: 9\r\n\r\nrejected\n")
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            } catch (IOException closed) {
                return;
            }
        }

        private static void closeSocket(Socket socket) {
            if (socket != null) {
                try { socket.close(); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
        }

        @Override
        public void close() throws Exception {
            closed.set(true);
            listener.close();
            sockets.forEach(Edge::closeSocket);
            exchanges.shutdownNow();
            deadlines.shutdownNow();
            acceptor.join(Duration.ofSeconds(3));
            assertThat(acceptor.isAlive()).isFalse();
            assertThat(exchanges.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(deadlines.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isZero();
            assertThat(unexpected.get()).isNull();
            assertThat(sockets.stream().allMatch(Socket::isClosed)).isTrue();
        }
    }
}
