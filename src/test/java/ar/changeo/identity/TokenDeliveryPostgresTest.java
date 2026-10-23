package ar.changeo.identity;

import ar.changeo.ChangeoApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = {ChangeoApplication.class, TokenDeliveryPostgresTest.TestConfig.class}, properties = {"changeo.token-delivery=local"})
class TokenDeliveryPostgresTest {
    private static final Path RUNTIME = Path.of(required("ED_RUNTIME"));
    private static final String SCHEMA = required("ED_TEST_SCHEMA");
    @Autowired IdentityStore identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountService localAccounts;
    @Autowired MutableClock clock;
    @Autowired ObjectMapper json;
    private TokenDeliverySpool spool;
    private DurableTokenDelivery durable;
    private AccountService remote;
    private HttpServer server;
    private ExecutorService httpExecutor;
    private ResendTokenClient client;
    private TokenDeliveryWorker worker;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger code = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final UUID provider = UUID.randomUUID();

    @TestConfiguration static class TestConfig {
        @Bean @Primary MutableClock deliveryClock() { return new MutableClock(); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required("CHANGEO_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> required("CHANGEO_DATABASE_USER"));
        properties.add("spring.datasource.password", () -> required("CHANGEO_DATABASE_PASSWORD"));
        for (String name : List.of("spring.flyway.schemas", "spring.flyway.default-schema", "spring.jpa.properties.hibernate.default_schema", "spring.datasource.hikari.schema")) { properties.add(name, () -> SCHEMA); }
        properties.add("changeo.mailbox", () -> RUNTIME.resolve("local-mailbox").toString());
        properties.add("changeo.mfa-key", () -> RUNTIME.resolve("mfa-test.key").toString());
    }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), name + " required"); }

    @BeforeEach void setup() throws Exception {
        clock.reset();
        Path materialRoot = Boolean.getBoolean("email.delivery.restored") ? RUNTIME.resolve("restore") : RUNTIME;
        spool = new TokenDeliverySpool(materialRoot.resolve("spool"), materialRoot.resolve("spool.key"), json);
        durable = new DurableTokenDelivery(jdbc, identity, clock, spool, "sender@example.test");
        remote = new AccountService(identity, passwords, new TokenDeliveryCoordinator((id, purpose, raw) -> { throw new AssertionError("No local fallback"); }, Optional.of(durable)), clock);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); httpExecutor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(httpExecutor);
        response.set("{\"id\":\"" + provider + "\"}");
        server.createContext("/", exchange -> {
            try {
                requests.incrementAndGet(); keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key"))); bodies.add(exchange.getRequestBody().readAllBytes());
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (key != null) {
                    UUID delivery = UUID.fromString(key.substring("identity-token/".length()));
                    UUID account = intent(delivery).accountId();
                    tx(() -> { jdbc.queryForList("select id from identity_account where id=? for update nowait", account); return null; });
                }
                assertThat(jdbc.queryForObject("select count(*) from identity_token_delivery where state='DISPATCHING' or reason='QUERY_COMMITTED'", Integer.class)).isPositive();
                if (retryAfter.get() != null) { exchange.getResponseHeaders().set("Retry-After", retryAfter.get()); }
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(code.get(), bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        }); server.start();
        client = new ResendTokenClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "synthetic-credential", json);
        worker = new TokenDeliveryWorker(durable, client, transactions);
    }
    @AfterEach void close() { client.close(); server.stop(0); httpExecutor.close(); }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private UUID register() { return tx(() -> remote.register("delivery" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345").accountId().value()); }
    private UUID id(UUID account) { return jdbc.queryForObject("select id from identity_token_delivery where account_id=? order by created_at desc,id desc limit 1", UUID.class, account); }
    private DurableTokenDelivery.Intent intent(UUID id) { return durable.find(id, false).orElseThrow(); }
    private String raw(UUID id) { var current = intent(id); String hash = jdbc.queryForObject("select token_hash from identity_contact_token where id=?", String.class, current.tokenId()); return json.readTree(spool.open(current.binding(hash))).path("text").asString().split(": ")[1].split("\n")[0]; }
    private void advance(long seconds) { clock.advance(seconds); }

    @Test void shouldQueueCommitThenAcceptWithoutClaimingDeliveryAndObserveRecipientServer() {
        UUID account = register(); UUID id = id(account); String raw = raw(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.PENDING); assertThat(requests).hasValue(0);
        assertThat(jdbc.queryForObject("select consumed from identity_contact_token where id=?", Boolean.class, intent(id).tokenId())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from identity_audit_event where subject_id=? and action='TOKEN_DELIVERY_QUEUED_CONTACT'", Integer.class, account)).isEqualTo(1);
        assertThat(worker.process(id)).isTrue(); assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.ACCEPTED);
        assertThat(jdbc.queryForObject("select delivered_at from identity_token_delivery where id=?", java.sql.Timestamp.class, id)).isNull();
        advance(30); response.set("{\"id\":\"" + provider + "\",\"last_event\":\"opened\"}"); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.ACCEPTED);
        advance(30); response.set("{\"id\":\"" + provider + "\",\"last_event\":\"delivered\"}"); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.DELIVERED);
        localAccounts.verifyContact(account, raw); assertThatThrownBy(() -> localAccounts.verifyContact(account, raw)).isInstanceOf(IdentityFailure.class);
        assertThat(keys).containsExactly("identity-token/" + id, "null", "null");
        assertThat(jdbc.queryForObject("select to_jsonb(t)::text from identity_token_delivery t where id=?", String.class, id)).doesNotContain(raw, "@", "Código", "recipient", "Bearer");
    }

    @Test void shouldRollbackTokenIntentAndSupersessionKeepingOnlyEncryptedOrphan() throws Exception {
        UUID existing = register(); UUID existingId = id(existing); String original = raw(existingId);
        long filesBefore; try (var files = Files.list(RUNTIME.resolve("spool"))) { filesBefore = files.count(); }
        long rows = jdbc.queryForObject("select count(*) from identity_token_delivery", Long.class);
        new TransactionTemplate(transactions).executeWithoutResult(status -> { remote.resend(existing); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("select count(*) from identity_token_delivery", Long.class)).isEqualTo(rows);
        assertThat(raw(existingId)).isEqualTo(original);
        try (var files = Files.list(RUNTIME.resolve("spool"))) { assertThat(files.count()).isEqualTo(filesBefore + 1); }
        assertThat(jdbc.queryForObject("select consumed from identity_contact_token where id=?", Boolean.class, intent(existingId).tokenId())).isFalse();
        assertThat(requests).hasValue(0);
        tx(() -> { localAccounts.verifyContact(existing, original); return null; });
    }

    @ParameterizedTest @ValueSource(strings = {"consume", "supersede", "expire", "epoch"})
    void shouldCancelBeforeFirstDispatchWhenTokenIsInvalid(String mutation) {
        UUID account = register(); UUID id = id(account); String raw = raw(id);
        invalidate(account, id, raw, mutation);
        assertThat(worker.authorize(id)).isEmpty(); assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.CANCELLED);
        assertThat(intent(id).invalidReason()).isNotNull(); assertThat(requests).hasValue(0);
    }
    private void invalidate(UUID account, UUID id, String raw, String mutation) {
        switch (mutation) {
            case "consume" -> localAccounts.verifyContact(account, raw);
            case "supersede" -> tx(() -> remote.resend(account));
            case "expire" -> advance(1801);
            case "epoch" -> tx(() -> { identity.lockAccounts(List.of(account)); identity.account(account).securityEpoch++; return null; });
            default -> throw new IllegalArgumentException();
        }
    }

    @Test void shouldRetainAuthorizedLateAcceptanceAfterConsumptionWithoutRevivingToken() {
        UUID account = register(); UUID id = id(account); String raw = raw(id);
        var dispatch = worker.authorize(id).orElseThrow();
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.DISPATCHING);
        localAccounts.verifyContact(account, raw);
        var result = client.send(dispatch.id(), dispatch.body()); worker.observe(dispatch, result);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.ACCEPTED);
        assertThatThrownBy(() -> localAccounts.verifyContact(account, raw)).isInstanceOf(IdentityFailure.class);
        advance(30); response.set("{\"id\":\"" + provider + "\",\"last_event\":\"delivered\"}"); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.DELIVERED); assertThat(intent(id).invalidReason()).isEqualTo("TOKEN_CONSUMED");
        assertThat(keys).containsExactly("identity-token/" + id, "null");
    }

    @Test void shouldPreserveEarlierUncertaintyAcrossDefiniteLaterFailureAndInvalidation() {
        UUID account = register(); UUID id = id(account); code.set(503); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN); Instant first = intent(id).dispatchedAt();
        advance(5); code.set(401); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN); assertThat(intent(id).uncertainAt()).isNotNull();
        assertThat(intent(id).dispatchedAt()).isEqualTo(first); assertThat(intent(id).availableAt()).isNull();
        localAccounts.verifyContact(account, raw(id)); advance(60); assertThat(worker.process(id)).isFalse(); assertThat(requests).hasValue(2);
    }

    @ParameterizedTest @ValueSource(strings = {"consume", "supersede", "expire", "epoch"})
    void shouldStopRetryAfterInvalidationPreservingPossibleEarlierEffect(String mutation) {
        UUID account = register(); UUID id = id(account); String raw = raw(id);
        code.set(503); worker.process(id); invalidate(account, id, raw, mutation); advance(5);
        assertThat(worker.process(id)).isFalse();
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN);
        assertThat(intent(id).invalidReason()).isNotNull(); assertThat(intent(id).dispatchedAt()).isNotNull();
        assertThat(intent(id).uncertainAt()).isNotNull(); assertThat(requests).hasValue(1);
    }

    @Test void shouldRollbackCaughtParticipatingDenialWithoutExecutableIntent() {
        long rows = jdbc.queryForObject("select count(*) from identity_token_delivery", Long.class);
        assertThatThrownBy(() -> tx(() -> {
            var registration = remote.register("rollback" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345");
            try { localAccounts.verifyContact(registration.accountId().value(), "invalid"); }
            catch (IdentityFailure expected) { }
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from identity_token_delivery", Long.class)).isEqualTo(rows);
        assertThat(requests).hasValue(0);
    }

    @Test void shouldPreserveBindingAtActualPostgresTimestampPrecision() {
        var fractional = new AccountService(identity, passwords, new TokenDeliveryCoordinator((id, purpose, raw) -> false, Optional.of(durable)),
                Clock.fixed(clock.instant().plusNanos(123456789), ZoneOffset.UTC));
        UUID account = tx(() -> fractional.register("fractional" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345").accountId().value());
        UUID id = id(account); assertThat(raw(id)).hasSize(43); assertThat(worker.process(id)).isTrue();
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.ACCEPTED);
    }

    @Test void shouldNeverEraseAcceptanceOnTerminalFailureObservation() {
        UUID id = id(register()); worker.process(id); Instant dispatched = intent(id).dispatchedAt(); advance(30);
        response.set("{\"id\":\"" + provider + "\",\"last_event\":\"bounced\"}"); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.FAILED);
        assertThat(intent(id).providerId()).isEqualTo(provider); assertThat(intent(id).dispatchedAt()).isEqualTo(dispatched);
        assertThat(jdbc.queryForObject("select accepted_at is not null and observed_at is not null from identity_token_delivery where id=?", Boolean.class, id)).isTrue();
        assertThatThrownBy(() -> jdbc.update("update identity_token_delivery set dispatched_at=null where id=?", id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void shouldRetrySameExactPayloadAtMostThreeTimesAndRetainUnknown() {
        UUID account = register(); UUID id = id(account); code.set(503);
        assertThat(worker.process(id)).isTrue(); advance(4); assertThat(worker.process(id)).isFalse(); advance(1); worker.process(id);
        advance(29); assertThat(worker.process(id)).isFalse(); advance(1); worker.process(id); advance(300);
        assertThat(worker.process(id)).isFalse(); assertThat(intent(id).attempts()).isEqualTo(3); assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN);
        assertThat(keys).containsExactly("identity-token/" + id, "identity-token/" + id, "identity-token/" + id);
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0)); assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
    }

    @Test void shouldPreserveInProgressIdempotentEffectAcrossLaterRejection() {
        UUID id = id(register()); code.set(409); response.set("{\"name\":\"concurrent_idempotent_requests\"}"); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN);
        assertThat(intent(id).uncertainAt()).isNotNull();
        advance(5); code.set(401); worker.process(id);
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN);
        assertThat(intent(id).attempts()).isEqualTo(2); assertThat(intent(id).availableAt()).isNull();
    }

    @Test void shouldFenceStaleLeaseOutcomeAndSerializeConcurrentWorkers() throws Exception {
        UUID account = register(); UUID id = id(account);
        var first = worker.authorize(id).orElseThrow(); assertThat(worker.authorize(id)).isEmpty(); advance(31);
        var second = worker.authorize(id).orElseThrow();
        worker.observe(first, new ResendTokenClient.Result(ResendTokenClient.Outcome.ACCEPTED, Optional.of(provider), "PROVIDER_ACCEPTED", 0));
        assertThat(intent(id).providerId()).isNull(); assertThat(intent(id).leaseGeneration()).isEqualTo(second.generation());
        worker.observe(second, ResendTokenClient.Result.of(ResendTokenClient.Outcome.REJECTED, "REQUEST_REJECTED"));
        assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN);
        UUID other = id(register()); CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { await(start); return worker.authorize(other); }); var b = pool.submit(() -> { await(start); return worker.authorize(other); }); start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(intent(other).attempts()).isEqualTo(1);
    }

    @Test void shouldSerializeBothAccountMutationAndCommittedAuthorizationOrders() throws Exception {
        for (boolean dispatchFirst : List.of(true, false)) {
            UUID account = register(); UUID id = id(account); String raw = raw(id); CountDownLatch locked = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var holder = pool.submit(() -> tx(() -> {
                    identity.lockAccounts(List.of(account));
                    if (!dispatchFirst) { localAccounts.verifyContact(account, raw); }
                    locked.countDown(); await(release); return null;
                })); await(locked);
                var contender = pool.submit(() -> worker.authorize(id));
                assertThat(contender.isDone()).isFalse(); release.countDown(); holder.get(5, TimeUnit.SECONDS);
                var authorization = contender.get(5, TimeUnit.SECONDS);
                if (dispatchFirst) { assertThat(authorization).isPresent(); localAccounts.verifyContact(account, raw); }
                else { assertThat(authorization).isEmpty(); assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.CANCELLED); }
                assertThatThrownBy(() -> localAccounts.verifyContact(account, raw)).isInstanceOf(IdentityFailure.class);
            }
        }
    }

    @Test void shouldStopMissingOrWrongMaterialWithoutLosingPossibleEffect() throws Exception {
        UUID account = register(); UUID id = id(account); Path file = RUNTIME.resolve("spool").resolve(id + ".sealed");
        Files.move(file, file.resolveSibling(id + ".retained"));
        assertThat(worker.authorize(id)).isEmpty(); assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.FAILED);
        UUID other = id(register()); code.set(503); worker.process(other);
        Path second = RUNTIME.resolve("spool").resolve(other + ".sealed"); Files.move(second, second.resolveSibling(other + ".retained")); advance(5);
        assertThat(worker.authorize(other)).isEmpty(); assertThat(intent(other).state()).isEqualTo(DurableTokenDelivery.State.UNKNOWN);
        assertThat(intent(other).uncertainAt()).isNotNull(); assertThat(requests).hasValue(1);
    }

    @Test void shouldBoundIdBasedQueriesAndKeepAcceptanceOnPermissionOutage() {
        UUID id = id(register()); worker.process(id); code.set(401);
        for (int n = 0; n < 6; n++) { advance(30); assertThat(worker.process(id)).isTrue(); assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.ACCEPTED); }
        advance(300); assertThat(worker.process(id)).isFalse(); assertThat(intent(id).observations()).isEqualTo(6); assertThat(requests).hasValue(7);
        assertThat(jdbc.queryForObject("select reason from identity_token_delivery where id=?", String.class, id)).isEqualTo("QUERY_PERMISSION");
    }

    @ParameterizedTest @ValueSource(longs = {10, 300, 900})
    void shouldHonorBoundedQueryRetryAfterWithoutLosingAcceptanceOrExceedingObservationCap(long advisedSeconds) {
        UUID id = id(register()); assertThat(worker.process(id)).isTrue();
        var accepted = intent(id);
        var acceptedAt = jdbc.queryForObject("select accepted_at from identity_token_delivery where id=?", java.sql.Timestamp.class, id);
        code.set(429); retryAfter.set(Long.toString(advisedSeconds));
        advance(30); assertThat(worker.process(id)).isTrue();
        Instant limitedAt = clock.instant(); long delay = Math.max(30, Math.min(300, advisedSeconds));
        if (delay > 30) {
            advance(30); assertThat(worker.process(id)).isFalse(); assertThat(requests).hasValue(2);
            advance(delay - 31);
        } else { advance(delay - 1); }
        assertThat(worker.process(id)).isFalse(); assertThat(requests).hasValue(2);
        assertThat(intent(id).availableAt()).isEqualTo(limitedAt.plusSeconds(delay));
        for (int observation = 2; observation <= 6; observation++) {
            advance(1); assertThat(worker.process(id)).isTrue();
            var current = intent(id);
            assertThat(current.state()).isEqualTo(DurableTokenDelivery.State.ACCEPTED);
            assertThat(current.providerId()).isEqualTo(provider); assertThat(current.attempts()).isEqualTo(1);
            assertThat(current.observations()).isEqualTo(observation);
            assertThat(current.dispatchedAt()).isEqualTo(accepted.dispatchedAt());
            assertThat(current.uncertainAt()).isEqualTo(accepted.uncertainAt());
            assertThat(jdbc.queryForObject("select accepted_at from identity_token_delivery where id=?", java.sql.Timestamp.class, id)).isEqualTo(acceptedAt);
            assertThat(jdbc.queryForObject("select delivered_at from identity_token_delivery where id=?", java.sql.Timestamp.class, id)).isNull();
            if (observation < 6) { advance(delay - 1); assertThat(worker.process(id)).isFalse(); }
        }
        advance(300); assertThat(worker.process(id)).isFalse(); assertThat(intent(id).availableAt()).isNull();
        assertThat(requests).hasValue(7); assertThat(keys).containsExactly("identity-token/" + id, "null", "null", "null", "null", "null", "null");
        assertThat(jdbc.queryForObject("select reason from identity_token_delivery where id=?", String.class, id)).isEqualTo("RATE_LIMIT");
    }

    @Test void shouldRejectAmbientWorkersAndPreparationOutsideIdentityTransaction() {
        tx(() -> { assertThatThrownBy(worker::processOne).isInstanceOf(IllegalStateException.class); assertThatThrownBy(() -> worker.authorize(UUID.randomUUID())).isInstanceOf(IllegalStateException.class); return null; });
        assertThatThrownBy(() -> durable.prepare(new ContactToken(), 0, "a@example.test", "unavailable")).isInstanceOf(IllegalStateException.class);
    }

    @Test void shouldKeepDefaultLocalDeliverySuccessAndFailureWithoutDurableIntent() throws Exception {
        var account = localAccounts.register("local" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345"); assertThat(account.delivered()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from identity_token_delivery where account_id=?", Integer.class, account.accountId().value())).isZero();
        var failing = new AccountService(identity, passwords, new TokenDeliveryCoordinator((id, purpose, raw) -> false, Optional.empty()), clock);
        UUID failed = tx(() -> failing.register("failure" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345").accountId().value());
        assertThat(jdbc.queryForObject("select consumed from identity_contact_token where account_id=?", Boolean.class, failed)).isTrue();
    }

    @Test void shouldComposeExplicitPreparationWithSchedulerOffAndCurrentRealClock() {
        String schema = "email_composed_" + UUID.randomUUID().toString().replace("-", "");
        server.removeContext("/");
        server.createContext("/emails", exchange -> {
            try {
                requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(ChangeoApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run(
                        "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                        "--spring.jpa.properties.hibernate.default_schema=" + schema, "--spring.datasource.hikari.schema=" + schema,
                        "--changeo.token-delivery=resend-preparation", "--changeo.token-delivery-spool=" + RUNTIME.resolve("spool"),
                        "--changeo.token-delivery-key=" + RUNTIME.resolve("spool.key"), "--changeo.token-delivery-credential=" + RUNTIME.resolve("fake.credential"),
                        "--changeo.token-delivery-sender=sender@example.test", "--changeo.token-delivery-endpoint=http://127.0.0.1:" + server.getAddress().getPort(),
                        "--changeo.mailbox=" + RUNTIME.resolve("composed-mailbox"), "--changeo.mfa-key=" + RUNTIME.resolve("composed-mfa.key"))) {
            assertThat(context.getBeansOfType(TokenDeliveryConfiguration.Schedule.class)).isEmpty();
            var accounts = context.getBean(AccountService.class);
            var registration = accounts.register("composition" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345");
            assertThat(registration.delivered()).isFalse(); assertThat(requests).hasValue(0);
            var database = context.getBean(JdbcTemplate.class);
            UUID id = database.queryForObject("select id from identity_token_delivery where account_id=?", UUID.class, registration.accountId().value());
            assertThat(database.queryForObject("select consumed from identity_contact_token where account_id=?", Boolean.class, registration.accountId().value())).isFalse();
            assertThat(context.getBean(TokenDeliveryWorker.class).processOne()).isTrue();
            assertThat(database.queryForObject("select state from identity_token_delivery where id=?", String.class, id)).isEqualTo("ACCEPTED");
            assertThat(requests).hasValue(1);
        }
    }

    @Test void shouldApplyV10ToPopulatedV9PreservingRowsKeysChecksumsAndDefaultBehavior() throws Exception {
        String schema = "email_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        DataSource source = jdbc.getDataSource(); var old = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("9").load();
        assertThat(old.migrate().migrationsExecuted).isEqualTo(9); old.validate();
        UUID account = UUID.randomUUID(); UUID token = UUID.randomUUID();
        try (var connection = source.getConnection()) {
            connection.setSchema(schema); var scoped = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            scoped.update("insert into identity_account(id,contact,password_hash,security_epoch,contact_generation) values(?,?,?,7,8)", account, "upgrade@example.test", "synthetic-hash");
            scoped.update("insert into identity_contact_token values(?,?,'CONTACT',?,8,?,false)", token, account, AccountService.hash("v9-private-token"), DurableTokenDelivery.time(clock.instant().plusSeconds(1800)));
            var before = scoped.queryForList("select to_jsonb(a)::text from identity_account a");
            var tokens = scoped.queryForList("select to_jsonb(t)::text from identity_contact_token t");
            var constraints = scoped.queryForList("select conname,pg_get_constraintdef(oid) from pg_constraint where connamespace=current_schema()::regnamespace order by conname,pg_get_constraintdef(oid)");
            var history = scoped.queryForList("select version,script,checksum,success from flyway_schema_history order by installed_rank");
            byte[] keyBefore = Files.readAllBytes(RUNTIME.resolve("spool.key"));
            var current = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("10").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1); current.validate(); assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(scoped.queryForList("select to_jsonb(a)::text from identity_account a")).isEqualTo(before);
            assertThat(scoped.queryForList("select to_jsonb(t)::text from identity_contact_token t")).isEqualTo(tokens);
            assertThat(scoped.queryForList("select conname,pg_get_constraintdef(oid) from pg_constraint where connamespace=current_schema()::regnamespace order by conname,pg_get_constraintdef(oid)")).containsAll(constraints);
            assertThat(scoped.queryForList("select version,script,checksum,success from flyway_schema_history where version is null or version::integer<=9 order by installed_rank")).isEqualTo(history);
            assertThat(Files.readAllBytes(RUNTIME.resolve("spool.key"))).isEqualTo(keyBefore);
            scoped.execute("set search_path to " + schema);
            assertThat(scoped.queryForObject("select count(*) from identity_token_delivery", Integer.class)).isZero();
        }
        Path mailbox = RUNTIME.resolve("upgraded-mailbox");
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(ChangeoApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run(
                        "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                        "--spring.jpa.properties.hibernate.default_schema=" + schema, "--spring.datasource.hikari.schema=" + schema,
                        "--changeo.mailbox=" + mailbox, "--changeo.mfa-key=" + RUNTIME.resolve("upgraded-mfa.key"))) {
            var service = context.getBean(AccountService.class);
            var local = service.register("postupgrade" + UUID.randomUUID() + "@example.test", "Synthetic-password-12345");
            assertThat(local.delivered()).isTrue();
            String raw;
            try (var files = Files.list(mailbox)) { raw = Files.readString(files.filter(p -> p.getFileName().toString().startsWith(local.accountId().value() + "-CONTACT-")).findFirst().orElseThrow()); }
            service.verifyContact(local.accountId().value(), raw);
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("select contact_verified from identity_account where id=?", Boolean.class, local.accountId().value())).isTrue();
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("select count(*) from identity_token_delivery", Integer.class)).isZero();
        }
    }

    @Test void shouldKeepAndProcessActualDatabaseSpoolKeyRestore() throws Exception {
        Path fixture = RUNTIME.resolve("restore-fixture-" + SCHEMA + ".private");
        if (!Boolean.getBoolean("email.delivery.restored")) {
            UUID account = register(); UUID id = id(account);
            Files.writeString(fixture, json.writeValueAsString(Map.of("schema", SCHEMA, "account", account.toString(), "delivery", id.toString(), "raw", raw(id))), StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(fixture, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.PENDING);
        } else {
            var saved = json.readTree(Files.readAllBytes(fixture)); UUID id = UUID.fromString(saved.path("delivery").asString()); UUID account = UUID.fromString(saved.path("account").asString());
            assertThat(raw(id)).isEqualTo(saved.path("raw").asString());
            var authorization = worker.authorize(id).orElseThrow(); worker.observe(authorization, client.send(id, authorization.body()));
            assertThat(intent(id).state()).isEqualTo(DurableTokenDelivery.State.ACCEPTED);
            localAccounts.verifyContact(account, saved.path("raw").asString());
            assertThatThrownBy(() -> localAccounts.verifyContact(account, saved.path("raw").asString())).isInstanceOf(IdentityFailure.class);
            assertThat(requests).hasValue(1);
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Latch timed out"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
