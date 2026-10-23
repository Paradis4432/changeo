package ar.changeo.identity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import tools.jackson.databind.ObjectMapper;

final class ResendTokenClient implements AutoCloseable {
    private final URI endpoint;
    private final String credential;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Limits limits;

    ResendTokenClient(URI endpoint, String credential, ObjectMapper json) {
        this(endpoint, credential, json, new Limits(Duration.ofSeconds(2), Duration.ofSeconds(5), 16384));
    }

    ResendTokenClient(URI endpoint, String credential, ObjectMapper json, Limits limits) {
        if (!Set.of("127.0.0.1", "[::1]", "::1").contains(endpoint.getHost()) || !"http".equals(endpoint.getScheme())
                || endpoint.getPort() < 1 || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !Set.of("", "/").contains(endpoint.getPath())) { throw new IllegalArgumentException("Preparation requires a literal loopback HTTP origin"); }
        if (credential == null || !credential.matches("[A-Za-z0-9_-]{8,256}")) { throw new IllegalArgumentException("Private delivery credential unavailable"); }
        this.endpoint = endpoint; this.credential = credential; this.json = json; this.limits = limits;
        this.http = HttpClient.newBuilder().connectTimeout(limits.connect()).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).version(HttpClient.Version.HTTP_1_1).build();
    }

    Result send(UUID delivery, byte[] body) {
        if (body.length == 0 || body.length > 8192) { return Result.of(Outcome.REJECTED, "REQUEST_BOUND"); }
        return exchange(HttpRequest.newBuilder(endpoint.resolve("/emails")).header("Idempotency-Key", "identity-token/" + delivery)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)), Optional.empty());
    }

    Result query(UUID providerId) {
        return exchange(HttpRequest.newBuilder(endpoint.resolve("/emails/" + providerId)).GET(), Optional.of(providerId));
    }

    private Result exchange(HttpRequest.Builder builder, Optional<UUID> expected) {
        var request = builder.timeout(limits.request()).header("Authorization", "Bearer " + credential).header("Accept", "application/json").build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new BoundedBody(limits.maximumBytes()));
        try {
            var response = pending.get(limits.request().toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status >= 300 && status < 400) { return Result.of(Outcome.UNKNOWN, "REDIRECT_REFUSED"); }
            if (status >= 500) { return Result.of(Outcome.UNKNOWN, "PROVIDER_UNAVAILABLE"); }
            if (status == 429) { return new Result(Outcome.RETRYABLE, Optional.empty(), "RATE_LIMIT", retryAfter(response)); }
            if (status == 401 || status == 403) { return Result.of(Outcome.REJECTED, expected.isPresent() ? "QUERY_PERMISSION" : "AUTHORIZATION_REJECTED"); }
            var node = json.readTree(response.body());
            if (status == 409) {
                return switch (node.path("name").asString("")) {
                    case "concurrent_idempotent_requests" -> new Result(Outcome.RETRYABLE, Optional.empty(), "IDEMPOTENCY_IN_PROGRESS", retryAfter(response));
                    case "invalid_idempotent_request" -> Result.of(Outcome.REJECTED, "IDEMPOTENCY_MISMATCH");
                    default -> Result.of(Outcome.UNKNOWN, "CONFLICT_UNRESOLVED");
                };
            }
            if (status == 400 || status == 422) { return Result.of(Outcome.REJECTED, "REQUEST_REJECTED"); }
            if (status < 200 || status >= 300) { return Result.of(Outcome.UNKNOWN, "STATUS_UNRESOLVED"); }
            String value = node.path("id").asString("");
            if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) { return Result.of(Outcome.UNKNOWN, "ACKNOWLEDGEMENT_INVALID"); }
            UUID id = UUID.fromString(value);
            if (expected.isEmpty()) { return new Result(Outcome.ACCEPTED, Optional.of(id), "PROVIDER_ACCEPTED", 0); }
            if (!id.equals(expected.orElseThrow())) { return Result.of(Outcome.UNKNOWN, "QUERY_ID_MISMATCH"); }
            String event = node.path("last_event").asString("");
            return switch (event) {
                case "delivered" -> new Result(Outcome.DELIVERED, Optional.of(id), "RECIPIENT_SERVER_DELIVERED", 0);
                case "failed", "bounced", "suppressed", "canceled" -> new Result(Outcome.TERMINAL_FAILURE, Optional.of(id), "EVENT_" + event.toUpperCase(Locale.ROOT), 0);
                case "sent", "queued", "scheduled", "delivery_delayed", "opened", "clicked", "complained" -> new Result(Outcome.PENDING, Optional.of(id), "EVENT_" + event.toUpperCase(Locale.ROOT), 0);
                default -> new Result(Outcome.PENDING, Optional.of(id), "EVENT_UNRESOLVED", 0);
            };
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); pending.cancel(true); return Result.of(Outcome.UNKNOWN, "TRANSPORT_INTERRUPTED");
        } catch (TimeoutException | ExecutionException | tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            pending.cancel(true); return Result.of(Outcome.UNKNOWN, "TRANSPORT_UNRESOLVED");
        }
    }

    private long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        if (value.length() > 64) { return 0; }
        try { return Math.min(300, Math.max(0, Long.parseLong(value))); }
        catch (NumberFormatException failure) {
            try { return Math.min(300, Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds())); }
            catch (java.time.DateTimeException invalid) { return 0; }
        }
    }

    @Override public void close() {
        http.shutdownNow();
        try { http.awaitTermination(Duration.ofSeconds(2)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
    }

    enum Outcome { ACCEPTED, DELIVERED, PENDING, TERMINAL_FAILURE, REJECTED, RETRYABLE, UNKNOWN }
    record Result(Outcome outcome, Optional<UUID> providerId, String reason, long retrySeconds) {
        static Result of(Outcome outcome, String reason) { return new Result(outcome, Optional.empty(), reason, 0); }
    }
    record Limits(Duration connect, Duration request, int maximumBytes) {
        Limits {
            if (connect.isNegative() || connect.isZero() || connect.compareTo(Duration.ofSeconds(5)) > 0 || request.toMillis() < 1
                    || request.compareTo(Duration.ofSeconds(10)) > 0 || maximumBytes < 128 || maximumBytes > 65536) { throw new IllegalArgumentException("Invalid delivery bounds"); }
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            result.whenComplete((body, error) -> { if (result.isCancelled()) { subscription.cancel(); } });
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Response exceeds bound")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new IOException("Response unavailable")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
