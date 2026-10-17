package ar.changeo.identity;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.api.*;
import ar.changeo.security.SessionProof;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ChangeoApplication.class, properties = {
        "spring.flyway.schemas=participants_test", "spring.flyway.default-schema=participants_test",
        "spring.jpa.properties.hibernate.default_schema=participants_test", "spring.datasource.hikari.schema=participants_test",
        "changeo.mfa-key=.runtime/participants-test-mfa.key", "changeo.moderation-worker=false"})
class ParticipantIdentityPostgresTest {
    @Autowired ParticipantIdentityAccess identity;
    @Autowired IdentityStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @MockitoBean SessionProof proof;

    UUID adult() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into identity_account(id,contact,password_hash,contact_verified) values(?,?,?,true)",
                id, "participant" + id + "@example.test", "synthetic-fixture-hash");
        jdbc.update("insert into identity_verification_evidence(id,account_id,kind,age,expires_at,scope,provenance,created_at) values(?,?,'AGE',30,?,'SANDBOX_SYNTHETIC',?,?)",
                UUID.randomUUID(), id, Timestamp.from(clock.instant().plusSeconds(3600)),
                "LOCAL_SYNTHETIC_OPERATOR:" + UUID.randomUUID(), Timestamp.from(clock.instant()));
        return id;
    }

    @Test void shouldDiscoverImmutableControllersWithoutGrantingParticipation() {
        UUID person = adult();
        assertThat(identity.controllers(Set.of(new SubjectId(person))))
                .containsExactly(Map.entry(new SubjectId(person), new ActorId(person)));
        assertThatThrownBy(() -> identity.controllers(Set.of(new SubjectId(UUID.randomUUID()))))
                .isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> identity.requireForUpdate(PermissionRequest.self(new AccountId(person), Capability.NEW_REQUEST), Set.of()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void shouldPoisonCaughtDenialAndRejectMissingParticipants() {
        UUID person = adult();
        jdbc.execute("create table if not exists participant_intent(id uuid primary key)");
        UUID intent = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            try {
                identity.requireForUpdate(PermissionRequest.self(new AccountId(person), Capability.NEW_REQUEST),
                        Set.of(new AccountId(UUID.randomUUID())));
            } catch (IdentityFailure denied) {
                jdbc.update("insert into participant_intent values(?)", intent);
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from participant_intent where id=?", Long.class, intent)).isZero();
    }

    @Test void shouldPropagateActualDatabaseFailureInsteadOfReturningPartialControllers() throws Exception {
        UUID person = adult(); UUID intent = UUID.randomUUID();
        jdbc.execute("create table if not exists participant_intent(id uuid primary key)");
        var failed = new java.util.concurrent.atomic.AtomicBoolean();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("lock table identity_account in access exclusive mode"); locked.countDown(); await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.update("insert into participant_intent values(?)", intent);
                    jdbc.execute("set local statement_timeout='200ms'");
                    try { identity.controllers(Set.of(new SubjectId(person))); }
                    catch (org.springframework.dao.DataAccessException databaseFailure) { failed.set(true); }
                })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            } finally { release.countDown(); }
            blocker.get(5, TimeUnit.SECONDS);
        }
        assertThat(failed).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from participant_intent where id=?", Integer.class, intent)).isZero();
        assertThat(identity.controllers(Set.of(new SubjectId(person)))).containsExactly(Map.entry(new SubjectId(person), new ActorId(person)));
        assertThatThrownBy(() -> identity.controllers(null)).isInstanceOf(NullPointerException.class);
        Set<SubjectId> tooMany = new HashSet<>();
        for (int index = 0; index < 65; index++) { tooMany.add(new SubjectId(UUID.randomUUID())); }
        assertThatThrownBy(() -> identity.controllers(tooMany)).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldProtectCompleteOtherPartyUntilCallerCommits() throws Exception {
        UUID first = adult();
        UUID second = adult();
        CountDownLatch protectedSet = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> owner = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                identity.requireForUpdate(PermissionRequest.self(new AccountId(first), Capability.NEW_REQUEST),
                        Set.of(new AccountId(first), new AccountId(second)));
                identity.requireForUpdate(PermissionRequest.self(new AccountId(second), Capability.NEW_REQUEST), Set.of(new AccountId(second)));
                protectedSet.countDown();
                await(release);
            }));
            try {
                assertThat(protectedSet.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> mutation = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    attempted.countDown();
                    store.lockAccounts(List.of(second));
                    jdbc.update("insert into identity_restriction values(?,?,true,'SYNTHETIC_REVIEW',?)", UUID.randomUUID(), second, Timestamp.from(clock.instant()));
                }));
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
                mutation.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                        identity.requireForUpdate(PermissionRequest.self(new AccountId(second), Capability.NEW_REQUEST), Set.of(new AccountId(first)))))
                        .isInstanceOf(IdentityFailure.class);
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
