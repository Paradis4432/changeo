package ar.changeo.files;

import ar.changeo.ChangeoApplication;
import ar.changeo.files.api.*;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = {ChangeoApplication.class, PrivateBlobStorageFixture.Configuration.class}, properties = {
        "spring.flyway.schemas=r2_current_review", "spring.flyway.default-schema=r2_current_review", "spring.jpa.properties.hibernate.default_schema=r2_current_review", "spring.datasource.hikari.schema=r2_current_review",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=false", "changeo.moderation-worker=false", "changeo.moderation-preparation=openai-wire", "changeo.files.storage=r2-preparation",
        "changeo.files.r2.bucket=changeo-r2-preparation", "changeo.files.r2.access-key=r2-preparation-key", "changeo.files.r2.secret-key=r2-preparation-secret",
        "changeo.mfa-key=.runtime/r2-mfa.key", "changeo.mailbox=.runtime/r2-mailbox", "changeo.files-root=.runtime/r2-local"})
class PrivateBlobCurrentIntegrationPostgresTest extends PrivateBlobStorageFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_FILE_BYTES = 1048576;
    private static final int MAX_MAIN_BYTES = 32768;
    private static final List<Capture> CAPTURES = new CopyOnWriteArrayList<>();
    private static final AtomicReference<WireGate> WIRE_GATE = new AtomicReference<>();
    private static HttpServer moderationServer;
    private static ExecutorService moderationThreads;
    private static Path fixture;
    private final List<ContentReference> own = new ArrayList<>();
    @Autowired ReviewFileAccess reviewFiles;
    @Autowired ModerationPreparationWorker preparation;
    @Autowired GuardianService guardians;
    @Autowired javax.sql.DataSource dataSource;
    private static final AtomicInteger PROTOCOL_DISCONNECTS = new AtomicInteger();

    @DynamicPropertySource static void preparationProperties(DynamicPropertyRegistry properties) throws IOException {
        fixture = Path.of(".runtime/external-moderation-preparation-20261020/r2-current-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(fixture, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path credential = fixture.resolve("fake.credential");
        Files.writeString(credential, "LOCAL_PROTOCOL_FIXTURE_" + "r".repeat(32), StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(credential, PosixFilePermissions.fromString("rw-------"));
        moderationServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        moderationThreads = Executors.newVirtualThreadPerTaskExecutor();
        moderationServer.setExecutor(moderationThreads);
        moderationServer.createContext("/v1/", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readNBytes(10485761);
                if (body.length > 10485760) { throw new IOException("Fixture request exceeded admission"); }
                var capture = new Capture(exchange.getRequestURI().getPath(), body);
                Path file = fixture.resolve("wire-" + UUID.randomUUID() + ".json");
                Files.write(file, body, StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                CAPTURES.add(capture);
                WireGate gate = WIRE_GATE.get();
                if (gate != null && capture.path().equals("/v1/moderations")) {
                    gate.entered().countDown();
                    if (!gate.release().await(10, TimeUnit.SECONDS)) { throw new IOException("Wire gate not released"); }
                }
                byte[] response = response(capture.path(), body);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException disconnected) { PROTOCOL_DISCONNECTS.incrementAndGet(); }
        });
        moderationServer.start();
        properties.add("changeo.moderation-preparation-origin", () -> "http://127.0.0.1:" + moderationServer.getAddress().getPort());
        properties.add("changeo.moderation-preparation-credential", () -> Path.of("").toAbsolutePath().relativize(credential).toString());
    }

    private static byte[] response(String path, byte[] body) {
        if (path.equals("/v1/responses")) {
            return JSON.writeValueAsBytes(Map.of("id", "resp_fixture", "model", "gpt-6-luna", "status", "completed", "error", JSON.nullNode(), "incomplete_details", JSON.nullNode(),
                    "output", List.of(Map.of("type", "message", "role", "assistant", "status", "completed", "content", List.of(Map.of("type", "output_text", "text", "{\"proposed_action\":\"APPROVE\",\"proposed_label\":\"GENERAL\",\"reason\":\"CLEAR\",\"uncertain\":false}"))))));
        }
        var categories = Set.of("sexual", "sexual/minors", "harassment", "harassment/threatening", "hate", "hate/threatening", "illicit", "illicit/violent", "self-harm", "self-harm/intent", "self-harm/instructions", "violence", "violence/graphic");
        Map<String, Boolean> flags = new TreeMap<>();
        Map<String, Double> scores = new TreeMap<>();
        Map<String, List<String>> coverage = new TreeMap<>();
        boolean images = JSON.readTree(body).path("input").isArray();
        categories.forEach(name -> { flags.put(name, false); scores.put(name, 0.0); coverage.put(name, images ? List.of("text", "image") : List.of("text")); });
        return JSON.writeValueAsBytes(Map.of("id", "modr_fixture", "model", "omni-moderation-latest", "results", List.of(Map.of("flagged", false, "categories", flags, "category_scores", scores, "category_applied_input_types", coverage))));
    }

    @BeforeEach void resetCurrentEvidence() { CAPTURES.clear(); WIRE_GATE.set(null); }

    @AfterEach void finishOwnedFixtureTasks() {
        var gate = protocol().gate;
        if (gate != null) { gate.release().countDown(); }
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        WireGate wire = WIRE_GATE.getAndSet(null);
        if (wire != null) { wire.release().countDown(); }
        for (var reference : own) {
            jdbc.update("update moderation_task set state='COMPLETE',reason='RS2_FIXTURE_CLOSED',claim_token=null,lease_until=null where submission_id in (select s.id from moderation_submission s join moderation_snapshot p on p.id=s.id join moderation_reference r on r.id=p.reference_id where r.resource_id=? and p.revision_id=?) and state<>'COMPLETE'", reference.resourceId(), reference.revisionId());
        }
        own.clear();
    }

    @AfterAll static void closeModerationProtocol() throws Exception {
        if (moderationServer != null) { moderationServer.stop(0); }
        if (moderationThreads != null) { moderationThreads.shutdownNow(); assertThat(moderationThreads.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private ContentReference pending(UUID author, String audience, String text, List<WorkbenchService.Upload> uploads, UUID subject) {
        actor.set(author);
        var reference = workbench.save(new WorkbenchService.Draft(UUID.randomUUID(), Optional.empty(), 0, text, "GENERAL", audience, Optional.ofNullable(subject), Optional.empty(), uploads));
        workbench.submit(reference);
        own.add(reference);
        return reference;
    }

    private ContentReference pending() {
        return pending(verified(30), "PUBLIC", "complete harmless main text", List.of(new WorkbenchService.Upload("text/plain", CLEAR)), null);
    }

    private ReviewFileAccess.Request claim(ContentReference reference) { return R2PreparationClaims.claim(application, reference); }

    private void noAuthority(ContentReference reference) {
        assertThat(jdbc.queryForObject("select approved_revision from workbench_resource where id=?", UUID.class, reference.resourceId())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from moderation_decision d join moderation_snapshot s on s.id=d.submission_id where s.revision_id=?", Long.class, reference.revisionId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_task t join moderation_snapshot s on s.id=t.submission_id where s.revision_id=? and t.kind='ACTIVATE'", Long.class, reference.revisionId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id in (select id from moderation_snapshot where revision_id=?)", Long.class, reference.revisionId())).isZero();
    }

    private static byte[] image(String type) throws IOException {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), type, output)).isTrue();
        return output.toByteArray();
    }

    @ParameterizedTest @ValueSource(strings = {"PUBLIC", "PRIVATE"})
    void shouldMaterializeWholeOrderedRemoteFilesWithCurrentRealPolicy(String audience) throws Exception {
        byte[] maximum = "a".repeat(MAX_FILE_BYTES).getBytes(StandardCharsets.UTF_8);
        var uploads = List.of(new WorkbenchService.Upload("text/plain", maximum), new WorkbenchService.Upload("image/png", image("png")), new WorkbenchService.Upload("image/jpeg", image("jpeg")), new WorkbenchService.Upload("text/plain", CLEAR));
        var reference = pending(verified(30), audience, "m".repeat(MAX_MAIN_BYTES), uploads, null);
        var request = claim(reference);
        long before = protocol().count("GET");
        var material = reviewFiles.materialize(request);
        assertThat(material.files().stream().map(ReviewFileAccess.File::reference).toList()).isEqualTo(request.attachments());
        for (var file : material.files()) {
            var original = uploads.stream().filter(upload -> ContentDigest.bytes(upload.bytes()).equals(file.reference().digest())).findFirst().orElseThrow();
            assertThat(file.bytes()).isEqualTo(original.bytes());
            byte[] changed = file.bytes(); changed[0] ^= 1;
            assertThat(file.bytes()).isEqualTo(original.bytes());
        }
        assertThat(protocol().count("GET")).isEqualTo(before + 4);
        assertThat(CAPTURES).isEmpty();
        noAuthority(reference);
    }

    @ParameterizedTest @EnumSource(value = R2PrivateBlobStoreTest.Mode.class, names = {"WRONG_LENGTH", "WRONG_TYPE", "WRONG_ENCODING", "WRONG_ETAG", "SHORT", "OVERSIZE", "CHECKSUM", "RESET", "EMPTY", "REDIRECT", "THROTTLED", "STALL", "DRIBBLE"})
    void shouldDenyOneRemoteAttemptAndRecoverNativeClientAndDatabase(R2PrivateBlobStoreTest.Mode mode) {
        var reference = pending(); var request = claim(reference);
        long before = protocol().count("GET");
        protocol().mode = mode;
        long start = System.nanoTime();
        assertThatThrownBy(() -> reviewFiles.materialize(request)).isInstanceOf(ModerationFailure.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(9));
        assertThat(protocol().count("GET")).isEqualTo(before + 1);
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(CLEAR);
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
        assertThat(CAPTURES).isEmpty(); noAuthority(reference);
    }

    @ParameterizedTest @ValueSource(strings = {"token", "task", "generation", "expired", "restriction", "edit", "recall", "foreign", "digest", "order"})
    void shouldRejectChangedClaimOrCanonicalBindingBeforeAnyGet(String change) {
        UUID author = verified(30);
        var reference = pending(author, "PUBLIC", "complete main", List.of(new WorkbenchService.Upload("text/plain", CLEAR), new WorkbenchService.Upload("text/plain", new byte[]{120})), null);
        var original = claim(reference);
        ReviewFileAccess.Request request = original;
        switch (change) {
            case "token" -> request = new ReviewFileAccess.Request(reference, original.task(), UUID.randomUUID(), original.generation(), original.attachments());
            case "task" -> request = new ReviewFileAccess.Request(reference, UUID.randomUUID(), original.token(), original.generation(), original.attachments());
            case "generation" -> request = new ReviewFileAccess.Request(reference, original.task(), original.token(), original.generation() + 1, original.attachments());
            case "expired" -> jdbc.update("update moderation_task set lease_until=? where id=?", Timestamp.from(clock.instant().minusSeconds(1)), original.task());
            case "restriction" -> admin.restriction(operator, author, true);
            case "edit" -> edit(reference, author);
            case "recall" -> jdbc.update("update moderation_submission set recalled=true where id=(select id from moderation_snapshot where revision_id=?)", reference.revisionId());
            case "foreign" -> request = new ReviewFileAccess.Request(reference, original.task(), original.token(), original.generation(), List.of(new FileReference(UUID.randomUUID(), reference, original.attachments().getFirst().digest())));
            case "digest" -> request = new ReviewFileAccess.Request(reference, original.task(), original.token(), original.generation(), List.of(new FileReference(original.attachments().getFirst().id(), reference, "0".repeat(64))));
            case "order" -> request = new ReviewFileAccess.Request(reference, original.task(), original.token(), original.generation(), original.attachments().reversed());
            default -> throw new AssertionError();
        }
        long before = protocol().count("GET");
        var denied = request;
        assertThatThrownBy(() -> reviewFiles.materialize(denied)).isInstanceOfAny(ModerationFailure.class, IdentityFailure.class);
        assertThat(protocol().count("GET")).isEqualTo(before);
        assertThat(CAPTURES).isEmpty(); noAuthority(reference);
    }

    private void edit(ContentReference reference, UUID author) {
        as(author, () -> workbench.save(new WorkbenchService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), jdbc.queryForObject("select version from workbench_resource where id=?", Long.class, reference.resourceId()), "new canonical text", "GENERAL", "PUBLIC", Optional.empty(), Optional.empty(), List.of())));
    }

    @ParameterizedTest @CsvSource({"restriction,true", "restriction,false", "guardian,true", "guardian,false", "edit,true", "edit,false", "recall,true", "recall,false", "replacement,true", "replacement,false"})
    void shouldObserveNativeBlockedMutationInBothOrders(String change, boolean copyFirst) throws Exception {
        UUID author = verified(30);
        UUID minor = change.equals("guardian") ? verified(15) : null;
        UUID link;
        if (minor != null) {
            admin.evidence(operator, minor, "MINOR_DRAFT", null);
            link = guardians.request(minor, author, minor);
            admin.reviewGuardian(operator, link, 0, true);
        } else { link = null; }
        var reference = pending(author, minor == null ? "PUBLIC" : "PRIVATE", "complete lock fixture", List.of(new WorkbenchService.Upload("text/plain", CLEAR)), minor);
        var request = claim(reference);
        Runnable mutation = switch (change) {
            case "restriction" -> () -> admin.restriction(operator, author, true);
            case "guardian" -> () -> guardians.revoke(author, link, guardians.own(author).stream().filter(row -> row.id().equals(link)).findFirst().orElseThrow().version());
            case "edit" -> () -> edit(reference, author);
            case "recall" -> () -> jdbc.update("update moderation_submission set recalled=true where id=(select id from moderation_snapshot where revision_id=?)", reference.revisionId());
            case "replacement" -> () -> jdbc.update("update moderation_task set claim_token=? where id=?", UUID.randomUUID(), request.task());
            default -> throw new AssertionError();
        };
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var mutatorPid = new AtomicInteger();
        long gets = protocol().count("GET");
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            if (copyFirst) {
                protocol().gate = new R2PrivateBlobStoreTest.FakeS3.Gate(entered, release);
                protocol().mode = R2PrivateBlobStoreTest.Mode.HELD;
                var read = threads.submit(() -> reviewFiles.materialize(request));
                Future<?> changed = null;
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    changed = threads.submit(() -> tx(() -> { mutatorPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); mutation.run(); return null; }));
                    await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(mutatorPid.get()).isPositive());
                    await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0", Boolean.class, mutatorPid.get())).isTrue());
                } finally { release.countDown(); }
                assertThat(read.get(5, TimeUnit.SECONDS).files().getFirst().bytes()).isEqualTo(CLEAR);
                if (changed != null) { changed.get(5, TimeUnit.SECONDS); }
                assertThat(protocol().count("GET")).isEqualTo(gets + 1);
            } else {
                var changed = threads.submit(() -> tx(() -> {
                    mutatorPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); mutation.run();
                    application.getBean(jakarta.persistence.EntityManager.class).flush(); entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Mutation gate missing"); } }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Mutation interrupted"); }
                    return null;
                }));
                Future<?> read = null;
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    read = threads.submit(() -> reviewFiles.materialize(request));
                    await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))", Integer.class, mutatorPid.get())).isEqualTo(1));
                } finally { release.countDown(); }
                changed.get(5, TimeUnit.SECONDS);
                Future<?> denied = read;
                assertThatThrownBy(() -> denied.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(protocol().count("GET")).isEqualTo(gets);
            }
        }
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        assertThatThrownBy(() -> reviewFiles.materialize(request)).isInstanceOfAny(ModerationFailure.class, IdentityFailure.class);
        assertThat(CAPTURES).isEmpty(); noAuthority(reference);
    }

    @Test void shouldExpireLeaseDuringAdmittedRemoteCopyThenDenyAnotherAdmission() throws Exception {
        var reference = pending(); var request = claim(reference);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        protocol().gate = new R2PrivateBlobStoreTest.FakeS3.Gate(entered, release);
        protocol().mode = R2PrivateBlobStoreTest.Mode.HELD;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = threads.submit(() -> reviewFiles.materialize(request));
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); clock.advance(); clock.advance(); }
            finally { release.countDown(); }
            assertThat(read.get(5, TimeUnit.SECONDS).files().getFirst().bytes()).isEqualTo(CLEAR);
        }
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        long gets = protocol().count("GET");
        assertThatThrownBy(() -> reviewFiles.materialize(request)).isInstanceOf(ModerationFailure.class);
        assertThat(protocol().count("GET")).isEqualTo(gets); assertThat(CAPTURES).isEmpty();
    }

    @Test void shouldFinishAdmittedWorkerCopyAfterLeaseExpiryWithoutDispatchOrClaimMutation() throws Exception {
        var reference = pending();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        protocol().gate = new R2PrivateBlobStoreTest.FakeS3.Gate(entered, release); protocol().mode = R2PrivateBlobStoreTest.Mode.HELD;
        Map<String, Object> before;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = threads.submit(preparation::processOne);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                before = jdbc.queryForMap("select id,state,claim_token,attempts,reason from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?)", reference.revisionId());
                clock.advance(); clock.advance();
            } finally { release.countDown(); }
            assertThat(running.get(7, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForMap("select id,state,claim_token,attempts,reason from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?)", reference.revisionId())).isEqualTo(before);
        assertThat(CAPTURES).isEmpty(); noAuthority(reference);
    }

    @ParameterizedTest @ValueSource(strings = {"restriction", "guardian", "edit", "recall", "replacement"})
    void shouldObserveQueuedMutationAfterWorkerRemoteCopyBeforeFirstDispatch(String change) throws Exception {
        UUID author = verified(30); UUID minor = change.equals("guardian") ? verified(15) : null;
        UUID link;
        if (minor != null) { admin.evidence(operator, minor, "MINOR_DRAFT", null); link = guardians.request(minor, author, minor); admin.reviewGuardian(operator, link, 0, true); }
        else { link = null; }
        var reference = pending(author, minor == null ? "PUBLIC" : "PRIVATE", "worker copy mutation fixture", List.of(new WorkbenchService.Upload("text/plain", CLEAR)), minor);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        protocol().gate = new R2PrivateBlobStoreTest.FakeS3.Gate(entered, release); protocol().mode = R2PrivateBlobStoreTest.Mode.HELD;
        var pid = new AtomicInteger(); UUID replacement = UUID.randomUUID();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = threads.submit(preparation::processOne); Future<?> changed = null;
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                changed = threads.submit(() -> tx(() -> {
                    pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    switch (change) {
                        case "restriction" -> admin.restriction(operator, author, true);
                        case "guardian" -> guardians.revoke(author, link, guardians.own(author).stream().filter(row -> row.id().equals(link)).findFirst().orElseThrow().version());
                        case "edit" -> edit(reference, author);
                        case "recall" -> jdbc.update("update moderation_submission set recalled=true,generation=generation+1 where id=(select id from moderation_snapshot where revision_id=?)", reference.revisionId());
                        case "replacement" -> jdbc.update("update moderation_task set claim_token=?,reason='RS2_REPLACED' where submission_id=(select id from moderation_snapshot where revision_id=?)", replacement, reference.revisionId());
                        default -> throw new AssertionError();
                    }
                    return null;
                }));
                await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(pid.get()).isPositive());
                await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0", Boolean.class, pid.get())).isTrue());
            } finally { release.countDown(); }
            if (changed != null) { changed.get(6, TimeUnit.SECONDS); }
            assertThat(running.get(7, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(CAPTURES).isEmpty(); noAuthority(reference);
        if (change.equals("replacement")) { assertThat(jdbc.queryForObject("select claim_token from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?)", UUID.class, reference.revisionId())).isEqualTo(replacement); }
    }

    @ParameterizedTest @ValueSource(strings = {"lease", "replacement", "restriction", "edit", "recall"})
    void shouldRetainCommittedFirstPostAndDenySecondAfterCurrentMutation(String change) throws Exception {
        UUID author = verified(30);
        var reference = pending(author, "PUBLIC", "current in-flight main", List.of(new WorkbenchService.Upload("text/plain", CLEAR)), null);
        var gate = new WireGate(new CountDownLatch(1), new CountDownLatch(1)); WIRE_GATE.set(gate);
        UUID replacement = UUID.randomUUID();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = threads.submit(preparation::processOne);
            try {
                assertThat(gate.entered().await(7, TimeUnit.SECONDS)).isTrue();
                switch (change) {
                    case "lease" -> { clock.advance(); clock.advance(); }
                    case "replacement" -> jdbc.update("update moderation_task set claim_token=?,reason='RS2_REPLACED' where submission_id=(select id from moderation_snapshot where revision_id=?)", replacement, reference.revisionId());
                    case "restriction" -> admin.restriction(operator, author, true);
                    case "edit" -> edit(reference, author);
                    case "recall" -> jdbc.update("update moderation_submission set recalled=true,generation=generation+1 where id=(select id from moderation_snapshot where revision_id=?)", reference.revisionId());
                    default -> throw new AssertionError();
                }
            } finally { gate.release().countDown(); }
            assertThat(running.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(CAPTURES).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from moderation_preparation_observation o join moderation_preparation_dispatch d on d.id=o.dispatch_id join moderation_snapshot s on s.id=d.submission_id where s.revision_id=? and disposition='HISTORICAL_ONLY'", Long.class, reference.revisionId())).isEqualTo(1);
        if (change.equals("replacement")) { assertThat(jdbc.queryForObject("select claim_token from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?)", UUID.class, reference.revisionId())).isEqualTo(replacement); }
        noAuthority(reference);
    }

    @Test void shouldRequireUniqueRealGuardEvenWithoutAttachmentsAndRejectOuterTransaction() {
        var reference = pending(verified(30), "PUBLIC", "complete empty attachment main", List.of(), null);
        var request = claim(reference);
        var service = (PrivateFileService) reviewFiles;
        assertThatThrownBy(() -> tx(() -> service.materialize(request))).isInstanceOf(ModerationFailure.class);
        for (int count : List.of(0, 2)) {
            var beans = new StaticListableBeanFactory();
            for (int index = 0; index < count; index++) { beans.addBean("guard" + index, application.getBean(ReviewFilePolicy.class)); }
            var isolated = new PrivateFileService(jdbc, transactions, application.getBeanProvider(AttachmentAccessPolicy.class), application.getBean(IdentityAccess.class), proof, application.getBean(Admission.class), clock, application.getBean(FileInspector.class), application.getBean(LocalPrivateBlobStore.class), application.getBeanProvider(R2PrivateBlobStore.class));
            isolated.reviewPolicies(beans.getBeanProvider(ReviewFilePolicy.class));
            assertThatThrownBy(() -> isolated.materialize(request)).isInstanceOf(ModerationFailure.class);
        }
        assertThat(reviewFiles.materialize(request).files()).isEmpty();
        assertThat(CAPTURES).isEmpty();
    }

    @Test void shouldRejectDuplicateExcessAndForeignResourceRequestConstruction() {
        var reference = pending(); var request = claim(reference); var file = request.attachments().getFirst();
        assertThatThrownBy(() -> new ReviewFileAccess.Request(reference, request.task(), request.token(), -1, List.of(file))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewFileAccess.Request(reference, request.task(), request.token(), request.generation(), List.of(file, file))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewFileAccess.Request(reference, request.task(), request.token(), request.generation(), Collections.nCopies(5, file))).isInstanceOf(IllegalArgumentException.class);
        var foreign = new FileReference(file.id(), new ContentReference(reference.ownerKind(), UUID.randomUUID(), reference.revisionId()), file.digest());
        assertThatThrownBy(() -> new ReviewFileAccess.Request(reference, request.task(), request.token(), request.generation(), List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
        assertThat(CAPTURES).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"PUBLIC", "PRIVATE"})
    void shouldSendCompleteMixedRemoteMaterialThroughBothActualWireStages(String audience) throws Exception {
        var uploads = List.of(new WorkbenchService.Upload("text/plain", "a".repeat(MAX_FILE_BYTES).getBytes(StandardCharsets.UTF_8)), new WorkbenchService.Upload("image/png", image("png")), new WorkbenchService.Upload("image/jpeg", image("jpeg")), new WorkbenchService.Upload("text/plain", CLEAR));
        String main = "m".repeat(MAX_MAIN_BYTES);
        var reference = pending(verified(30), audience, main, uploads, null);
        long gets = protocol().count("GET");
        assertThat(preparation.processOne()).isTrue();
        assertThat(protocol().count("GET")).isEqualTo(gets + 4);
        assertThat(CAPTURES).hasSize(2);
        assertWholeWires(reference, main, uploads, CAPTURES);
        assertThat(jdbc.queryForObject("select reason from moderation_submission where id=(select id from moderation_snapshot where revision_id=?)", String.class, reference.revisionId())).isEqualTo("PREPARATION_ONLY");
        noAuthority(reference);
    }

    private void assertWholeWires(ContentReference reference, String main, List<WorkbenchService.Upload> uploads, List<Capture> captures) {
        var references = jdbc.query("select id,digest from files_artifact where resource_id=? and revision_id=? order by id", (row, index) -> new FileReference(row.getObject(1, UUID.class), reference, row.getString(2)), reference.resourceId(), reference.revisionId());
        StringBuilder expectedText = new StringBuilder(main);
        List<String> images = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            var file = references.get(index);
            var upload = uploads.stream().filter(value -> ContentDigest.bytes(value.bytes()).equals(file.digest())).findFirst().orElseThrow();
            if (upload.mediaType().equals("text/plain")) { expectedText.append("\n[attachment-").append(index + 1).append(":text/plain]\n").append(new String(upload.bytes(), StandardCharsets.UTF_8)); }
            else { images.add("data:" + upload.mediaType() + ";base64," + Base64.getEncoder().encodeToString(upload.bytes())); }
        }
        for (var capture : captures) {
            assertThat(capture.body().length).isLessThanOrEqualTo(8650752).isLessThanOrEqualTo(10485760);
            var body = JSON.readTree(capture.body());
            boolean moderation = capture.path().equals("/v1/moderations");
            JsonNode parts = moderation ? body.path("input") : body.path("input").get(0).path("content");
            if (parts.isTextual()) { assertThat(parts.asString()).isEqualTo(expectedText.toString()); }
            else {
                assertThat(parts.get(0).path("text").asString()).isEqualTo(expectedText.toString());
                assertThat(parts.size()).isEqualTo(images.size() + 1);
                for (int index = 0; index < images.size(); index++) {
                    var value = parts.get(index + 1).path("image_url");
                    String actual = moderation ? value.path("url").asString() : value.asString();
                    assertThat(actual).isEqualTo(images.get(index));
                    String encoded = actual.substring(actual.indexOf(',') + 1);
                    assertThat(Base64.getDecoder().decode(encoded)).isEqualTo(Base64.getDecoder().decode(images.get(index).substring(images.get(index).indexOf(',') + 1)));
                }
            }
        }
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "r2.current.migrate", matches = "true")
    void shouldPreserveBothCopiedPopulatedV11SchemasAndMigrateFreshLocalToTwelve() throws Exception {
        var binding = localRestored();
        String upgrade = binding.getProperty("schema");
        assertThat(upgrade).matches("r2_upgrade_[a-f0-9]{32}");
        for (String schema : List.of("r2_storage_test", upgrade)) {
            var migration = org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("11");
            var tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema=? and table_name<>'flyway_schema_history' order by table_name", String.class, schema);
            var before = schemaSnapshot(schema, tables);
            var history = jdbc.queryForList("select * from " + schema + ".flyway_schema_history order by installed_rank");
            assertThat(history.stream().filter(row -> row.get("version") != null).count()).isEqualTo(11);
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            migration.validate(); assertThat(migration.migrate().migrationsExecuted).isZero();
            assertThat(schemaSnapshot(schema, tables)).isEqualTo(before);
            assertThat(jdbc.queryForList("select * from " + schema + ".flyway_schema_history where installed_rank<=? order by installed_rank", history.getLast().get("installed_rank"))).isEqualTo(history);
            assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("12");
            Files.writeString(fixture.resolve(schema + "-migration.private.json"), JSON.writeValueAsString(Map.of("schema", schema, "oldComponents", before, "oldHistory", history, "newMigrationCount", 1, "repeat", 0)), StandardOpenOption.CREATE_NEW);
        }
        var fresh = org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas("r2_current_local").defaultSchema("r2_current_local").load();
        assertThat(fresh.info().current()).isNull(); assertThat(fresh.migrate().migrationsExecuted).isEqualTo(12);
        fresh.validate(); assertThat(fresh.migrate().migrationsExecuted).isZero();
        var review = application.getBean(org.flywaydb.core.Flyway.class);
        assertThat(review.info().current().getVersion().getVersion()).isEqualTo("12");
        review.validate(); assertThat(review.migrate().migrationsExecuted).isZero();
    }

    private Map<String, Object> schemaSnapshot(String schema, List<String> tables) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            assertThat(table).matches("[a-z0-9_]+");
            snapshot.put("rows:" + table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from " + schema + "." + table + " t", String.class));
        }
        snapshot.put("columns", jdbc.queryForList("select table_name,column_name,ordinal_position,data_type,is_nullable,column_default from information_schema.columns where table_schema=? order by table_name,ordinal_position", schema).stream().filter(row -> tables.contains(row.get("table_name"))).toList());
        snapshot.put("constraintsAndKeys", jdbc.queryForList("select r.relname as table_name,c.conname,c.contype,pg_get_constraintdef(c.oid) as definition from pg_constraint c join pg_class r on r.oid=c.conrelid join pg_namespace n on n.oid=r.relnamespace where n.nspname=? order by r.relname,c.conname", schema).stream().filter(row -> tables.contains(row.get("table_name"))).toList());
        snapshot.put("indexes", jdbc.queryForList("select tablename,indexname,indexdef from pg_indexes where schemaname=? order by tablename,indexname", schema).stream().filter(row -> tables.contains(row.get("tablename"))).toList());
        return snapshot;
    }

    @Test void shouldBoundTwoMaximumActualRemotePipelinesAndRefuseThirdClaim() throws Exception {
        byte[] maximum = "\\".repeat(MAX_FILE_BYTES).getBytes(StandardCharsets.UTF_8);
        String main = "\u0001".repeat(MAX_MAIN_BYTES);
        List<WorkbenchService.Upload> uploads = new ArrayList<>();
        for (int index = 0; index < 4; index++) { uploads.add(new WorkbenchService.Upload("text/plain", maximum)); }
        long puts = protocol().count("PUT"); long gets = protocol().count("GET");
        List<ContentReference> references = new ArrayList<>();
        for (int index = 0; index < 2; index++) { clock.advance(); references.add(pending(verified(30), "PUBLIC", main, uploads, null)); }
        clock.advance(); var waiting = pending(verified(30), "PUBLIC", "third has no permit", List.of(), null);
        var gate = new WireGate(new CountDownLatch(2), new CountDownLatch(1)); WIRE_GATE.set(gate);
        var heap = new AtomicLong();
        var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assertThat(allocation.isThreadAllocatedMemorySupported()).isTrue();
        long allocatedBefore = allocation.getTotalThreadAllocatedBytes();
        try (var sampler = Executors.newSingleThreadScheduledExecutor(); var threads = Executors.newFixedThreadPool(2)) {
            sampler.scheduleAtFixedRate(() -> heap.accumulateAndGet(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), Math::max), 0, 10, TimeUnit.MILLISECONDS);
            var first = threads.submit(preparation::processOne); var second = threads.submit(preparation::processOne);
            try {
                assertThat(gate.entered().await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(preparation.processOne()).isFalse();
                assertThat(jdbc.queryForObject("select attempts from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?)", Integer.class, waiting.revisionId())).isZero();
            } finally { gate.release().countDown(); }
            assertThat(first.get(15, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(15, TimeUnit.SECONDS)).isTrue();
            sampler.shutdownNow(); assertThat(sampler.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(protocol().count("PUT")).isEqualTo(puts + 8); assertThat(protocol().count("GET")).isEqualTo(gets + 8);
        assertThat(CAPTURES).hasSize(4);
        assertWholeWires(references.getFirst(), main, uploads, CAPTURES);
        for (var reference : references) { noAuthority(reference); }
        assertThat(heap.get()).isPositive().isLessThanOrEqualTo(768L * 1024 * 1024);
        long allocated = allocation.getTotalThreadAllocatedBytes() - allocatedBefore;
        assertThat(allocated).isPositive();
        Files.writeString(fixture.resolve("maximum-pipelines.private.json"), JSON.writeValueAsString(Map.of("puts", 8, "gets", 8, "posts", 4, "thirdAttempts", 0, "sampledHeapBytes", heap.get(), "totalPlatformAllocationDeltaBytes", allocated, "heapSampleIntervalMillis", 10, "jvmMaximumBytes", Runtime.getRuntime().maxMemory(), "limit", "Finite fixture sample; excludes unsampled peaks and is not a 20-journey or heap guarantee")), StandardOpenOption.CREATE_NEW);
    }

    @Test void shouldRefuseExhaustedNativePoolWithoutStorageTrafficAndRecoverEveryLease() throws Exception {
        var reference = pending(); var request = claim(reference);
        var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
        assertThat(pool.getConnectionTimeout()).isEqualTo(500);
        assertThat(pool.getValidationTimeout()).isEqualTo(250);
        long gets = protocol().count("GET"); long puts = protocol().count("PUT");
        List<java.sql.Connection> leases = new ArrayList<>();
        try {
            for (int index = 0; index < pool.getMaximumPoolSize(); index++) { leases.add(pool.getConnection()); }
            long started = System.nanoTime();
            assertThatThrownBy(() -> reviewFiles.materialize(request)).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(protocol().count("GET")).isEqualTo(gets); assertThat(protocol().count("PUT")).isEqualTo(puts);
        } finally { for (var lease : leases) { lease.close(); } }
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(CLEAR);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test void shouldAbortInterruptedRemoteMaterializationAndRecover() throws Exception {
        var reference = pending(); var request = claim(reference);
        long gets = protocol().count("GET");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        protocol().gate = new R2PrivateBlobStoreTest.FakeS3.Gate(entered, release); protocol().mode = R2PrivateBlobStoreTest.Mode.HELD;
        var outcome = new AtomicReference<Throwable>(); var interrupted = new AtomicBoolean();
        Thread thread = Thread.ofPlatform().start(() -> { try { reviewFiles.materialize(request); } catch (RuntimeException denied) { outcome.set(denied); interrupted.set(Thread.currentThread().isInterrupted()); } });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); thread.interrupt(); thread.join(8000);
            assertThat(thread.isAlive()).isFalse(); assertThat(outcome.get()).isInstanceOf(ModerationFailure.class); assertThat(interrupted).isTrue();
            assertThat(protocol().count("GET")).isEqualTo(gets + 1);
        } finally { release.countDown(); thread.interrupt(); thread.join(8000); }
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(CLEAR);
        assertThat(protocol().count("GET")).isEqualTo(gets + 2);
        assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(CAPTURES).isEmpty();
    }

    @Test void shouldBoundMaterializationRowLockAtTwoSecondsThenRecover() throws Exception {
        var reference = pending(); var request = claim(reference);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        long gets = protocol().count("GET");
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var holding = threads.submit(() -> tx(() -> {
                jdbc.queryForObject("select id from workbench_resource where id=? for update", UUID.class, reference.resourceId()); entered.countDown();
                try { if (!release.await(6, TimeUnit.SECONDS)) { throw new IllegalStateException("Owned lock gate missing"); } }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Owned lock interrupted"); }
                return null;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                Throwable failure = catchThrowable(() -> reviewFiles.materialize(request));
                assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
                Throwable cause = failure;
                while (cause.getCause() != null) { cause = cause.getCause(); }
                assertThat(((org.postgresql.util.PSQLException) cause).getSQLState()).isEqualTo("55P03");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(1800), Duration.ofSeconds(4));
                assertThat(protocol().count("GET")).isEqualTo(gets);
            } finally { release.countDown(); }
            holding.get(5, TimeUnit.SECONDS);
        }
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(CLEAR);
        assertThat(CAPTURES).isEmpty();
    }

    @Test void shouldFinishHealthyProgressingRemoteCopyBeyondTwoSeconds() {
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
        var reference = pending(verified(30), "PUBLIC", "healthy progressing copy", List.of(new WorkbenchService.Upload("text/plain", bytes)), null);
        var request = claim(reference); protocol().mode = R2PrivateBlobStoreTest.Mode.DRIBBLE;
        long started = System.nanoTime();
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(bytes);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofSeconds(2)).isLessThan(Duration.ofSeconds(6));
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(bytes);
    }

    @ParameterizedTest @ValueSource(strings = {"unsafe", "pdf", "type", "size-zero", "size-large", "metadata-digest", "missing", "corrupt"})
    void shouldDenyWholeActualWorkerAttemptAfterOwnedFaultWithoutAnyPost(String change) throws Exception {
        var reference = Set.of("unsafe", "pdf").contains(change)
                ? pending(verified(30), "PUBLIC", "whole uninspectable fixture", List.of(new WorkbenchService.Upload(change.equals("pdf") ? "application/pdf" : "application/octet-stream", CLEAR)), null)
                : pending();
        var request = claim(reference); var file = request.attachments().getFirst();
        long gets = protocol().count("GET");
        if (Set.of("type", "size-zero", "size-large", "metadata-digest").contains(change)) {
            var before = jdbc.queryForList("select * from files_artifact where id=?", file.id());
            String sql = switch (change) {
                case "type" -> "update files_artifact set media_type='application/x-unreleased' where id=?";
                case "size-zero" -> "update files_artifact set size=0 where id=?";
                case "size-large" -> "update files_artifact set size=1048577 where id=?";
                case "metadata-digest" -> "update files_artifact set digest='" + "0".repeat(64) + "' where id=?";
                default -> throw new AssertionError();
            };
            assertThatThrownBy(() -> jdbc.update(sql, file.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForList("select * from files_artifact where id=?", file.id())).isEqualTo(before);
            assertThat(protocol().count("GET")).isEqualTo(gets); assertThat(CAPTURES).isEmpty(); noAuthority(reference);
            return;
        }
        switch (change) {
            case "unsafe", "pdf" -> { }
            case "missing", "corrupt" -> {
                String key = "/changeo-r2-preparation/private-blobs/" + file.id();
                var stored = protocol().objects.get(key);
                Path original = protocol().root.resolve(file.id().toString());
                Files.copy(original, fixture.resolve("preserved-" + file.id()), StandardCopyOption.COPY_ATTRIBUTES);
                if (change.equals("missing")) { Files.move(original, fixture.resolve("moved-" + file.id())); protocol().objects.remove(key); }
                else {
                    byte[] corrupt = stored.bytes().clone(); corrupt[0] ^= 1;
                    Files.write(original, corrupt); protocol().objects.put(key, new R2PrivateBlobStoreTest.FakeS3.Stored(corrupt, stored.type(), stored.etag()));
                }
            }
            default -> throw new AssertionError();
        }
        clock.advance(); clock.advance();
        assertThat(preparation.processOne()).isTrue();
        assertThat(CAPTURES).isEmpty();
        assertThat(protocol().count("GET")).isEqualTo(gets + (Set.of("missing", "corrupt").contains(change) ? 1 : 0));
        noAuthority(reference);
        assertThat(protocol().count("DELETE")).isZero();
    }

    @Test void shouldRejectUnreleasedTypeBeforeAnyStorageOrTaskEffect() {
        UUID author = verified(30); actor.set(author);
        long puts = protocol().count("PUT"); long gets = protocol().count("GET");
        assertThatThrownBy(() -> workbench.save(new WorkbenchService.Draft(UUID.randomUUID(), Optional.empty(), 0, "unsupported fixture", "GENERAL", "PUBLIC", Optional.empty(), Optional.empty(), List.of(new WorkbenchService.Upload("application/x-unreleased", CLEAR))))).isInstanceOf(ModerationFailure.class);
        assertThat(protocol().count("PUT")).isEqualTo(puts); assertThat(protocol().count("GET")).isEqualTo(gets); assertThat(CAPTURES).isEmpty();
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "r2.current.capture", matches = "true")
    void shouldRetainCurrentPendingRemoteFixtureForCompleteRestore() throws Exception {
        String contact = "r2-current-restore-" + UUID.randomUUID() + "@example.test";
        UUID author = register(contact, 30);
        var reference = pending(author, "PUBLIC", "complete retained current remote review fixture", List.of(new WorkbenchService.Upload("text/plain", CLEAR)), null);
        var file = file(reference);
        var values = new Properties();
        values.setProperty("contact", contact); values.setProperty("password", PASSWORD); values.setProperty("actor", author.toString());
        values.setProperty("resource", reference.resourceId().toString()); values.setProperty("revision", reference.revisionId().toString()); values.setProperty("file", file.id().toString());
        values.setProperty("digest", file.digest()); values.setProperty("schema", "r2_current_review"); values.setProperty("clock", clock.instant().toString());
        Path target = Path.of(".runtime/current-review-restore-fixture.properties");
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) { values.store(output, null); }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        assertThat(jdbc.queryForObject("select state from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?)", String.class, reference.revisionId())).isEqualTo("PENDING");
        noAuthority(reference); own.remove(reference);
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "r2.current.restore", matches = "true")
    void shouldAuthenticateRestoredCurrentClaimMaterializeDenyFaultsAndReopenSdk() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("r2_storage_current_restore_structural_qa");
        var values = settings("current-review-restore-fixture.properties");
        while (clock.instant().isBefore(java.time.Instant.parse(values.getProperty("clock")))) { clock.advance(); }
        var authenticated = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).param("username", values.getProperty("contact")).param("password", values.getProperty("password")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/account")).andReturn();
        assertThat(authenticated.getRequest().getSession(false)).isNotNull();
        UUID author = UUID.fromString(values.getProperty("actor")); actor.set(author);
        var reference = new ContentReference(SurfaceKind.REQUEST_BODY, UUID.fromString(values.getProperty("resource")), UUID.fromString(values.getProperty("revision")));
        own.add(reference); var request = claim(reference);
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(CLEAR);
        assertThatThrownBy(() -> files.read(request.attachments().getFirst(), FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        long gets = protocol().count("GET");
        var orphan = settings("current-remote-restore-fixture.properties").getProperty("orphan");
        assertThat(protocol().objects).containsKey("/changeo-r2-preparation/private-blobs/" + orphan);
        for (var replacement : List.of(new FileReference(UUID.fromString(orphan), reference, values.getProperty("digest")), new FileReference(UUID.randomUUID(), reference, values.getProperty("digest")))) {
            assertThatThrownBy(() -> reviewFiles.materialize(new ReviewFileAccess.Request(reference, request.task(), request.token(), request.generation(), List.of(replacement)))).isInstanceOf(ModerationFailure.class);
        }
        assertThatThrownBy(() -> reviewFiles.materialize(new ReviewFileAccess.Request(reference, request.task(), request.token(), request.generation() + 1, request.attachments()))).isInstanceOf(ModerationFailure.class);
        assertThat(protocol().count("GET")).isEqualTo(gets);
        protocol().mode = R2PrivateBlobStoreTest.Mode.CHECKSUM;
        assertThatThrownBy(() -> reviewFiles.materialize(request)).isInstanceOf(ModerationFailure.class);
        protocol().mode = R2PrivateBlobStoreTest.Mode.NORMAL;
        assertThat(reviewFiles.materialize(request).files().getFirst().bytes()).isEqualTo(CLEAR);
        edit(reference, author);
        gets = protocol().count("GET");
        assertThatThrownBy(() -> reviewFiles.materialize(request)).isInstanceOf(ModerationFailure.class);
        assertThat(protocol().count("GET")).isEqualTo(gets); assertThat(CAPTURES).isEmpty(); noAuthority(reference);
        assertRestoredCommittedReplay(author, reference, request.attachments().getFirst());
        Files.writeString(fixture.resolve("current-restore-result.private.json"), JSON.writeValueAsString(Map.of("actualCredentialSessionLogin", true, "wholeRemoteMaterial", true, "ordinaryPendingDenied", true, "foreignOrphanGenerationCorruptStaleDenied", true, "providerPosts", CAPTURES.size(), "sdkClosedAndReopened", true, "immutableReplayNewPuts", 0)), StandardOpenOption.CREATE_NEW);
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "r2.current.restore", matches = "true")
    void shouldReopenRestoredSdkAndReplayOriginalCommittedIntakeWithoutNewPut() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("r2_storage_current_restore_structural_qa");
        var values = settings("current-review-restore-fixture.properties");
        var authenticated = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).param("username", values.getProperty("contact")).param("password", values.getProperty("password")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/account")).andReturn();
        assertThat(authenticated.getRequest().getSession(false)).isNotNull();
        UUID author = UUID.fromString(values.getProperty("actor"));
        assertThat(accounts.credentials(values.getProperty("contact")).orElseThrow().id()).isEqualTo(author);
        var reference = new ContentReference(SurfaceKind.REQUEST_BODY, UUID.fromString(values.getProperty("resource")), UUID.fromString(values.getProperty("revision")));
        var file = new FileReference(UUID.fromString(values.getProperty("file")), reference, values.getProperty("digest"));
        assertRestoredCommittedReplay(author, reference, file);
        assertThat(CAPTURES).isEmpty();
        Files.writeString(fixture.resolve("current-replay-result.private.json"), JSON.writeValueAsString(Map.of("actualRestoredCredentialSession", true, "originalCommittedFileAndCommand", true, "immutableBindingAndMetadataUnchanged", true, "wholeBytesEqual", true, "newPuts", 0, "providerPosts", 0, "scopedOriginalActor", true, "dedicatedSdkClosed", true)), StandardOpenOption.CREATE_NEW);
    }

    private void assertRestoredCommittedReplay(UUID author, ContentReference reference, FileReference file) {
        var metadata = jdbc.queryForMap("select * from files_artifact where id=?", file.id());
        var binding = jdbc.queryForMap("select store_id,object_key,etag from files_blob_binding where file_id=?", file.id());
        var command = jdbc.queryForObject("select command_id from files_artifact where id=?", UUID.class, file.id());
        application.getBean(R2PrivateBlobStore.class).close();
        try (var reopened = protocol().store(journal())) {
            assertThat(reopened.storeId()).isEqualTo(binding.get("store_id"));
            assertThat(reopened.read(new PrivateBlobStore.Remote(file.id(), (String) binding.get("store_id"), (String) binding.get("object_key"), (String) binding.get("etag")), "text/plain", CLEAR.length)).isEqualTo(CLEAR);
            var providers = new StaticListableBeanFactory(); providers.addBean("reopened", reopened);
            var service = new PrivateFileService(jdbc, transactions, application.getBeanProvider(AttachmentAccessPolicy.class), application.getBean(IdentityAccess.class), proof, application.getBean(Admission.class), clock, application.getBean(FileInspector.class), application.getBean(LocalPrivateBlobStore.class), providers.getBeanProvider(R2PrivateBlobStore.class));
            long puts = protocol().count("PUT");
            assertThat(as(author, () -> tx(() -> service.intake(command, new AuthorContext(new ActorId(author), new SubjectId(author), Capability.NEW_REQUEST), reference, "text/plain", CLEAR)))).isEqualTo(file);
            assertThat(protocol().count("PUT")).isEqualTo(puts);
        }
        assertThat(jdbc.queryForMap("select * from files_artifact where id=?", file.id())).isEqualTo(metadata);
        assertThat(jdbc.queryForMap("select store_id,object_key,etag from files_blob_binding where file_id=?", file.id())).isEqualTo(binding);
    }

    @ParameterizedTest @ValueSource(strings = {"missing-sdk", "wrong-store"})
    void shouldRejectImmutableRemoteBindingWithoutLocalFallbackOrNewGet(String fault) {
        var reference = pending(); var request = claim(reference);
        var remoteBeans = new StaticListableBeanFactory();
        try (var wrong = new R2PrivateBlobStore(protocol().endpoint(), "different-owned-fixture-bucket", "r2-preparation-key", "r2-preparation-secret", new PrivateBlobJournal(fixture.resolve("wrong-store-" + UUID.randomUUID()), clock))) {
            if (fault.equals("wrong-store")) { remoteBeans.addBean("wrong", wrong); }
            var isolated = new PrivateFileService(jdbc, transactions, application.getBeanProvider(AttachmentAccessPolicy.class), application.getBean(IdentityAccess.class), proof, application.getBean(Admission.class), clock, application.getBean(FileInspector.class), application.getBean(LocalPrivateBlobStore.class), remoteBeans.getBeanProvider(R2PrivateBlobStore.class));
            isolated.reviewPolicies(application.getBeanProvider(ReviewFilePolicy.class));
            long gets = protocol().count("GET");
            assertThatThrownBy(() -> isolated.materialize(request)).isInstanceOf(ModerationFailure.class);
            assertThat(protocol().count("GET")).isEqualTo(gets); assertThat(CAPTURES).isEmpty();
        }
    }

    record WireGate(CountDownLatch entered, CountDownLatch release) {}
    record Capture(String path, byte[] body) {}
}

class PrivateBlobCurrentOrdinaryPostgresTest extends PrivateBlobStorageFixture {
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "r2.current.capture", matches = "true")
    void shouldRetainCurrentApprovedRemoteCredentialByteAndOrphanFixture() throws Exception {
        String contact = "r2-current-approved-" + UUID.randomUUID() + "@example.test";
        UUID author = register(contact, 30); actor.set(author);
        var reference = save("synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.empty(), "text/plain");
        approve(reference); var file = file(reference);
        UUID orphan = UUID.randomUUID();
        try (var store = protocol().store(journal().resolve("current-orphan"))) { store.create(orphan, "text/plain", CLEAR); }
        var values = new Properties();
        values.setProperty("contact", contact); values.setProperty("password", PASSWORD); values.setProperty("actor", author.toString());
        values.setProperty("resource", reference.resourceId().toString()); values.setProperty("revision", reference.revisionId().toString()); values.setProperty("file", file.id().toString());
        values.setProperty("digest", file.digest()); values.setProperty("schema", "r2_storage_test"); values.setProperty("clock", clock.instant().toString()); values.setProperty("orphan", orphan.toString());
        Path target = Path.of(".runtime/current-remote-restore-fixture.properties");
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) { values.store(output, null); }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "r2.current.restore", matches = "true")
    void shouldAuthenticateRestoredApprovedRemoteAndDenyOrphanWithCurrentHeaders() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("r2_storage_current_restore_structural_qa");
        var values = settings("current-remote-restore-fixture.properties");
        while (clock.instant().isBefore(java.time.Instant.parse(values.getProperty("clock")))) { clock.advance(); }
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).param("username", values.getProperty("contact")).param("password", values.getProperty("password")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/account")).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false); assertThat(session).isNotNull();
        actor.set(UUID.fromString(values.getProperty("actor")));
        var reference = new ContentReference(SurfaceKind.PRIVATE_MESSAGE, UUID.fromString(values.getProperty("resource")), UUID.fromString(values.getProperty("revision")));
        var file = new FileReference(UUID.fromString(values.getProperty("file")), reference, values.getProperty("digest"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/content/files/" + file.id()).session(session).param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("digest", file.digest()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(CLEAR))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Referrer-Policy", "same-origin"));
        long gets = protocol().count("GET");
        assertThatThrownBy(() -> files.read(new FileReference(UUID.fromString(values.getProperty("orphan")), reference, file.digest()), FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThat(protocol().count("GET")).isEqualTo(gets);
    }

    @Test void shouldPreserveOrdinaryRemoteBytesAndCurrentHeadersAcrossReadinessAndAcquisitionRefusal() throws Exception {
        UUID author = verified(30); actor.set(author);
        var reference = save("synthetic:clear", "GENERAL", "PRIVATE", Optional.empty(), Optional.empty(), "text/plain");
        approve(reference); var file = file(reference);
        var readiness = application.getBean(ar.changeo.config.RuntimeReadiness.class);
        org.springframework.boot.availability.AvailabilityChangeEvent.publish(application, org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
        assertThat(readiness.ready()).isTrue();
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
        jdbc.execute("select pg_sleep(2.2)");
        var pool = application.getBean(com.zaxxer.hikari.HikariDataSource.class);
        List<java.sql.Connection> leases = new ArrayList<>();
        long gets = protocol().count("GET"); long puts = protocol().count("PUT");
        try {
            for (int index = 0; index < pool.getMaximumPoolSize(); index++) { leases.add(pool.getConnection()); }
            assertThat(readiness.ready()).isFalse();
            assertThatThrownBy(() -> files.read(file, FileAccess.Purpose.ORDINARY)).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
            assertThat(protocol().count("GET")).isEqualTo(gets); assertThat(protocol().count("PUT")).isEqualTo(puts);
        } finally { for (var lease : leases) { lease.close(); } }
        assertThat(readiness.ready()).isTrue();
        assertThat(files.read(file, FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(CLEAR);
        jdbc.execute("select pg_sleep(2.2)");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/content/files/" + file.id()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(new ar.changeo.security.AccountPrincipal(author, "framework@example.test", "", accounts.epoch(author))))
                .param("resource", reference.resourceId().toString()).param("revision", reference.revisionId().toString()).param("digest", file.digest()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(CLEAR))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Referrer-Policy", "same-origin"));
        assertThat(application.getBeanNamesForType(ModerationPreparationWorker.class)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from moderation_preparation_dispatch where reference_id=(select id from moderation_reference where resource_id=?)", Long.class, reference.resourceId())).isZero();
    }
}
