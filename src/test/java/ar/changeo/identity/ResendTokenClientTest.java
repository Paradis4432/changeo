package ar.changeo.identity;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ResendTokenClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private URI endpoint;
    private final UUID provider = UUID.randomUUID();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>();
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final String credential = "synthetic-private-credential";

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        response.set("{\"id\":\"" + provider + "\"}");
        server.createContext("/", exchange -> {
            try {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + credential);
                paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().toString());
                keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                bodies.add(exchange.getRequestBody().readAllBytes());
                exchange.getResponseHeaders().add("Location", endpoint + "/leak");
                exchange.getResponseHeaders().add("Retry-After", "999999");
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start(); endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void close() { server.stop(0); executor.close(); }
    private ResendTokenClient client() { return new ResendTokenClient(endpoint, credential, JsonMapper.builder().build()); }

    @Test void shouldUseExactFrozenBodyStableKeyAuthenticationAndIdBasedInquiry() {
        UUID delivery = UUID.randomUUID(); byte[] body = "{\"from\":\"sender@example.test\",\"to\":[\"recipient@example.test\"],\"subject\":\"Código\",\"text\":\"Secreto\\n privado\"}".getBytes(StandardCharsets.UTF_8);
        try (var client = client()) {
            assertThat(client.send(delivery, body).outcome()).isEqualTo(ResendTokenClient.Outcome.ACCEPTED);
            assertThat(client.send(delivery, body).providerId()).contains(provider);
            response.set("{\"id\":\"" + provider + "\",\"last_event\":\"delivered\",\"text\":\"private provider content\"}");
            assertThat(client.query(provider).outcome()).isEqualTo(ResendTokenClient.Outcome.DELIVERED);
        }
        assertThat(paths).containsExactly("POST /emails", "POST /emails", "GET /emails/" + provider);
        assertThat(keys).containsExactly("identity-token/" + delivery, "identity-token/" + delivery, "null");
        assertThat(bodies.get(0)).isEqualTo(body); assertThat(bodies.get(1)).isEqualTo(body); assertThat(bodies.get(2)).isEmpty();
    }

    @ParameterizedTest @CsvSource({"sent,PENDING", "queued,PENDING", "scheduled,PENDING", "delivery_delayed,PENDING", "opened,PENDING", "clicked,PENDING", "complained,PENDING", "unknown,PENDING", "delivered,DELIVERED", "failed,TERMINAL_FAILURE", "bounced,TERMINAL_FAILURE", "suppressed,TERMINAL_FAILURE", "canceled,TERMINAL_FAILURE"})
    void shouldMapOnlyRecipientServerDelivery(String event, ResendTokenClient.Outcome expected) {
        response.set("{\"id\":\"" + provider + "\",\"last_event\":\"" + event + "\"}");
        try (var client = client()) { assertThat(client.query(provider).outcome()).isEqualTo(expected); }
    }

    @ParameterizedTest @CsvSource({"400,validation_error,REJECTED", "401,restricted_api_key,REJECTED", "403,invalid_permission,REJECTED", "409,concurrent_idempotent_requests,RETRYABLE", "409,invalid_idempotent_request,REJECTED", "409,unknown,UNKNOWN", "422,validation_error,REJECTED", "429,rate_limit_exceeded,RETRYABLE", "500,application_error,UNKNOWN", "503,service_unavailable,UNKNOWN", "302,redirect,UNKNOWN"})
    void shouldRedactProviderErrorsAndRefuseRedirects(int code, String name, ResendTokenClient.Outcome expected) {
        status.set(code); response.set("{\"name\":\"" + name + "\",\"message\":\"" + credential + " private-token-body\"}");
        try (var client = client()) {
            var result = client.send(UUID.randomUUID(), "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(result.outcome()).isEqualTo(expected);
            assertThat(result.toString()).doesNotContain(credential, "private-token-body");
            assertThat(result.retrySeconds()).isBetween(0L, 300L);
        }
        assertThat(paths).hasSize(1);
    }

    @Test void shouldRejectMalformedAcknowledgementMismatchedIdAndOversizedBody() {
        try (var client = client()) {
            for (String body : List.of("not-json", "{}", "{\"id\":\"1-1-1-1-1\"}", "x".repeat(200000))) {
                response.set(body); assertThat(client.send(UUID.randomUUID(), new byte[]{123,125}).outcome()).isEqualTo(ResendTokenClient.Outcome.UNKNOWN);
            }
            response.set("{\"id\":\"" + UUID.randomUUID() + "\",\"last_event\":\"delivered\"}");
            assertThat(client.query(provider).reason()).isEqualTo("QUERY_ID_MISMATCH");
            status.set(401); assertThat(client.query(provider).reason()).isEqualTo("QUERY_PERMISSION");
        }
    }

    @Test void shouldBoundSlowBodyAfterHeadersAndCloseOwnedRequest() throws Exception {
        server.removeContext("/"); CountDownLatch headers = new CountDownLatch(1); CountDownLatch finish = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); headers.countDown();
                finish.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        long start = System.nanoTime();
        try (var client = new ResendTokenClient(endpoint, credential, JsonMapper.builder().build(), new ResendTokenClient.Limits(Duration.ofMillis(200), Duration.ofMillis(250), 1024))) {
            assertThat(client.send(UUID.randomUUID(), new byte[]{123,125}).outcome()).isEqualTo(ResendTokenClient.Outcome.UNKNOWN);
            assertThat(headers.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { finish.countDown(); }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void shouldRejectLiveAndAmbiguousOriginsBeforeAnyConnection() {
        for (String origin : List.of("https://api.resend.com", "http://localhost:1234", "http://127.0.0.1:1234/path", "http://127.0.0.1:1234?secret", "http://user@127.0.0.1:1234")) {
            assertThatThrownBy(() -> new ResendTokenClient(URI.create(origin), credential, JsonMapper.builder().build())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(paths).isEmpty();
    }

    @Test void shouldDefaultToLocalAndRejectUnknownComposition() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(TokenDeliveryConfiguration.class)
                .withBean(TokenDelivery.class, () -> (id, purpose, raw) -> true);
        runner.run(context -> {
            assertThat(context).hasSingleBean(TokenDeliveryCoordinator.class);
            assertThat(context).doesNotHaveBean(DurableTokenDelivery.class);
            assertThat(context).doesNotHaveBean(TokenDeliveryWorker.class);
            assertThat(context).doesNotHaveBean(ResendTokenClient.class);
        });
        runner.withPropertyValues("changeo.token-delivery=unrecognized").run(context -> assertThat(context).hasFailed());
        assertThat(paths).isEmpty();
    }
}
