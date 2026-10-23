package ar.changeo.moderation;

import ar.changeo.ChangeoApplication;
import ar.changeo.files.api.*;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.SessionProof;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes={ChangeoApplication.class,ModerationPreparationPostgresTest.Framework.class},properties={
        "spring.flyway.schemas=preparation_test","spring.flyway.default-schema=preparation_test","spring.jpa.properties.hibernate.default_schema=preparation_test","spring.datasource.hikari.schema=preparation_test",
        "changeo.synthetic-evidence=true","changeo.synthetic-moderation=false","changeo.moderation-worker=false","changeo.moderation-preparation=openai-wire",
        "changeo.mailbox=.runtime/preparation-mailbox","changeo.mfa-key=.runtime/preparation-mfa.key","changeo.files-root=.runtime/preparation-artifacts"})
class ModerationPreparationPostgresTest {
    private static HttpServer server;
    private static ExecutorService fakeThreads;
    private static final AtomicInteger WIRES=new AtomicInteger();
    private static final AtomicReference<Handler> HANDLER=new AtomicReference<>();
    private static final List<Path> CAPTURES=new CopyOnWriteArrayList<>();
    private static Path fixture;
    private static String schema;
    private final ThreadLocal<UUID> actor=new ThreadLocal<>();
    private final Map<String,String> tokens=new ConcurrentHashMap<>();
    private UUID administrator;
    @Autowired AccountService accounts;
    @Autowired AdminService admin;
    @Autowired OperatorService operators;
    @Autowired GuardianService guardians;
    @Autowired IdentityAccess identity;
    @Autowired WorkbenchService workbench;
    @Autowired WorkbenchStore canonical;
    @Autowired ModerationService moderation;
    @Autowired ModerationStore store;
    @Autowired ModerationWorker synthetic;
    @Autowired ModerationPreparationWorker worker;
    @MockitoSpyBean PreparationFilePolicy policy;
    @Autowired ReviewFileAccess reviewFiles;
    @Autowired FileAccess files;
    @Autowired OpenAiReviewClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestClock clock;
    @Autowired IndependentOwner independent;
    @Autowired PasswordEncoder passwords;
    @Autowired org.springframework.security.core.userdetails.UserDetailsService users;
    @Autowired Flyway flyway;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoBean SessionProof proof;
    @MockitoBean TokenDelivery delivery;

    @TestConfiguration static class Framework {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
        @Bean IndependentOwner independentOwner(JdbcTemplate jdbc,IdentityAccess identity,SessionProof proof,ModerationStore store) { return new IndependentOwner(jdbc,identity,proof,store); }
    }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));
        void advance(long seconds) { now.updateAndGet(value->value.plusSeconds(seconds)); }
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    @FunctionalInterface interface Handler { void handle(HttpExchange exchange,byte[] body) throws Exception; }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) throws IOException {
        schema=System.getenv("CHANGEO_PREPARATION_TEST_SCHEMA");
        if(schema==null) { schema="preparation_"+UUID.randomUUID().toString().replace("-","").substring(0,16); }
        if(!schema.matches("preparation_[a-f0-9]{16}")) { throw new IllegalArgumentException("Invalid owned preparation test schema"); }
        properties.add("spring.flyway.schemas",()->schema);properties.add("spring.flyway.default-schema",()->schema);
        properties.add("spring.jpa.properties.hibernate.default_schema",()->schema);properties.add("spring.datasource.hikari.schema",()->schema);
        fixture=Path.of(".runtime/external-moderation-preparation-20261020/postgres-fixture-"+UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(fixture,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path credential=fixture.resolve("fake.credential");Files.writeString(credential,OpenAiReviewClientTest.CREDENTIAL,StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(credential,PosixFilePermissions.fromString("rw-------"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);fakeThreads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(fakeThreads);
        server.createContext("/v1/",exchange->{
            try {
                WIRES.incrementAndGet();byte[] body=exchange.getRequestBody().readNBytes(OpenAiReviewClient.MAX_REQUEST_BYTES+1);
                if(body.length>OpenAiReviewClient.MAX_REQUEST_BYTES) { throw new IOException("Fixture body bound"); }
                Path capture=fixture.resolve("capture-"+UUID.randomUUID()+".json");Files.write(capture,body,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(capture,PosixFilePermissions.fromString("rw-------"));CAPTURES.add(capture);
                Handler handler=HANDLER.get();if(handler==null) { normal(exchange,body); } else { handler.handle(exchange,body); }
            } catch(Exception failure) { if(failure instanceof InterruptedException) { Thread.currentThread().interrupt(); } }
            finally { exchange.close(); }
        });server.start();
        properties.add("changeo.moderation-preparation-origin",()->"http://127.0.0.1:"+server.getAddress().getPort());
        properties.add("changeo.moderation-preparation-credential",credential::toString);
    }
    private static void normal(HttpExchange exchange,byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type","application/json");
        byte[] response=exchange.getRequestURI().getPath().equals("/v1/moderations")?OpenAiReviewClientTest.moderation():OpenAiReviewClientTest.response();
        exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
    }
    @AfterAll static void shutdownFixture() throws Exception {
        if(server!=null) { server.stop(0); }
        if(fakeThreads!=null) { fakeThreads.shutdownNow();assertThat(fakeThreads.awaitTermination(3,TimeUnit.SECONDS)).isTrue(); }
    }
    @BeforeEach void setup() throws Exception {
        HANDLER.set(null);WIRES.set(0);CAPTURES.clear();reset(proof,delivery,policy);independent.initialize();
        when(proof.actor()).thenAnswer(invocation->Optional.ofNullable(actor.get()));
        when(proof.mfa(any(),anyLong())).thenReturn(true);when(proof.freshPassword(any(),anyLong())).thenReturn(true);
        when(delivery.deliver(any(),anyString(),anyString())).thenAnswer(invocation->{tokens.put(invocation.getArgument(0)+":"+invocation.getArgument(1),invocation.getArgument(2));return true;});
        String contact="preparation-operator-"+UUID.randomUUID()+"@example.test";
        operators.provision(contact,Set.of("IDENTITY_ADMIN","RESTRICTION_ADMIN","MODERATION_REVIEWER"),fixture.resolve("operator-"+UUID.randomUUID()));
        administrator=accounts.credentials(contact).orElseThrow().id();
    }
    @AfterEach void clearActor() { actor.remove();HANDLER.set(null); }
    private UUID verified(int age) {
        UUID id=accounts.register("preparation-"+UUID.randomUUID()+"@example.test","Synthetic-password-12345").accountId().value();
        accounts.verifyContact(id,tokens.get(id+":CONTACT"));admin.evidence(administrator,id,"AGE",age);return id;
    }
    private <T> T tx(java.util.function.Supplier<T> action) { return new TransactionTemplate(transactions).execute(status->action.get()); }
    private ContentReference save(UUID author,String audience,List<WorkbenchService.Upload> uploads) {
        clock.advance(1);actor.set(author);var ref=workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.empty(),0,"harmless complete marketplace fixture","GENERAL",audience,Optional.empty(),Optional.empty(),uploads));workbench.submit(ref);return ref;
    }
    private ContentReference save() { return save(verified(30),"PUBLIC",List.of(new WorkbenchService.Upload("text/plain","whole harmless fixture".getBytes(StandardCharsets.UTF_8)))); }
    private void assertNoAuthority(ContentReference reference) {
        var submission=store.submission(reference,false);
        assertThat(submission.decision()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from moderation_decision where submission_id=?",Long.class,submission.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_task where submission_id=? and kind='ACTIVATE'",Long.class,submission.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?",Long.class,submission.id())).isZero();
        assertThat(jdbc.queryForObject("select approved_revision from workbench_resource where id=?",UUID.class,reference.resourceId())).isNull();
    }
    private ClaimBinding claim(ContentReference reference) {
        var next=tx(()->store.claim("REVIEW",90)).orElseThrow();
        assertThat(next.submission()).isEqualTo(store.submission(reference,false).id());
        var input=store.input(store.submission(reference,false));
        return new ClaimBinding(next,new ReviewFileAccess.Request(reference,next.id(),next.token(),next.generation(),input.attachments()));
    }
    record ClaimBinding(ModerationWorker.Claim claim,ReviewFileAccess.Request request) {}

    @ParameterizedTest @ValueSource(strings={"PUBLIC","PRIVATE"})
    void completesBothStagesWithoutDomainDecisionOrActivation(String audience) throws Exception {
        var ref=save(verified(30),audience,List.of(new WorkbenchService.Upload("text/plain","complete benign text".getBytes(StandardCharsets.UTF_8))));
        assertThat(synthetic.claim()).isEmpty();
        assertThat(worker.processOne()).isTrue();assertThat(WIRES.get()).isEqualTo(2);
        assertThat(store.submission(ref,false).state()).isEqualTo("MANUAL_REVIEW");assertThat(store.submission(ref,false).reason()).isEqualTo("PREPARATION_ONLY");
        assertNoAuthority(ref);
        var submission=store.submission(ref,false);
        assertThat(jdbc.queryForObject("select count(*) from moderation_preparation_dispatch where submission_id=? and source='LOCAL_PROTOCOL_FIXTURE' and endpoint_kind='LITERAL_LOOPBACK'",Long.class,submission.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from moderation_preparation_observation o join moderation_preparation_dispatch d on d.id=o.dispatch_id where d.submission_id=? and o.outcome='VALID'",Long.class,submission.id())).isEqualTo(2);
        assertThat(worker.processOne()).isFalse();
        for(Path capture:CAPTURES) { var body=OpenAiReviewClientTest.JSON.readTree(Files.readAllBytes(capture));assertThat(body.has("tools")).isFalse();assertThat(body.has("metadata")).isFalse(); }
    }

    @Test void usesIndependentCanonicalOwnerWithoutWorkbenchRows() {
        UUID author=verified(30);actor.set(author);
        var ref=new ContentReference(SurfaceKind.PROFILE_BIO,UUID.randomUUID(),UUID.randomUUID());
        var context=new AuthorContext(new ActorId(author),new SubjectId(author),Capability.NEW_REQUEST);
        tx(()->{identity.requireForUpdate(context.permission());var file=files.intake(UUID.randomUUID(),context,ref,"text/plain","whole independent file".getBytes(StandardCharsets.UTF_8));independent.save(context,ref,"independent complete fixture",file);return moderation.submitForReview(context,ref,"independent complete fixture","GENERAL",List.of(file));});
        assertThat(worker.processOne()).isTrue();assertThat(WIRES.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from workbench_resource where id=?",Long.class,ref.resourceId())).isZero();
        assertThat(store.submission(ref,false).decision()).isNull();
        assertThat(jdbc.queryForObject("select approved from fixture_canonical where id=?",UUID.class,ref.resourceId())).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"application/pdf","model/stl","application/octet-stream"})
    void quarantinesWholeAffectedAttemptWithZeroSends(String media) {
        var ref=save(verified(30),"PUBLIC",List.of(new WorkbenchService.Upload("text/plain","benign text".getBytes(StandardCharsets.UTF_8)),new WorkbenchService.Upload(media,"harmless uninspectable placeholder".getBytes(StandardCharsets.UTF_8))));
        assertThat(worker.processOne()).isTrue();assertThat(WIRES.get()).isZero();assertNoAuthority(ref);
        assertThat(store.submission(ref,false).reason()).isEqualTo("PREPARATION_UNINSPECTABLE");
    }

    @Test void rejectsOuterTransactionAndMaterialCopyIsDefensive() {
        var ref=save();var binding=claim(ref);
        assertThatThrownBy(()->tx(()->reviewFiles.materialize(binding.request()))).isInstanceOf(ModerationFailure.class);
        var material=reviewFiles.materialize(binding.request());byte[] original=material.files().getFirst().bytes();byte[] altered=material.files().getFirst().bytes();altered[0]^=1;
        assertThat(Arrays.equals(material.files().getFirst().bytes(),original)).isTrue();
        verify(policy,times(1)).requireCurrentReview(binding.request());
        tx(()->{store.complete(binding.claim(),"FIXTURE_CLOSED");return null;});
    }

    @ParameterizedTest @ValueSource(strings={"expired","stolen","generation","recalled","edit","restriction"})
    void deniesCurrentClaimOrOwnerChangeBeforeBytes(String change) {
        var ref=save();var binding=claim(ref);UUID author=store.submission(ref,false).author().actor().value();
        switch(change) {
            case "expired"->jdbc.update("update moderation_task set lease_until=? where id=?",Timestamp.from(clock.instant().minusSeconds(1)),binding.claim().id());
            case "stolen"->jdbc.update("update moderation_task set claim_token=? where id=?",UUID.randomUUID(),binding.claim().id());
            case "generation"->jdbc.update("update moderation_submission set generation=generation+1 where id=?",binding.claim().submission());
            case "recalled"->jdbc.update("update moderation_submission set recalled=true where id=?",binding.claim().submission());
            case "edit"->workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.of(ref.resourceId()),tx(()->canonical.resource(ref.resourceId(),false).version()),"new harmless revision","GENERAL","PUBLIC",Optional.empty(),Optional.empty(),List.of()));
            case "restriction"->admin.restriction(administrator,author,true);
            default->throw new AssertionError();
        }
        assertThatThrownBy(()->reviewFiles.materialize(binding.request())).isInstanceOfAny(ModerationFailure.class,IdentityFailure.class);
        assertThat(WIRES.get()).isZero();assertNoAuthority(ref);
        jdbc.update("update moderation_task set state='COMPLETE',reason='FIXTURE_CLOSED',claim_token=null,lease_until=null where id=?",binding.claim().id());
    }

    @Test void currentRestrictionAfterMaterializationDeniesFirstDispatch() throws Exception {
        var ref=save();UUID author=store.submission(ref,false).author().actor().value();
        AtomicInteger authorizations=new AtomicInteger();
        doAnswer(invocation->{if(authorizations.incrementAndGet()==3) { var mutation=new TransactionTemplate(transactions);mutation.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);mutation.executeWithoutResult(status->admin.restriction(administrator,author,true)); }return invocation.callRealMethod();}).when(policy).authorize(any());
        assertThat(worker.processOne()).isTrue();assertThat(WIRES.get()).isZero();assertNoAuthority(ref);
        assertThat(store.submission(ref,false).reason()).isEqualTo("PREPARATION_ELIGIBILITY");
    }

    @ParameterizedTest @ValueSource(strings={"restriction","edit","lease","replacement","recall"})
    void recordsLateTruthAndCannotDispatchSecondStageAfterChange(String change) throws Exception {
        var ref=save();var original=store.submission(ref,false);CountDownLatch sent=new CountDownLatch(1),release=new CountDownLatch(1);
        HANDLER.set((exchange,body)->{sent.countDown();if(!release.await(5,TimeUnit.SECONDS)) { throw new IOException(); }normal(exchange,body);});
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var running=threads.submit(worker::processOne);
            try {
                assertThat(sent.await(5,TimeUnit.SECONDS)).isTrue();
                switch(change) {
                    case "restriction"->admin.restriction(administrator,original.author().actor().value(),true);
                    case "edit"->workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.of(ref.resourceId()),tx(()->canonical.resource(ref.resourceId(),false).version()),"changed during fake I/O","GENERAL","PUBLIC",Optional.empty(),Optional.empty(),List.of()));
                    case "lease"->jdbc.update("update moderation_task set lease_until=? where submission_id=?",Timestamp.from(clock.instant().minusSeconds(1)),original.id());
                    case "replacement"->jdbc.update("update moderation_task set claim_token=?,reason='FIXTURE_REPLACED' where submission_id=?",UUID.randomUUID(),original.id());
                    case "recall"->jdbc.update("update moderation_submission set recalled=true,generation=generation+1,reason='FIXTURE_RECALLED' where id=?",original.id());
                    default->throw new AssertionError();
                }
            } finally { release.countDown(); }
            assertThat(running.get(8,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(WIRES.get()).isEqualTo(1);assertNoAuthority(ref);
        assertThat(jdbc.queryForObject("select count(*) from moderation_preparation_observation o join moderation_preparation_dispatch d on d.id=o.dispatch_id where d.submission_id=? and disposition='HISTORICAL_ONLY'",Long.class,original.id())).isEqualTo(1);
        if(change.equals("replacement")) { assertThat(jdbc.queryForObject("select reason from moderation_task where submission_id=?",String.class,original.id())).isEqualTo("FIXTURE_REPLACED"); }
        jdbc.update("update moderation_task set state='COMPLETE',reason='FIXTURE_CLOSED',claim_token=null,lease_until=null where submission_id=?",original.id());
    }

    @Test void appendOnlyDispatchAndObservationCannotBeChanged() {
        var ref=save();assertThat(worker.processOne()).isTrue();
        UUID submission=store.submission(ref,false).id();UUID dispatch=jdbc.queryForObject("select id from moderation_preparation_dispatch where submission_id=? and wire_kind='MODERATIONS'",UUID.class,submission);
        assertThatThrownBy(()->jdbc.update("update moderation_preparation_dispatch set source='OTHER' where id=?",dispatch)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from moderation_preparation_observation where dispatch_id=?",dispatch)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertNoAuthority(ref);
    }

    @Test void performsFreshMigrationValidationAndRepeatZero() {
        flyway.validate();assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"corrupt","missing","foreign"})
    void deniesWholeAttemptForUnboundOrUnavailableMaterial(String change) throws Exception {
        var ref=save();var input=store.input(store.submission(ref,false));var file=input.attachments().getFirst();
        Path path=Path.of(".runtime/preparation-artifacts").resolve(file.id().toString());
        if(change.equals("foreign")) {
            var binding=claim(ref);var foreign=new FileReference(UUID.randomUUID(),ref,file.digest());
            assertThatThrownBy(()->reviewFiles.materialize(new ReviewFileAccess.Request(ref,binding.claim().id(),binding.claim().token(),binding.claim().generation(),List.of(foreign)))).isInstanceOf(ModerationFailure.class);
            tx(()->{store.complete(binding.claim(),"FIXTURE_CLOSED");return null;});
        } else {
            Path original=fixture.resolve("preserved-file-"+file.id());Files.copy(path,original);Files.setPosixFilePermissions(original,PosixFilePermissions.fromString("rw-------"));
            if(change.equals("missing")) { Files.move(path,fixture.resolve("moved-file-"+file.id())); }
            else { byte[] altered=Files.readAllBytes(path);altered[0]^=1;Files.write(path,altered); }
            assertThat(worker.processOne()).isTrue();
        }
        assertThat(WIRES.get()).isZero();assertNoAuthority(ref);
    }

    @ParameterizedTest @ValueSource(strings={"restriction-read-first","restriction-mutation-first","guardian-read-first","guardian-mutation-first"})
    void currentIdentityProtectionSpansWholeMaterializationInBothOrders(String scenario) throws Exception {
        UUID author=verified(30);UUID minor=scenario.startsWith("guardian")?verified(15):null;UUID link;
        if(minor!=null) {
            admin.evidence(administrator,minor,"MINOR_DRAFT",null);link=guardians.request(minor,author,minor);admin.reviewGuardian(administrator,link,0,true);
        } else { link=null; }
        actor.set(author);
        var ref=workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.empty(),0,"whole protected fixture","GENERAL",minor==null?"PUBLIC":"PRIVATE",Optional.ofNullable(minor),Optional.empty(),List.of(new WorkbenchService.Upload("text/plain","complete protected bytes".getBytes(StandardCharsets.UTF_8)))));
        workbench.submit(ref);var binding=claim(ref);
        Runnable mutation=minor==null?()->admin.restriction(administrator,author,true):()->guardians.revoke(author,link,guardians.own(author).stream().filter(row->row.id().equals(link)).findFirst().orElseThrow().version());
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            if(scenario.endsWith("read-first")) {
                doAnswer(invocation->{invocation.callRealMethod();held.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return null;}).when(policy).requireCurrentReview(binding.request());
                var read=threads.submit(()->reviewFiles.materialize(binding.request()));
                Future<?> changed=null;
                try { assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();changed=threads.submit(()->{started.countDown();mutation.run();});assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();Future<?> pending=changed;assertThatThrownBy(()->pending.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally { release.countDown(); }
                assertThat(read.get(5,TimeUnit.SECONDS).files()).hasSize(1);changed.get(5,TimeUnit.SECONDS);
            } else {
                var changed=threads.submit(()->tx(()->{mutation.run();entityManager.flush();held.countDown();try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}return null;}));
                Future<?> read=null;
                try { assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();read=threads.submit(()->{started.countDown();return reviewFiles.materialize(binding.request());});assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();Future<?> pending=read;assertThatThrownBy(()->pending.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally { release.countDown(); }
                changed.get(5,TimeUnit.SECONDS);Future<?> denied=read;assertThatThrownBy(()->denied.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IdentityFailure.class);
            }
        }
        assertThatThrownBy(()->reviewFiles.materialize(binding.request())).isInstanceOf(IdentityFailure.class);
        tx(()->{store.complete(binding.claim(),"FIXTURE_CLOSED");return null;});assertThat(WIRES.get()).isZero();assertNoAuthority(ref);
    }

    @Test void twoPipelinePermitsPrecedeClaimsAndRecoverAfterMaximumBodies() throws Exception {
        List<ContentReference> references=new ArrayList<>();
        byte[] maximum="\\".repeat(1048576).getBytes(StandardCharsets.UTF_8);
        for(int n=0;n<2;n++) {
            clock.advance(1);UUID author=verified(30);actor.set(author);List<WorkbenchService.Upload> uploads=new ArrayList<>();for(int f=0;f<4;f++) { uploads.add(new WorkbenchService.Upload("text/plain",maximum)); }
            var ref=workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.empty(),0,"\u0001".repeat(32768),"GENERAL","PUBLIC",Optional.empty(),Optional.empty(),uploads));workbench.submit(ref);references.add(ref);
        }
        var waiting=save();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var maximumBody=new AtomicInteger();
        HANDLER.set((exchange,body)->{maximumBody.accumulateAndGet(body.length,Math::max);if(exchange.getRequestURI().getPath().equals("/v1/moderations")) { entered.countDown();if(!release.await(8,TimeUnit.SECONDS)) { throw new IOException(); } }normal(exchange,body);});
        try(var threads=Executors.newFixedThreadPool(2)) {
            var one=threads.submit(worker::processOne);var two=threads.submit(worker::processOne);
            try {
                assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();assertThat(worker.processOne()).isFalse();
                assertThat(jdbc.queryForObject("select attempts from moderation_task where submission_id=?",Integer.class,store.submission(waiting,false).id())).isZero();
            } finally { release.countDown(); }
            assertThat(one.get(10,TimeUnit.SECONDS)).isTrue();assertThat(two.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(WIRES.get()).isEqualTo(4);assertThat(maximumBody.get()).isLessThanOrEqualTo(OpenAiReviewClient.PROVED_BODY_BYTES);
        for(var reference:references) { assertNoAuthority(reference);assertThat(store.submission(reference,false).reason()).isEqualTo("PREPARATION_ONLY"); }
        HANDLER.set(null);assertThat(worker.processOne()).isTrue();assertNoAuthority(waiting);assertThat(WIRES.get()).isEqualTo(6);
    }

    @Test void limitsTransientUnknownsToThreeLogicalAttemptsWithoutImmediateRetry() throws Exception {
        var ref=save();
        HANDLER.set((exchange,body)->{exchange.getResponseHeaders().set("Content-Type","application/json");byte[] response="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,response.length);exchange.getResponseBody().write(response);});
        for(int n=1;n<=3;n++) {
            assertThat(worker.processOne()).isTrue();assertThat(WIRES.get()).isEqualTo(n);
            assertThat(worker.processOne()).isFalse();clock.advance(16);
        }
        assertThat(store.submission(ref,false).state()).isEqualTo("FAILED");assertThat(worker.processOne()).isFalse();assertNoAuthority(ref);
        assertThat(jdbc.queryForObject("select count(*) from moderation_preparation_dispatch where submission_id=?",Long.class,store.submission(ref,false).id())).isEqualTo(3);
    }

    @Test void populatedV11UpgradePreservesAllOldRowsColumnsConstraintsAndPayloads() throws Exception {
        String schema="prep_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        var old=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("11").load();assertThat(old.migrate().migrationsExecuted).isEqualTo(11);
        try(var connection=dataSource.getConnection()) {
            connection.setSchema(schema);var upgrade=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            UUID author=UUID.randomUUID(),resource=UUID.randomUUID(),revision=UUID.randomUUID(),file=UUID.randomUUID(),reference=UUID.randomUUID(),snapshot=UUID.randomUUID(),decision=UUID.randomUUID(),token=UUID.randomUUID();
            Timestamp now=Timestamp.from(clock.instant());byte[] bytes="complete migration fixture bytes".getBytes(StandardCharsets.UTF_8);String digest=ContentDigest.bytes(bytes);
            Path artifact=fixture.resolve("upgrade-artifact-"+file);Files.write(artifact,bytes,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(artifact,PosixFilePermissions.fromString("rw-------"));
            upgrade.update("insert into identity_account(id,contact,password_hash,contact_verified) values(?,?,?,true)",author,"upgrade-"+author+"@example.test",passwords.encode("Synthetic-password-12345"));
            upgrade.update("insert into files_artifact values(?,?,?,?,?,?,?,?,?,?,?,?)",file,UUID.randomUUID(),author,resource,revision,"REQUEST_BODY",digest,"text/plain",bytes.length,true,"DOUBT",now);
            upgrade.update("insert into files_blob_binding values(?,'R2',?,?,?)",file,"a".repeat(64),"private-blobs/"+file,"fixture-etag");
            upgrade.update("insert into identity_contact_token values(?,?,?, ?,?,?,false)",token,author,"CONTACT","b".repeat(64),1,Timestamp.from(clock.instant().plusSeconds(3600)));
            upgrade.update("insert into identity_token_delivery(id,token_id,account_id,purpose,generation,security_epoch,expires_at,state,created_at,reason) values(?,?,?,'CONTACT',1,0,?,'PENDING',?,'FIXTURE_QUEUED')",UUID.randomUUID(),token,author,Timestamp.from(clock.instant().plusSeconds(3600)),now);
            upgrade.update("insert into moderation_reference values(?,'REQUEST_BODY',?)",reference,resource);
            upgrade.update("insert into moderation_snapshot(id,reference_id,revision_id,actor_id,subject_id,capability,text,declared_label,digest,created_at) values(?,?,?,?,?,'NEW_REQUEST','synthetic:clear','GENERAL',?,?)",snapshot,reference,revision,author,author,ContentDigest.payload("synthetic:clear","GENERAL",List.of()),now);
            upgrade.update("insert into moderation_submission(id,state,label,reason) values(?,'PENDING','ADULT','QUEUED')",snapshot);
            upgrade.update("insert into moderation_decision values(?,?,null,'APPROVE','GENERAL','CLEAR','synthetic-v1','local-fixture-v1','LOCAL_SYNTHETIC',?)",decision,snapshot,now);
            upgrade.update("update moderation_submission set state='APPROVED',label='GENERAL',decision_id=? where id=?",decision,snapshot);
            var tables=upgrade.queryForList("select table_name from information_schema.tables where table_schema=? and table_type='BASE TABLE' and table_name<>'flyway_schema_history' order by table_name",String.class,schema);
            Map<String,String> before=new TreeMap<>();for(String table:tables) { before.put(table,tableDigest(upgrade,schema,table)); }
            var columns=upgrade.queryForList("select table_name,column_name,ordinal_position,data_type,is_nullable,column_default from information_schema.columns where table_schema=? order by table_name,ordinal_position",schema);
            var checks=upgrade.queryForList("select c.conname,pg_get_constraintdef(c.oid) as definition from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname=? order by c.conname",schema);
            var checksums=upgrade.queryForList("select version,checksum from flyway_schema_history where version is not null order by installed_rank");
            var current=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();assertThat(current.migrate().migrationsExecuted).isEqualTo(1);current.validate();assertThat(current.migrate().migrationsExecuted).isZero();
            for(String table:tables) { assertThat(tableDigest(upgrade,schema,table)).isEqualTo(before.get(table)); }
            assertThat(upgrade.queryForList("select table_name,column_name,ordinal_position,data_type,is_nullable,column_default from information_schema.columns where table_schema=? and table_name not like 'moderation_preparation_%' order by table_name,ordinal_position",schema)).isEqualTo(columns);
            assertThat(upgrade.queryForList("select c.conname,pg_get_constraintdef(c.oid) as definition from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname=? and c.conrelid not in (select oid from pg_class where relname like 'moderation_preparation_%' and relnamespace=n.oid) order by c.conname",schema)).isEqualTo(checks);
            assertThat(upgrade.queryForList("select version,checksum from flyway_schema_history where version is not null and version<>'12' order by installed_rank")).isEqualTo(checksums);
            assertThat(ContentDigest.bytes(Files.readAllBytes(artifact))).isEqualTo(digest);
            Files.writeString(fixture.resolve("populated-upgrade-result.json"),OpenAiReviewClient.json(Map.of("oldTables",tables.size(),"preservedColumns",columns.size(),"preservedConstraints",checks.size(),"preservedMigrationChecksums",checksums.size(),"artifactHash",digest,"repeatMigrations",0)),StandardOpenOption.CREATE_NEW);
        }
    }
    private static String tableDigest(JdbcTemplate jdbc,String schema,String table) {
        var rows=jdbc.queryForList("select row_to_json(t)::text from "+schema+"."+table+" t order by row_to_json(t)::text",String.class);
        return ContentDigest.bytes(String.join("\n",rows).getBytes(StandardCharsets.UTF_8));
    }

    @Test void preservesOfflineCredentialAndWholeBoundMaterialFixtureForRestore() throws Exception {
        String contact="restore-"+UUID.randomUUID()+"@example.test";
        UUID author=accounts.register(contact,"Synthetic-password-12345").accountId().value();accounts.verifyContact(author,tokens.get(author+":CONTACT"));admin.evidence(administrator,author,"AGE",30);
        var ref=save(author,"PUBLIC",List.of(new WorkbenchService.Upload("text/plain","complete retained restore bytes \u00a0🙂".getBytes(StandardCharsets.UTF_8))));
        var submission=store.submission(ref,false);var input=store.input(submission);
        jdbc.update("update moderation_task set available_at=? where submission_id=?",Timestamp.from(clock.instant().plusSeconds(3600)),submission.id());
        Map<String,Object> binding=Map.of("contact",contact,"author",author.toString(),"resource",ref.resourceId().toString(),"revision",ref.revisionId().toString(),"contentDigest",input.digest(),"fileDigest",input.attachments().getFirst().digest(),"clock",clock.instant().toString(),"schema",schema);
        Path record=fixture.resolve("restore-binding.json");Files.writeString(record,OpenAiReviewClient.json(binding),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(record,PosixFilePermissions.fromString("rw-------"));
        assertNoAuthority(ref);
    }

    @Test @Tag("restore") void restoresActualFrameworkAuthenticationAndCurrentBoundBytes() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("moderation_preparation_restore_qa");
        Path root=Path.of(".runtime/external-moderation-preparation-20261020");List<Path> bindings;
        try(var paths=Files.walk(root)) { bindings=paths.filter(path->path.getFileName().toString().equals("restore-binding.json")).sorted(Comparator.comparingLong((Path path)->path.toFile().lastModified()).reversed()).toList(); }
        assertThat(bindings.isEmpty()).isFalse();var binding=OpenAiReviewClientTest.JSON.readTree(Files.readString(bindings.getFirst()));
        clock.set(Instant.parse(binding.path("clock").stringValue()));
        var provider=new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);provider.setPasswordEncoder(passwords);
        var authenticated=provider.authenticate(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(binding.path("contact").stringValue(),"Synthetic-password-12345"));
        assertThat(authenticated.isAuthenticated()).isTrue();var principal=(ar.changeo.security.AccountPrincipal)authenticated.getPrincipal();
        UUID author=UUID.fromString(binding.path("author").stringValue());assertThat(principal.id()).isEqualTo(author);actor.set(principal.id());
        var ref=new ContentReference(SurfaceKind.REQUEST_BODY,UUID.fromString(binding.path("resource").stringValue()),UUID.fromString(binding.path("revision").stringValue()));
        var submission=store.submission(ref,false);assertThat(submission.digest()).isEqualTo(binding.path("contentDigest").stringValue());
        jdbc.update("update moderation_task set available_at=? where submission_id=?",Timestamp.from(clock.instant()),submission.id());
        var claimed=claim(ref);var material=reviewFiles.materialize(claimed.request());
        assertThat(ContentDigest.bytes(material.files().getFirst().bytes())).isEqualTo(binding.path("fileDigest").stringValue());
        assertThatThrownBy(()->files.read(material.files().getFirst().reference(),FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        var foreign=new FileReference(UUID.randomUUID(),ref,binding.path("fileDigest").stringValue());
        assertThatThrownBy(()->reviewFiles.materialize(new ReviewFileAccess.Request(ref,claimed.claim().id(),claimed.claim().token(),claimed.claim().generation(),List.of(foreign)))).isInstanceOf(ModerationFailure.class);
        workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.of(ref.resourceId()),tx(()->canonical.resource(ref.resourceId(),false).version()),"restored current revision changes","GENERAL","PUBLIC",Optional.empty(),Optional.empty(),List.of()));
        assertThatThrownBy(()->reviewFiles.materialize(claimed.request())).isInstanceOf(ModerationFailure.class);
        assertThat(WIRES.get()).isZero();assertNoAuthority(ref);
        tx(()->{store.barrier(claimed.claim().barrier());store.submission(claimed.claim().submission(),true);if(store.live(claimed.claim())) { store.complete(claimed.claim(),"FIXTURE_CLOSED"); }return null;});
        Path result=fixture.resolve("restore-result.json");Files.writeString(result,OpenAiReviewClient.json(Map.of("frameworkAuthentication",true,"matchingMfaKeyStartup",true,"completeBoundBytes",true,"ordinaryPendingDenied",true,"foreignBindingDenied",true,"staleBindingDenied",true,"wireRequests",0)),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(result,PosixFilePermissions.fromString("rw-------"));
    }
}
