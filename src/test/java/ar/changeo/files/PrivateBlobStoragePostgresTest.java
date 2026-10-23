package ar.changeo.files;

import ar.changeo.ChangeoApplication;
import ar.changeo.files.api.*;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.SessionProof;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class PrivateBlobStoragePostgresTest extends PrivateBlobStorageFixture {
    @Test
    @EnabledIfSystemProperty(named = "r2.upgrade", matches = "true")
    void shouldPreservePopulatedV10AndLocalBytesWhenAddingImmutableV11() throws Exception {
        String localContact = "r2local" + UUID.randomUUID() + "@example.test";
        UUID author = register(localContact, 30);
        actor.set(author);
        var reference = save("synthetic:clear", "GENERAL", "PUBLIC", Optional.empty(), Optional.empty(), "text/plain");
        var file = file(reference);
        assertThat(new LocalPrivateBlobStore(Path.of(".runtime/r2-local")).read(new PrivateBlobStore.Local(file.id()), "text/plain", CLEAR.length)).isEqualTo(CLEAR);
        var tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema='" + upgradeSchema + "' and table_name<>'flyway_schema_history' order by table_name", String.class);
        Map<String, String> before = new LinkedHashMap<>();
        for (String table : tables) { before.put(table, rows(table)); }
        var columns = jdbc.queryForList("select table_name,column_name,ordinal_position,data_type,is_nullable,column_default from information_schema.columns where table_schema='" + upgradeSchema + "' order by table_name,ordinal_position");
        var history = jdbc.queryForList("select * from flyway_schema_history order by installed_rank");
        assertThat(history.stream().filter(row -> row.get("version") != null).count()).isEqualTo(10);
        var upgrade = Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgradeSchema).defaultSchema(upgradeSchema).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        for (String table : tables) { assertThat(rows(table)).as(table).isEqualTo(before.get(table)); }
        assertThat(jdbc.queryForList("select table_name,column_name,ordinal_position,data_type,is_nullable,column_default from information_schema.columns where table_schema='" + upgradeSchema + "' and table_name<>'files_blob_binding' order by table_name,ordinal_position")).isEqualTo(columns);
        assertThat(jdbc.queryForList("select * from flyway_schema_history where installed_rank<=? order by installed_rank", history.getLast().get("installed_rank"))).isEqualTo(history);
        assertThat(jdbc.queryForObject("select count(*) from files_blob_binding", Long.class)).isZero();
        approve(reference);
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
        assertThat(protocol().count("PUT")).isZero();
        var restored = new Properties();
        restored.setProperty("contact", localContact);
        restored.setProperty("password", PASSWORD);
        restored.setProperty("actor", author.toString());
        restored.setProperty("file", file.id().toString());
        restored.setProperty("resource", reference.resourceId().toString());
        restored.setProperty("revision", reference.revisionId().toString());
        restored.setProperty("digest", file.digest());
        restored.setProperty("schema", upgradeSchema);
        restored.setProperty("clock", clock.instant().toString());
        try (var output = Files.newOutputStream(Path.of(".runtime/local-restore-fixture.properties"), StandardOpenOption.CREATE_NEW)) { restored.store(output, null); }
        String freshSchema = "r2_fresh_" + UUID.randomUUID().toString().replace("-", "");
        var fresh = Flyway.configure().dataSource(jdbc.getDataSource()).schemas(freshSchema).defaultSchema(freshSchema).load();
        assertThat(fresh.migrate().migrationsExecuted).isEqualTo(11);
        fresh.validate();
        assertThat(fresh.migrate().migrationsExecuted).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named = "r2.upgraded.remote", matches = "true")
    void shouldCreateCanonicalRemoteBindingBesideOldLocalArtifactInUpgradedSchema() throws Exception {
        var values = localRestored();
        actor.set(UUID.fromString(values.getProperty("actor")));
        var oldContent = new ContentReference(SurfaceKind.REQUEST_BODY, UUID.fromString(values.getProperty("resource")), UUID.fromString(values.getProperty("revision")));
        var oldFile = new FileReference(UUID.fromString(values.getProperty("file")), oldContent, values.getProperty("digest"));
        long before = protocol().count("PUT");
        var reference = save("synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.empty(), "text/plain");
        var remoteFile = file(reference);
        approve(reference);
        assertThat(jdbc.queryForObject("select count(*) from files_blob_binding where file_id=?", Long.class, oldFile.id())).isZero();
        assertThat(jdbc.queryForObject("select object_key from files_blob_binding where file_id=?", String.class, remoteFile.id())).isEqualTo("private-blobs/" + remoteFile.id());
        assertThat(protocol().count("PUT")).isEqualTo(before + 1);
        assertThat(files.read(oldFile, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
        assertThat(files.read(remoteFile, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
        assertThatThrownBy(() -> tx(() -> jdbc.update("update files_blob_binding set etag='changed' where file_id=?", remoteFile.id()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private String rows(String table) {
        if (!table.matches("[a-z0-9_]+")) { throw new IllegalArgumentException(); }
        return jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from " + table + " t", String.class);
    }

    @Test void shouldCommitImmutableRemoteBindingAndReplayBeforeAnotherPut() {
        UUID author = verified(30);
        actor.set(author);
        UUID command = UUID.randomUUID();
        var input = draft(command, "synthetic:clear", "GENERAL", "PUBLIC", Optional.empty(), Optional.empty(), "text/plain");
        var reference = workbench.save(input);
        var file = file(reference);
        long puts = protocol().count("PUT");
        assertThat(workbench.save(input)).isEqualTo(reference);
        var context = new AuthorContext(new ActorId(author), new SubjectId(author), Capability.NEW_REQUEST);
        UUID fileCommand = UUID.nameUUIDFromBytes((command + ":0").getBytes(StandardCharsets.UTF_8));
        assertThat(tx(() -> files.intake(fileCommand, context, reference, "text/plain", CLEAR))).isEqualTo(file);
        assertThat(protocol().count("PUT")).isEqualTo(puts);
        assertThatThrownBy(() -> tx(() -> files.intake(fileCommand, context, reference, "text/plain", new byte[]{1}))).isInstanceOf(ModerationFailure.class);
        approve(reference);
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
        assertThat(jdbc.queryForObject("select object_key from files_blob_binding where file_id=?", String.class, file.id())).isEqualTo("private-blobs/" + file.id());
        assertThatThrownBy(() -> tx(() -> jdbc.update("update files_blob_binding set etag='changed' where file_id=?", file.id()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> tx(() -> jdbc.update("delete from files_blob_binding where file_id=?", file.id()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(Files.exists(journal().resolve(file.id() + ".completion"))).isTrue();
    }

    @Test void shouldPoisonCaughtFailedPutAndRetainPossibleEffectWithoutMetadata() throws Exception {
        UUID author = verified(30);
        actor.set(author);
        var context = new AuthorContext(new ActorId(author), new SubjectId(author), Capability.NEW_REQUEST);
        var reference = new ContentReference(SurfaceKind.REQUEST_BODY, UUID.randomUUID(), UUID.randomUUID());
        UUID command = UUID.randomUUID();
        protocol().mode = R2PrivateBlobStoreTest.Mode.MISSING_ETAG;
        assertThatThrownBy(() -> tx(() -> {
            jdbc.update("insert into r2_test_intent values(?)", command);
            try { files.intake(command, context, reference, "text/plain", CLEAR); } catch (ModerationFailure expected) { }
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from r2_test_intent where id=?", Long.class, command)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from files_artifact where command_id=?", Long.class, command)).isZero();
        var put = protocol().requests.stream().filter(request -> request.method().equals("PUT")).reduce((first, second) -> second).orElseThrow();
        UUID id = UUID.fromString(put.path().substring(put.path().lastIndexOf('/') + 1));
        assertThat(protocol().objects).containsKey(put.path());
        assertThat(Files.readString(journal().resolve(id + ".outcome"))).startsWith("QUARANTINED\n");
    }

    @Test void shouldRetainSuccessfulRemotePutAfterBindingFailureAndOuterRollback() throws Exception {
        UUID author = verified(30);
        actor.set(author);
        var context = new AuthorContext(new ActorId(author), new SubjectId(author), Capability.NEW_REQUEST);
        var reference = new ContentReference(SurfaceKind.REQUEST_BODY, UUID.randomUUID(), UUID.randomUUID());
        UUID command = UUID.randomUUID();
        doAnswer(invocation -> {
            if (invocation.getArgument(0, String.class).startsWith("insert into files_blob_binding")) { throw new IllegalStateException("synthetic binding failure"); }
            return invocation.callRealMethod();
        }).when(jdbc).update(anyString(), any(Object[].class));
        try {
            assertThatThrownBy(() -> tx(() -> {
                try { files.intake(command, context, reference, "text/plain", CLEAR); } catch (IllegalStateException expected) { }
                return null;
            })).isInstanceOf(UnexpectedRollbackException.class);
        } finally { reset(jdbc); }
        assertThat(jdbc.queryForObject("select count(*) from files_artifact where command_id=?", Long.class, command)).isZero();
        var retained = new TransactionTemplate(transactions).execute(status -> {
            var file = files.intake(UUID.randomUUID(), context, reference, "text/plain", CLEAR);
            status.setRollbackOnly();
            return file;
        });
        assertThat(jdbc.queryForObject("select count(*) from files_artifact where id=?", Long.class, retained.id())).isZero();
        assertThat(Files.readString(journal().resolve(retained.id() + ".completion"))).startsWith("RETAINED\n");
        assertThat(protocol().objects).containsKey("/changeo-r2-preparation/private-blobs/" + retained.id());
        assertThat(protocol().count("DELETE")).isZero();
    }

    @Test void shouldDenyPendingQuarantinedCorruptUnknownAndAmbientBytes() {
        UUID author = verified(30);
        actor.set(author);
        var pending = save("synthetic:clear", "GENERAL", "PUBLIC", Optional.empty(), Optional.empty(), "text/plain");
        var file = file(pending);
        assertThatThrownBy(() -> files.read(file, FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        approve(pending);
        assertThatThrownBy(() -> tx(() -> files.read(file, FileAccess.Purpose.ORDINARY))).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> files.read(new FileReference(file.id(), file.content(), "0".repeat(64)), FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        protocol().mode = R2PrivateBlobStoreTest.Mode.CHECKSUM;
        long before = protocol().count("GET");
        assertThatThrownBy(() -> files.read(file, FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThat(protocol().count("GET")).isEqualTo(before + 1);
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        var unsafe = save("synthetic:clear", "GENERAL", "PUBLIC", Optional.empty(), Optional.empty(), "application/pdf");
        actor.set(operator);
        assertThatThrownBy(() -> files.read(file(unsafe), FileAccess.Purpose.REVIEW)).isInstanceOf(ModerationFailure.class);
        actor.set(operator);
        UUID caseId = cases.reviewCase(UUID.randomUUID(), pending);
        actor.set(operator);
        cases.act(UUID.randomUUID(), caseId, 0, "RECALL", "GENERAL", "SAFETY");
        actor.set(author);
        assertThatThrownBy(() -> files.read(file, FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
    }

    @ParameterizedTest
    @EnumSource(Withdrawal.class)
    void shouldHoldCurrentAuthorizationThroughDelayedRemoteBytes(Withdrawal withdrawal) throws Exception {
        UUID author = verified(30), viewer = verified(30);
        actor.set(author);
        accounts.preferences(viewer, true, true);
        String text = withdrawal == Withdrawal.PREFERENCE ? "synthetic:medical" : "synthetic:clear";
        var reference = save(text, withdrawal == Withdrawal.PREFERENCE ? "SENSITIVE" : "GENERAL", "PRIVATE", Optional.empty(), Optional.of(viewer), "text/plain");
        approve(reference);
        var file = file(reference);
        var gate = new R2PrivateBlobStoreTest.FakeS3.Gate(new CountDownLatch(1), new CountDownLatch(1));
        protocol().gate = gate;
        protocol().mode = R2PrivateBlobStoreTest.Mode.HELD;
        var mutationStarted = new CountDownLatch(1);
        Runnable mutation = switch (withdrawal) {
            case RESTRICTION -> () -> admin.restriction(operator, viewer, true);
            case PREFERENCE -> () -> accounts.preferences(viewer, false, false);
            case MEMBERSHIP -> () -> workbench.removeParticipant(reference.resourceId(), viewer);
            case RECALL -> () -> {
                actor.set(operator);
                UUID review = cases.reviewCase(UUID.randomUUID(), reference);
                cases.act(UUID.randomUUID(), review, 0, "RECALL", "GENERAL", "SAFETY");
            };
        };
        UUID mutator = withdrawal == Withdrawal.MEMBERSHIP ? author : withdrawal == Withdrawal.RESTRICTION || withdrawal == Withdrawal.RECALL ? operator : viewer;
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var read = pool.submit(() -> as(viewer, () -> files.read(file, FileAccess.Purpose.ORDINARY)));
                assertThat(gate.entered().await(2, TimeUnit.SECONDS)).isTrue();
                var changed = pool.submit(() -> as(mutator, () -> { mutationStarted.countDown(); mutation.run(); return null; }));
                assertThat(mutationStarted.await(1, TimeUnit.SECONDS)).isTrue();
                blocked();
                assertThat(changed).isNotDone();
                gate.release().countDown();
                assertThat(read.get(3, TimeUnit.SECONDS).bytes()).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
                changed.get(3, TimeUnit.SECONDS);
            } finally { gate.release().countDown(); protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL; }
        }
        assertThatThrownBy(() -> as(viewer, () -> files.read(file, FileAccess.Purpose.ORDINARY)))
                .isInstanceOf(withdrawal == Withdrawal.RESTRICTION || withdrawal == Withdrawal.PREFERENCE ? IdentityFailure.class : ModerationFailure.class);
    }

    @ParameterizedTest
    @EnumSource(Withdrawal.class)
    void shouldReturnNoRemoteBytesAfterEarlierProtectedWithdrawal(Withdrawal withdrawal) throws Exception {
        UUID author = verified(30), viewer = verified(30);
        actor.set(author);
        accounts.preferences(viewer, true, true);
        var reference = save(withdrawal == Withdrawal.PREFERENCE ? "synthetic:medical" : "synthetic:clear", withdrawal == Withdrawal.PREFERENCE ? "SENSITIVE" : "GENERAL", "PRIVATE", Optional.empty(), Optional.of(viewer), "text/plain");
        approve(reference);
        var file = file(reference);
        UUID mutator = withdrawal == Withdrawal.MEMBERSHIP ? author : withdrawal == Withdrawal.RESTRICTION || withdrawal == Withdrawal.RECALL ? operator : viewer;
        var applied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        long before = protocol().count("GET");
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var mutation = pool.submit(() -> as(mutator, () -> tx(() -> {
                    switch (withdrawal) {
                        case RESTRICTION -> admin.restriction(operator, viewer, true);
                        case PREFERENCE -> accounts.preferences(viewer, false, false);
                        case MEMBERSHIP -> workbench.removeParticipant(reference.resourceId(), viewer);
                        case RECALL -> {
                            UUID review = cases.reviewCase(UUID.randomUUID(), reference);
                            cases.act(UUID.randomUUID(), review, 0, "RECALL", "GENERAL", "SAFETY");
                        }
                    }
                    application.getBean(jakarta.persistence.EntityManager.class).flush();
                    applied.countDown();
                    try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    return null;
                })));
                assertThat(applied.await(2, TimeUnit.SECONDS)).isTrue();
                var reading = pool.submit(() -> as(viewer, () -> files.read(file, FileAccess.Purpose.ORDINARY)));
                blocked();
                assertThat(protocol().count("GET")).isEqualTo(before);
                release.countDown();
                mutation.get(3, TimeUnit.SECONDS);
                assertThatThrownBy(() -> reading.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(withdrawal == Withdrawal.RESTRICTION || withdrawal == Withdrawal.PREFERENCE ? IdentityFailure.class : ModerationFailure.class);
                assertThat(protocol().count("GET")).isEqualTo(before);
            } finally { release.countDown(); }
        }
    }

    private void blocked() {
        await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(1)).until(() -> jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and cardinality(pg_blocking_pids(pid))>0", Long.class) > 0);
    }

    @Test void shouldSerializeConcurrentDuplicateIntakeBeforeOneRemotePut() throws Exception {
        UUID author = verified(30);
        var input = draft(UUID.randomUUID(), "synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.empty(), "text/plain");
        var gate = new R2PrivateBlobStoreTest.FakeS3.Gate(new CountDownLatch(1), new CountDownLatch(1));
        protocol().gate = gate;
        protocol().mode = R2PrivateBlobStoreTest.Mode.HELD;
        long before = protocol().count("PUT");
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> as(author, () -> workbench.save(input)));
                assertThat(gate.entered().await(2, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> as(author, () -> workbench.save(input)));
                blocked();
                gate.release().countDown();
                assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(second.get(3, TimeUnit.SECONDS));
                assertThat(protocol().count("PUT")).isEqualTo(before + 1);
            } finally { gate.release().countDown(); protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL; }
        }
    }

    @Test void shouldDenyNewGuardianIntakeAfterRevocationAndKeepFocusedSafeEvidence() {
        UUID guardian = verified(30), minor = verified(15);
        var guardians = application.getBean(GuardianService.class);
        admin.evidence(operator, minor, "MINOR_DRAFT", null);
        UUID link = guardians.request(minor, guardian, minor);
        admin.reviewGuardian(operator, link, 0, true);
        actor.set(guardian);
        var input = draft(UUID.randomUUID(), "synthetic:clear", "GENERAL", "PRIVATE", Optional.of(minor), Optional.empty(), "text/plain");
        var reference = workbench.save(input);
        workbench.submit(reference);
        var file = file(reference);
        long puts = protocol().count("PUT");
        long version = guardians.own(guardian).stream().filter(value -> value.id().equals(link)).findFirst().orElseThrow().version();
        guardians.revoke(guardian, link, version);
        assertThatThrownBy(() -> workbench.save(input)).isInstanceOf(IdentityFailure.class);
        assertThat(protocol().count("PUT")).isEqualTo(puts);
        actor.set(operator);
        assertThat(files.read(file, FileAccess.Purpose.REVIEW).bytes()).isEqualTo(CLEAR);
        when(proof.mfa(any(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> files.read(file, FileAccess.Purpose.REVIEW)).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldFailClosedWithoutConfiguredRemoteEvenWhenLocalBytesExist() throws Exception {
        UUID author = verified(30);
        actor.set(author);
        var reference = save("synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.empty(), "text/plain");
        approve(reference);
        var file = file(reference);
        var local = application.getBean(LocalPrivateBlobStore.class);
        local.create(file.id(), "text/plain", CLEAR);
        var unavailable = new PrivateFileService(jdbc, transactions, application.getBeanProvider(AttachmentAccessPolicy.class),
                application.getBean(IdentityAccess.class), proof, application.getBean(Admission.class), clock,
                application.getBean(FileInspector.class), local, new StaticListableBeanFactory().getBeanProvider(R2PrivateBlobStore.class));
        long before = protocol().count("GET");
        assertThatThrownBy(() -> unavailable.read(file, FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThat(protocol().count("GET")).isEqualTo(before);
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
    }

    @Test void shouldKeepCommittedBindingAuthoritativeAfterUnknownCommitAcknowledgment() throws Exception {
        UUID author = verified(30);
        actor.set(author);
        var input = draft(UUID.randomUUID(), "synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.empty(), "text/plain");
        var captured = new AtomicReference<ContentReference>();
        var manager = new JpaTransactionManager(application.getBean(jakarta.persistence.EntityManagerFactory.class)) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new TransactionSystemException("Synthetic lost commit acknowledgement");
            }
        };
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            captured.set(workbench.save(input));
            return null;
        })).isInstanceOf(TransactionSystemException.class);
        var reference = captured.get();
        var file = file(reference);
        assertThat(Files.readString(journal().resolve(file.id() + ".completion"))).startsWith("RETAINED\n");
        long before = protocol().count("PUT");
        assertThat(workbench.save(input)).isEqualTo(reference);
        assertThat(protocol().count("PUT")).isEqualTo(before);
        approve(reference);
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
    }

    @Test void shouldCaptureCompleteRestorableCredentialByteAndOrphanFixture() throws Exception {
        String contact = "r2restore" + UUID.randomUUID() + "@example.test";
        UUID author = register(contact, 30);
        actor.set(author);
        var reference = save("synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.empty(), "text/plain");
        approve(reference);
        var file = file(reference);
        var orphan = protocol().store(journal().resolve("standalone"));
        UUID orphanId = UUID.randomUUID();
        try (orphan) { orphan.create(orphanId, "text/plain", CLEAR); }
        var properties = new Properties();
        properties.setProperty("contact", contact);
        properties.setProperty("password", PASSWORD);
        properties.setProperty("actor", author.toString());
        properties.setProperty("file", file.id().toString());
        properties.setProperty("resource", reference.resourceId().toString());
        properties.setProperty("revision", reference.revisionId().toString());
        properties.setProperty("digest", file.digest());
        properties.setProperty("orphan", orphanId.toString());
        properties.setProperty("port", Integer.toString(protocol().server.getAddress().getPort()));
        properties.setProperty("fake-root", Path.of(".runtime").toAbsolutePath().relativize(protocol().root.toAbsolutePath()).toString());
        properties.setProperty("clock", clock.instant().toString());
        try (var output = Files.newOutputStream(Path.of(".runtime/restore-fixture.properties"), StandardOpenOption.CREATE_NEW)) { properties.store(output, null); }
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
    }

    enum Withdrawal { RESTRICTION, PREFERENCE, MEMBERSHIP, RECALL }
}

@SpringBootTest(classes = {ChangeoApplication.class, PrivateBlobStorageFixture.Configuration.class}, properties = {
        "spring.flyway.schemas=r2_storage_test", "spring.flyway.default-schema=r2_storage_test", "spring.jpa.properties.hibernate.default_schema=r2_storage_test", "spring.datasource.hikari.schema=r2_storage_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false", "changeo.files.storage=r2-preparation",
        "changeo.files.r2.bucket=changeo-r2-preparation", "changeo.files.r2.access-key=r2-preparation-key", "changeo.files.r2.secret-key=r2-preparation-secret",
        "changeo.mfa-key=.runtime/r2-mfa.key", "changeo.mailbox=.runtime/r2-mailbox", "changeo.files-root=.runtime/r2-local"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PrivateBlobStorageFixture {
    static final byte[] CLEAR = "synthetic:clear".getBytes(StandardCharsets.UTF_8);
    static final String upgradeSchema = "r2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD = "Synthetic-password-12345";
    private static R2PrivateBlobStoreTest.FakeS3 protocol;
    @Autowired FileAccess files;
    @Autowired WorkbenchService workbench;
    @Autowired ContentReader reader;
    @Autowired ModerationWorker worker;
    @Autowired ModerationService moderation;
    @Autowired CaseService cases;
    @Autowired AccountService accounts;
    @Autowired AdminService admin;
    @Autowired OperatorService operators;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired TestClock clock;
    @Autowired ApplicationContext application;
    @MockitoSpyBean JdbcTemplate jdbc;
    @MockitoBean SessionProof proof;
    @MockitoBean TokenDelivery delivery;
    final ThreadLocal<UUID> actor = new ThreadLocal<>();
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    UUID operator;

    @TestConfiguration static class Configuration {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-20T04:00:00Z"));
        void advance() { instant.updateAndGet(value -> value.plusSeconds(61)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("changeo.files.r2.endpoint", () -> protocol().endpoint().toString());
        registry.add("changeo.files.r2.journal-root", () -> journal().toString());
        if (Boolean.getBoolean("r2.local.restore")) {
            String schema;
            try { schema = localRestored().getProperty("schema"); } catch (IOException failure) { throw new UncheckedIOException(failure); }
            for (String key : List.of("spring.flyway.schemas", "spring.flyway.default-schema", "spring.jpa.properties.hibernate.default_schema", "spring.datasource.hikari.schema")) { registry.add(key, () -> schema); }
            registry.add("changeo.files.storage", () -> Boolean.getBoolean("r2.upgraded.remote") ? "r2-preparation" : "local");
        }
        if (Boolean.getBoolean("r2.upgrade")) {
            for (String key : List.of("spring.flyway.schemas", "spring.flyway.default-schema", "spring.jpa.properties.hibernate.default_schema", "spring.datasource.hikari.schema")) { registry.add(key, () -> upgradeSchema); }
            registry.add("spring.flyway.target", () -> "10");
            registry.add("changeo.files.storage", () -> "local");
        }
    }

    static synchronized R2PrivateBlobStoreTest.FakeS3 protocol() {
        if (protocol == null) {
            try {
                Path settings = Path.of(".runtime/r2-protocol.properties");
                if (Files.exists(settings)) {
                    var values = new Properties();
                    try (var input = Files.newInputStream(settings)) { values.load(input); }
                    protocol = new R2PrivateBlobStoreTest.FakeS3(Path.of(".runtime").resolve(values.getProperty("root")), Integer.parseInt(values.getProperty("port")));
                    protocol.load();
                } else {
                    protocol = new R2PrivateBlobStoreTest.FakeS3(Path.of(".runtime/r2-protocol"));
                    var values = new Properties();
                    values.setProperty("root", "r2-protocol");
                    values.setProperty("port", Integer.toString(protocol.server.getAddress().getPort()));
                    try (var output = Files.newOutputStream(settings, StandardOpenOption.CREATE_NEW)) { values.store(output, null); }
                    Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-------"));
                }
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
        return protocol;
    }

    static Path journal() { return Path.of(".runtime/r2-journal").toAbsolutePath(); }
    static Properties localRestored() throws IOException { return settings("local-restore-fixture.properties"); }
    static Properties restored() throws IOException { return settings("restore-fixture.properties"); }
    static Properties settings(String name) throws IOException {
        var result = new Properties();
        try (var input = Files.newInputStream(Path.of(".runtime").resolve(name))) { result.load(input); }
        return result;
    }

    @BeforeEach void initialize() throws Exception {
        clock.advance();
        tokens.clear();
        reset(proof, delivery, jdbc);
        when(proof.actor()).thenAnswer(invocation -> Optional.ofNullable(actor.get()));
        when(proof.mfa(any(), anyLong())).thenReturn(true);
        when(proof.freshPassword(any(), anyLong())).thenReturn(true);
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> { tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true; });
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        if (Boolean.getBoolean("r2.restore")) { clock.instant.set(Instant.parse((Boolean.getBoolean("r2.local.restore") ? localRestored() : restored()).getProperty("clock"))); return; }
        Path enrollment = Path.of(".runtime/r2-enrollment-" + UUID.randomUUID());
        String contact = "r2operator" + UUID.randomUUID() + "@example.test";
        operators.provision(contact, Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER"), enrollment);
        operator = accounts.credentials(contact).orElseThrow().id();
        jdbc.execute("create table if not exists r2_test_intent(id uuid primary key)");
    }

    @AfterEach void clearActor() { actor.remove(); }
    @AfterAll static void closeProtocol() throws Exception {
        if (protocol != null) { protocol.close(); protocol = null; }
    }

    UUID verified(int age) { return register("r2actor" + UUID.randomUUID() + "@example.test", age); }
    UUID register(String contact, int age) {
        UUID id = accounts.register(contact, PASSWORD).accountId().value();
        accounts.verifyContact(id, tokens.get(id + ":CONTACT"));
        admin.evidence(operator, id, "AGE", age);
        return id;
    }
    WorkbenchService.Draft draft(UUID command, String text, String label, String audience, Optional<UUID> subject, Optional<UUID> participant, String type) {
        return new WorkbenchService.Draft(command, Optional.empty(), 0, text, label, audience, subject, participant, List.of(new WorkbenchService.Upload(type, text.getBytes(StandardCharsets.UTF_8))));
    }
    ContentReference save(String text, String label, String audience, Optional<UUID> subject, Optional<UUID> participant, String type) {
        return workbench.save(draft(UUID.randomUUID(), text, label, audience, subject, participant, type));
    }
    FileReference file(ContentReference reference) {
        return jdbc.queryForObject("select id,digest from files_artifact where resource_id=? and revision_id=?", (row, index) -> new FileReference(row.getObject(1, UUID.class), reference, row.getString(2)), reference.resourceId(), reference.revisionId());
    }
    void approve(ContentReference reference) {
        workbench.submit(reference);
        for (int index = 0; index < 100; index++) {
            UUID approved = jdbc.queryForObject("select approved_revision from workbench_resource where id=?", UUID.class, reference.resourceId());
            if (reference.revisionId().equals(approved)) { return; }
            if (!worker.processOne()) { break; }
        }
        assertThat(jdbc.queryForObject("select approved_revision from workbench_resource where id=?", UUID.class, reference.resourceId())).isEqualTo(reference.revisionId());
    }
    <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    <T> T as(UUID account, Supplier<T> action) { actor.set(account); try { return action.get(); } finally { actor.remove(); } }

}
