package ar.changeo.moderation;

import ar.changeo.ChangeoApplication;
import ar.changeo.files.api.*;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.SessionProof;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes={ChangeoApplication.class,ModerationPostgresTest.Configuration.class},properties={"spring.flyway.schemas=moderation_test","spring.flyway.default-schema=moderation_test","spring.jpa.properties.hibernate.default_schema=moderation_test","spring.datasource.hikari.schema=moderation_test","changeo.synthetic-evidence=true","changeo.synthetic-moderation=true","changeo.moderation-worker=false","changeo.mailbox=.runtime/moderation-test-mailbox","changeo.mfa-key=.runtime/moderation-test-mfa.key","changeo.files-root=.runtime/moderation-test-artifacts"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ModerationPostgresTest {
    @Autowired AccountService accounts;
    @Autowired AdminService admin;
    @Autowired OperatorService operators;
    @Autowired GuardianService guardians;
    @Autowired IdentityAccess identity;
    @Autowired WorkbenchService workbench;
    @Autowired WorkbenchStore canonical;
    @Autowired ModerationService moderation;
    @Autowired ModerationStore store;
    @Autowired ModerationWorker worker;
    @Autowired ContentReader reader;
    @Autowired CaseService cases;
    @Autowired FileAccess files;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestClock clock;
    @Autowired AbuseLimits limits;
    @Autowired IndependentOwner independent;
    @Autowired Admission admission;
    @Autowired OwnerRegistry ownerRegistry;
    @Autowired ReviewEngine reviewEngine;
    @MockitoBean SessionProof proof;
    @MockitoBean TokenDelivery delivery;
    @MockitoSpyBean AttachmentPolicyRouter filePolicy;
    @MockitoSpyBean WorkbenchOwner owner;
    @MockitoSpyBean DecisionService decisions;
    private final Map<String,String> tokens=new ConcurrentHashMap<>();
    private final ThreadLocal<UUID> actor=new ThreadLocal<>();
    private Set<Path> previousFiles;
    private UUID administrator;
    @TestConfiguration static class Configuration {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
        @Bean IndependentOwner independentOwner(JdbcTemplate jdbc,IdentityAccess identity,SessionProof proof,ModerationStore store) { return new IndependentOwner(jdbc,identity,proof,store); }
    }
    static class TestClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-01T12:00:00Z"));
        void advance(long seconds) { now.updateAndGet(value->value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    @BeforeEach void setup() throws Exception {
        clock.advance(61);independent.initialize();jdbc.update("delete from moderation_task");tokens.clear();reset(proof,delivery);reset(filePolicy,owner,decisions);
        when(proof.actor()).thenAnswer(invocation->Optional.ofNullable(actor.get()));
        when(proof.mfa(any(),anyLong())).thenReturn(true);when(proof.freshPassword(any(),anyLong())).thenReturn(true);
        when(delivery.deliver(any(),anyString(),anyString())).thenAnswer(invocation->{ tokens.put(invocation.getArgument(0)+":"+invocation.getArgument(1),invocation.getArgument(2));return true; });
        administrator=provision(Set.of("IDENTITY_ADMIN","RESTRICTION_ADMIN","MODERATION_REVIEWER","MODERATION_AUDITOR"));
        previousFiles=artifactFiles();
    }
    @AfterEach void cleanup() throws Exception { actor.remove();for(Path path:artifactFiles()) { if(!previousFiles.contains(path)) { Files.deleteIfExists(path); } } }
    private Set<Path> artifactFiles() throws Exception { Path root=Path.of(".runtime/moderation-test-artifacts");if(!Files.exists(root)) { return Set.of(); }try(var paths=Files.list(root)) { return new HashSet<>(paths.toList()); } }
    private UUID provision(Set<String> roles) throws Exception {
        String contact="m1operator"+UUID.randomUUID()+"@example.test";Path enrollment=Path.of(".runtime/m1-enrollment-"+UUID.randomUUID());
        try { operators.provision(contact,roles,enrollment);return accounts.credentials(contact).orElseThrow().id(); } finally { Files.deleteIfExists(enrollment); }
    }
    private UUID verified(int age) {
        UUID id=accounts.register("m1"+UUID.randomUUID()+"@example.test","Synthetic-password-12345").accountId().value();accounts.verifyContact(id,tokens.get(id+":CONTACT"));admin.evidence(administrator,id,"AGE",age);return id;
    }
    private <T> T tx(java.util.function.Supplier<T> action) { return new TransactionTemplate(transactions).execute(status->action.get()); }
    private void txRun(Runnable action) { tx(()->{action.run();return null;}); }
    private WorkbenchService.Draft draft(UUID command,Optional<UUID> resource,long version,String text,String label,String audience,List<WorkbenchService.Upload> uploads) { return new WorkbenchService.Draft(command,resource,version,text,label,audience,Optional.empty(),Optional.empty(),uploads); }
    private ContentReference save(String text) { return workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,text,"GENERAL","PUBLIC",List.of())); }
    private void submit(ContentReference ref) { workbench.submit(ref); }
    private void runReview(ContentReference ref) { worker.process(claim(ref,"REVIEW")); }
    private void activate(ContentReference ref) { worker.process(claim(ref,"ACTIVATE")); }
    private ModerationWorker.Claim claim(ContentReference ref,String kind) {
        var task=tx(()->{ var s=store.submission(ref,false);return jdbc.queryForObject("select id from moderation_task where submission_id=? and kind=? and generation=?",UUID.class,s.id(),kind,s.generation()); });
        return tx(()->{
            UUID token=UUID.randomUUID();jdbc.update("update moderation_task set state='CLAIMED',claim_token=?,lease_until=?,attempts=attempts+1 where id=?",token,java.sql.Timestamp.from(clock.instant().plusSeconds(30)),task);
            var s=store.submission(ref,false);return new ModerationWorker.Claim(task,s.barrier(),s.id(),kind,s.generation(),token,kind.equals("ACTIVATE")?s.decision():null,false);
        });
    }
    private long count(String table,UUID id) { return jdbc.queryForObject("select count(*) from "+table+" where id=?",Long.class,id); }
    @Test void shouldKeepCommittedUnsubmittedDraftPrivateAndReplayWithoutNewArtifactsOrTasks() {
        UUID author=verified(30);actor.set(author);UUID command=UUID.randomUUID();var input=draft(command,Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of(new WorkbenchService.Upload("text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8))));
        var ref=workbench.save(input);assertThat(workbench.save(input)).isEqualTo(ref);
        assertThat(reader.own(ref.resourceId()).revisions().getFirst().state()).isEqualTo("DRAFT_PENDING_REVIEW");
        assertThatThrownBy(()->reader.retrieve(ref.resourceId())).isInstanceOf(ModerationFailure.class);
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,ref.revisionId())).isZero();
        assertThatThrownBy(()->workbench.save(draft(command,Optional.empty(),0,"changed","GENERAL","PUBLIC",List.of()))).isInstanceOf(ModerationFailure.class);
        submit(ref);submit(ref);assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,ref.revisionId())).isEqualTo(1);
        runReview(ref);assertThatThrownBy(()->reader.retrieve(ref.resourceId())).isInstanceOf(ModerationFailure.class);
        activate(ref);assertThat(reader.retrieve(ref.resourceId()).text()).isEqualTo("synthetic:clear");
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=(select id from moderation_snapshot where revision_id=?)",Long.class,ref.revisionId())).isEqualTo(1);
    }
    @Test void shouldRetainExactApprovedRevisionAcrossPendingEditStaleResultsAndRecall() {
        actor.set(verified(30));var first=save("synthetic:clear");submit(first);runReview(first);activate(first);
        long version=reader.own(first.resourceId()).version();var second=workbench.save(draft(UUID.randomUUID(),Optional.of(first.resourceId()),version,"synthetic:tattoo","GENERAL","PUBLIC",List.of()));submit(second);
        var delayed=claim(second,"REVIEW");long nextVersion=reader.own(first.resourceId()).version();var third=workbench.save(draft(UUID.randomUUID(),Optional.of(first.resourceId()),nextVersion,"synthetic:doubt","GENERAL","PUBLIC",List.of()));submit(third);
        worker.process(delayed);activate(second);assertThat(reader.retrieve(first.resourceId()).reference()).isEqualTo(first);
        actor.set(administrator);UUID caseId=cases.reviewCase(UUID.randomUUID(),first);cases.act(UUID.randomUUID(),caseId,0,"RECALL","GENERAL","SAFETY");
        assertThatThrownBy(()->reader.retrieve(first.resourceId())).isInstanceOf(ModerationFailure.class);
        assertThat(moderation.approval(first).state()).isEqualTo("RECALLED");
    }
    @Test void shouldRollbackCaughtBindingFailureAfterSuccessfulIdentityAndRejectAbsentTransaction() {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:clear");var context=new AuthorContext(new ActorId(author),new SubjectId(author),Capability.NEW_REQUEST);
        assertThatThrownBy(()->moderation.submitForReview(context,ref,"synthetic:clear","GENERAL",List.of())).isInstanceOf(ModerationFailure.class);
        jdbc.execute("create table if not exists moderation_test_intent(id uuid primary key)");UUID intent=UUID.randomUUID();
        assertThatThrownBy(()->txRun(()->{
            jdbc.update("insert into moderation_test_intent values(?)",intent);
            try { moderation.submitForReview(context,ref,"altered","GENERAL",List.of()); }catch(ModerationFailure expected) { }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(count("moderation_test_intent",intent)).isZero();
        var weaker=new AuthorContext(new ActorId(author),new SubjectId(author),Capability.DRAFT_REQUEST);
        assertThat(identity.decide(weaker.permission()).allowed()).isTrue();
        assertThatThrownBy(()->tx(()->moderation.submitForReview(weaker,ref,"synthetic:clear","GENERAL",List.of()))).isInstanceOf(ModerationFailure.class);
        var unsupported=new ContentReference(SurfaceKind.OFFER_BODY,ref.resourceId(),ref.revisionId());
        assertThatThrownBy(()->tx(()->moderation.submitForReview(context,unsupported,"synthetic:clear","GENERAL",List.of()))).isInstanceOf(ModerationFailure.class);
    }
    @Test void shouldRecoverDurableApprovalActivationAfterNewWorkerAndAvoidDuplicateEffects() {
        actor.set(verified(30));var ref=save("synthetic:clear");submit(ref);runReview(ref);
        assertThat(moderation.approval(ref).state()).isEqualTo("APPROVED");assertThatThrownBy(()->reader.retrieve(ref.resourceId())).isInstanceOf(ModerationFailure.class);
        var claim=claim(ref,"ACTIVATE");clock.advance(31);worker.process(claim);assertThatThrownBy(()->reader.retrieve(ref.resourceId())).isInstanceOf(ModerationFailure.class);
        var reclaimed=claim(ref,"ACTIVATE");worker.process(reclaimed);worker.process(reclaimed);
        assertThat(reader.retrieve(ref.resourceId()).reference()).isEqualTo(ref);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?",Long.class,reclaimed.submission())).isEqualTo(1);
        assertThatThrownBy(()->txRun(()->jdbc.update("update moderation_snapshot set text='changed' where id=?",reclaimed.submission()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void shouldPreserveApprovalWhenActivationEligibilityDeniesAndAllowOneDeliberateRetryGeneration() {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:clear");submit(ref);runReview(ref);admin.restriction(administrator,author,true);activate(ref);
        assertThat(moderation.approval(ref).state()).isEqualTo("AWAITING_ELIGIBILITY");assertThat(reader.own(ref.resourceId()).revisions()).hasSize(1);
        assertThatThrownBy(()->moderation.retry(UUID.randomUUID(),ref,moderation.generation(ref))).isInstanceOf(IdentityFailure.class);
        admin.restriction(administrator,author,false);UUID command=UUID.randomUUID();long generation=moderation.generation(ref);moderation.retry(command,ref,generation);moderation.retry(command,ref,generation);
        assertThat(moderation.generation(ref)).isEqualTo(generation+1);activate(ref);assertThat(reader.retrieve(ref.resourceId()).reference()).isEqualTo(ref);
    }
    @Test void shouldEnforceByteBindingQuarantineCorruptionAndOwnedReadTransaction() {
        UUID author=verified(30);actor.set(author);var ref=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of(new WorkbenchService.Upload("text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8)))));submit(ref);runReview(ref);activate(ref);
        var file=reader.retrieve(ref.resourceId()).attachments().getFirst();assertThat(files.read(file,FileAccess.Purpose.ORDINARY).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->tx(()->files.read(file,FileAccess.Purpose.ORDINARY))).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->files.read(new FileReference(file.id(),file.content(),"0".repeat(64)),FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->files.read(new FileReference(file.id(),new ContentReference(SurfaceKind.OFFER_BODY,ref.resourceId(),ref.revisionId()),file.digest()),FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        try { Files.write(Path.of(".runtime/moderation-test-artifacts",file.id().toString()),"changed".getBytes(StandardCharsets.UTF_8)); }catch(java.io.IOException failure) { throw new AssertionError(failure); }
        assertThatThrownBy(()->files.read(file,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        var quarantined=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of(new WorkbenchService.Upload("application/pdf",new byte[]{1,2,3}))));submit(quarantined);runReview(quarantined);assertThat(moderation.approval(quarantined).reason()).isEqualTo("UNINSPECTABLE");
        actor.set(administrator);var unsafe=reader.evidence(quarantined).attachments().getFirst();assertThatThrownBy(()->files.read(unsafe,FileAccess.Purpose.REVIEW)).isInstanceOf(ModerationFailure.class);
        UUID review=cases.reviewCase(UUID.randomUUID(),quarantined);assertThatThrownBy(()->cases.act(UUID.randomUUID(),review,0,"APPROVE","GENERAL","SAFETY")).isInstanceOf(ModerationFailure.class);
    }
    @Test void shouldUseIndependentCanonicalOwnerForSnapshotActivationRecallAndFilesWithoutWorkbenchRows() {
        UUID author=verified(30);actor.set(author);var context=new AuthorContext(new ActorId(author),new SubjectId(author),Capability.NEW_REQUEST);
        var ref=new ContentReference(SurfaceKind.PROFILE_BIO,UUID.randomUUID(),UUID.randomUUID());
        var file=tx(()->{
            identity.requireForUpdate(context.permission());var stored=files.intake(UUID.randomUUID(),context,ref,"text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8));independent.save(context,ref,"synthetic:clear",stored);return stored;
        });
        tx(()->moderation.submitForReview(context,ref,"synthetic:clear","GENERAL",List.of(file)));runReview(ref);activate(ref);
        assertThat(count("workbench_resource",ref.resourceId())).isZero();assertThat(count("workbench_revision",ref.revisionId())).isZero();
        assertThat(tx(()->independent.read(ref))).isEqualTo("synthetic:clear");assertThat(files.read(file,FileAccess.Purpose.ORDINARY).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        actor.set(administrator);UUID caseId=cases.reviewCase(UUID.randomUUID(),ref);cases.act(UUID.randomUUID(),caseId,0,"RECALL","GENERAL","SAFETY");
        assertThatThrownBy(()->tx(()->independent.read(ref))).isInstanceOf(ModerationFailure.class);assertThatThrownBy(()->files.read(file,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThat(moderation.approval(ref).state()).isEqualTo("RECALLED");assertThat(count("workbench_resource",ref.resourceId())).isZero();
    }
    @Test void shouldClaimConcurrentlyRecoverLeaseRejectStaleTokenAndBoundOutageRetries() throws Exception {
        UUID author=verified(30);actor.set(author);var first=save("synthetic:outage");submit(first);var second=save("synthetic:clear");submit(second);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);
            Callable<ModerationWorker.Claim> take=()->{start.await(5,TimeUnit.SECONDS);return worker.claim().orElseThrow();};
            var a=pool.submit(take);var b=pool.submit(take);var claimA=a.get(5,TimeUnit.SECONDS);var claimB=b.get(5,TimeUnit.SECONDS);
            assertThat(claimA.id()).isNotEqualTo(claimB.id());
            clock.advance(31);var reclaimed=worker.claim().orElseThrow();assertThat(Set.of(claimA.id(),claimB.id())).contains(reclaimed.id());
            worker.process(claimA);worker.process(claimB);assertThat(jdbc.queryForObject("select count(*) from moderation_decision where submission_id in (?,?)",Long.class,claimA.submission(),claimB.submission())).isZero();
            worker.process(reclaimed);
        }
        for(int index=0;index<12;index++) { clock.advance(31);worker.processOne(); }
        assertThat(moderation.approval(first).state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select attempts from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?) and kind='REVIEW'",Integer.class,first.revisionId())).isEqualTo(3);
        long generation=moderation.generation(first);submit(first);assertThat(moderation.generation(first)).isEqualTo(generation);
        UUID command=UUID.randomUUID();moderation.retry(command,first,generation);moderation.retry(command,first,generation);assertThat(moderation.generation(first)).isEqualTo(generation+1);
    }
    @Test void shouldCreateActivationForManualAppealAndLabelCorrectionAndKeepWarningsReversible() {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:doubt");submit(ref);runReview(ref);assertThat(moderation.approval(ref).state()).isEqualTo("MANUAL_REVIEW");
        UUID appeal=cases.open(UUID.randomUUID(),ref,"APPEAL","FALSE_POSITIVE","");actor.set(administrator);cases.act(UUID.randomUUID(),appeal,0,"TAKE","GENERAL","FALSE_POSITIVE");cases.act(UUID.randomUUID(),appeal,1,"APPROVE","GENERAL","FALSE_POSITIVE");activate(ref);
        assertThat(reader.retrieve(ref.resourceId()).text()).isEqualTo("synthetic:doubt");
        UUID warningCase=cases.reviewCase(UUID.randomUUID(),ref);cases.act(UUID.randomUUID(),warningCase,0,"WARNING","GENERAL","MISLABEL");
        UUID evidence=jdbc.queryForObject("select id from moderation_evidence where submission_id=(select id from moderation_snapshot where revision_id=?)",UUID.class,ref.revisionId());
        UUID withdrawal=UUID.randomUUID();cases.withdraw(withdrawal,evidence,0);cases.withdraw(withdrawal,evidence,0);assertThat(jdbc.queryForObject("select withdrawn from moderation_evidence where id=?",Boolean.class,evidence)).isTrue();
        cases.act(UUID.randomUUID(),warningCase,1,"CORRECT_LABEL","SENSITIVE","MISLABEL");activate(ref);assertThat(moderation.approval(ref).label()).isEqualTo("SENSITIVE");
        accounts.preferences(administrator,true,false);assertThat(reader.retrieve(ref.resourceId()).label()).isEqualTo("SENSITIVE");
        actor.set(author);var manual=save("arbitrary human-reviewed fixture");submit(manual);runReview(manual);actor.set(administrator);UUID manualCase=cases.reviewCase(UUID.randomUUID(),manual);cases.act(UUID.randomUUID(),manualCase,0,"APPROVE","GENERAL","SAFETY");activate(manual);
        assertThat(reader.retrieve(manual.resourceId()).text()).isEqualTo("arbitrary human-reviewed fixture");
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=(select id from moderation_snapshot where revision_id=?)",Long.class,ref.revisionId())).isEqualTo(2);
    }
    @Test void shouldEnforceOperatorProofLeastPrivilegeCurrentRoleAndIndependentApproval() throws Exception {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:doubt");submit(ref);runReview(ref);
        when(proof.mfa(any(),anyLong())).thenReturn(false);actor.set(administrator);assertThatThrownBy(()->cases.reviewQueue()).isInstanceOf(IdentityFailure.class);
        when(proof.mfa(any(),anyLong())).thenReturn(true);when(proof.freshPassword(any(),anyLong())).thenReturn(false);assertThatThrownBy(()->cases.reviewQueue()).isInstanceOf(IdentityFailure.class);assertThat(cases.queue(true)).isNotNull();
        when(proof.freshPassword(any(),anyLong())).thenReturn(true);UUID auditor=provision(Set.of("MODERATION_AUDITOR"));actor.set(auditor);assertThat(cases.queue(true)).isNotNull();assertThatThrownBy(()->reader.evidence(ref)).isInstanceOf(IdentityFailure.class);
        UUID reviewer=provision(Set.of("MODERATION_REVIEWER"));actor.set(reviewer);assertThatThrownBy(()->cases.queue(true)).isInstanceOf(IdentityFailure.class);assertThat(reader.evidence(ref).text()).isEqualTo("synthetic:doubt");
        actor.set(administrator);admin.removeRole(administrator,reviewer,"MODERATION_REVIEWER");actor.set(reviewer);assertThatThrownBy(()->reader.evidence(ref)).isInstanceOf(IdentityFailure.class);
        UUID selfReviewer=provision(Set.of("MODERATION_REVIEWER"));accounts.resend(selfReviewer);accounts.verifyContact(selfReviewer,tokens.get(selfReviewer+":CONTACT"));admin.evidence(administrator,selfReviewer,"AGE",30);actor.set(selfReviewer);var own=save("synthetic:doubt");submit(own);runReview(own);UUID ownCase=cases.reviewCase(UUID.randomUUID(),own);assertThatThrownBy(()->cases.act(UUID.randomUUID(),ownCase,0,"APPROVE","GENERAL","SAFETY")).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->cases.act(UUID.randomUUID(),ownCase,99,"HOLD","GENERAL","SAFETY")).isInstanceOf(ModerationFailure.class);
    }
    @Test void shouldHideAdultUnknownPendingAndPrivateContentUntilCurrentAudienceAllows() {
        UUID author=verified(30);actor.set(author);accounts.preferences(author,true,true);
        var adult=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:adult","ADULT","PUBLIC",List.of()));submit(adult);runReview(adult);activate(adult);assertThat(reader.retrieve(adult.resourceId()).label()).isEqualTo("ADULT");
        actor.remove();assertThatThrownBy(()->reader.retrieve(adult.resourceId())).isInstanceOf(ModerationFailure.class);
        UUID unknown=accounts.register("unknown"+UUID.randomUUID()+"@example.test","Synthetic-password-12345").accountId().value();actor.set(unknown);assertThatThrownBy(()->accounts.preferences(unknown,true,true)).isInstanceOf(IdentityFailure.class);assertThatThrownBy(()->reader.retrieve(adult.resourceId())).isInstanceOf(IdentityFailure.class);
        UUID minor=verified(16);actor.set(minor);assertThatThrownBy(()->accounts.preferences(minor,true,true)).isInstanceOf(IdentityFailure.class);assertThatThrownBy(()->reader.retrieve(adult.resourceId())).isInstanceOf(IdentityFailure.class);
        actor.set(author);var pending=save("unclassified private pending fixture");assertThat(reader.own(pending.resourceId()).revisions().getFirst().text()).isNotNull();accounts.preferences(author,true,false);assertThat(reader.own(pending.resourceId()).revisions().getFirst().text()).isNull();
        var privateRef=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PRIVATE",List.of()));submit(privateRef);runReview(privateRef);activate(privateRef);
        assertThat(reader.retrieve(privateRef.resourceId()).text()).isEqualTo("synthetic:clear");actor.set(verified(30));assertThatThrownBy(()->reader.retrieve(privateRef.resourceId())).isInstanceOf(ModerationFailure.class);
        actor.set(author);admin.restriction(administrator,author,true);assertThat(reader.own(pending.resourceId()).revisions()).isNotEmpty();assertThatThrownBy(()->reader.retrieve(privateRef.resourceId())).isInstanceOf(IdentityFailure.class);
    }
    @Test void shouldBoundActorCommandsPreserveReplayAndRecoverAfterWindow() {
        actor.set(verified(30));UUID command=UUID.randomUUID();var original=draft(command,Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of());var ref=workbench.save(original);
        for(int index=0;index<9;index++) { save("synthetic:clear"); }
        assertThat(workbench.save(original)).isEqualTo(ref);assertThatThrownBy(()->save("synthetic:clear")).isInstanceOf(ModerationFailure.class);assertThat(reader.own(ref.resourceId()).revisions()).hasSize(1);
        clock.advance(61);assertThat(save("synthetic:clear")).isNotNull();
        for(int index=0;index<4096;index++) { limits.allow("m1-cap:"+UUID.randomUUID(),1); }
        assertThatThrownBy(()->admission.source("new-source", "mutation")).isInstanceOf(ModerationFailure.class);clock.advance(61);admission.source("new-source","mutation");
    }
    @Test void shouldSerializeFileCopyBeforeRecallAndDenyAfterRecallCommit() throws Exception {
        UUID author=verified(30);actor.set(author);var ref=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of(new WorkbenchService.Upload("text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8)))));submit(ref);runReview(ref);activate(ref);
        var file=reader.retrieve(ref.resourceId()).attachments().getFirst();actor.set(administrator);UUID caseId=cases.reviewCase(UUID.randomUUID(),ref);
        var readLocked=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);var recallEntered=new CountDownLatch(1);
        doAnswer(invocation->{invocation.callRealMethod();readLocked.countDown();assertThat(releaseRead.await(5,TimeUnit.SECONDS)).isTrue();return null;}).when(filePolicy).requireCurrentAccess(eq(file),eq(FileAccess.Purpose.ORDINARY));
        doAnswer(invocation->{recallEntered.countDown();return invocation.callRealMethod();}).when(owner).lockRevision(any(),eq(ref));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var read=pool.submit(()->{actor.set(author);try{return files.read(file,FileAccess.Purpose.ORDINARY);}finally{actor.remove();}});assertThat(readLocked.await(5,TimeUnit.SECONDS)).isTrue();
            var recall=pool.submit(()->{actor.set(administrator);try{cases.act(UUID.randomUUID(),caseId,0,"RECALL","GENERAL","SAFETY");}finally{actor.remove();}});assertThat(recallEntered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->recall.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);releaseRead.countDown();assertThat(read.get(5,TimeUnit.SECONDS).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));recall.get(5,TimeUnit.SECONDS);
        } finally { releaseRead.countDown(); }
        verify(filePolicy,times(1)).requireCurrentAccess(file,FileAccess.Purpose.ORDINARY);reset(filePolicy);assertThatThrownBy(()->files.read(file,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldKeepIdentityGuardThroughSubmissionCommitAndDenyAfterWinningRestriction() throws Exception {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:clear");
        var guarded=new CountDownLatch(1);var release=new CountDownLatch(1);var restrictionStarted=new CountDownLatch(1);
        doAnswer(invocation->{var result=invocation.callRealMethod();guarded.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return result;}).when(owner).lockRevision(any(),eq(ref));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var submitting=pool.submit(()->{actor.set(author);try{submit(ref);}finally{actor.remove();}});assertThat(guarded.await(5,TimeUnit.SECONDS)).isTrue();
            var restricting=pool.submit(()->{actor.set(administrator);restrictionStarted.countDown();try{admin.restriction(administrator,author,true);}finally{actor.remove();}});assertThat(restrictionStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->restricting.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);release.countDown();submitting.get(5,TimeUnit.SECONDS);restricting.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
        reset(owner);assertThat(moderation.approval(ref).state()).isEqualTo("PENDING");runReview(ref);activate(ref);assertThat(moderation.approval(ref).state()).isEqualTo("AWAITING_ELIGIBILITY");
        assertThatThrownBy(()->submit(ref)).isInstanceOf(IdentityFailure.class);assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=(select id from moderation_snapshot where revision_id=?)",Long.class,ref.revisionId())).isZero();
        admin.restriction(administrator,author,false);var later=save("synthetic:clear");admin.restriction(administrator,author,true);
        assertThatThrownBy(()->submit(later)).isInstanceOf(IdentityFailure.class);assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,later.revisionId())).isZero();
    }
    @Test void shouldPreserveGuardianMetadataButDenySubmissionAndActivationAfterRevocation() {
        UUID guardian=verified(30),minor=verified(15);admin.evidence(administrator,minor,"MINOR_DRAFT",null);UUID link=guardians.request(minor,guardian,minor);admin.reviewGuardian(administrator,link,0,true);
        actor.set(guardian);accounts.preferences(guardian,true,true);
        var draft=new WorkbenchService.Draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PRIVATE",Optional.of(minor),Optional.empty(),List.of());
        var ref=workbench.save(draft);assertThat(reader.own(ref.resourceId()).revisions().getFirst().text()).isNull();submit(ref);runReview(ref);
        long version=guardians.own(guardian).stream().filter(row->row.id().equals(link)).findFirst().orElseThrow().version();guardians.revoke(guardian,link,version);activate(ref);
        assertThat(moderation.approval(ref).state()).isEqualTo("AWAITING_ELIGIBILITY");assertThatThrownBy(()->submit(ref)).isInstanceOf(IdentityFailure.class);assertThat(reader.own(ref.resourceId()).revisions()).hasSize(1);
        actor.set(minor);assertThat(reader.own(ref.resourceId()).revisions().getFirst().text()).isEqualTo("synthetic:clear");assertThatThrownBy(()->reader.retrieve(ref.resourceId())).isInstanceOf(ModerationFailure.class);
        assertThat(cases.open(UUID.randomUUID(),ref,"APPEAL","FALSE_POSITIVE","")).isNotNull();
    }
    @Test void shouldDenyReadWaitingBehindCommittedRecall() throws Exception {
        UUID author=verified(30);actor.set(author);var ref=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of(new WorkbenchService.Upload("text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8)))));submit(ref);runReview(ref);activate(ref);
        var file=reader.retrieve(ref.resourceId()).attachments().getFirst();actor.set(administrator);UUID caseId=cases.reviewCase(UUID.randomUUID(),ref);
        var recalled=new CountDownLatch(1);var release=new CountDownLatch(1);var readStarted=new CountDownLatch(1);
        doAnswer(invocation->{invocation.callRealMethod();recalled.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return null;}).when(owner).recall(eq(ref));
        doAnswer(invocation->{readStarted.countDown();return invocation.callRealMethod();}).when(filePolicy).requireCurrentAccess(eq(file),eq(FileAccess.Purpose.ORDINARY));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var recall=pool.submit(()->{actor.set(administrator);try{cases.act(UUID.randomUUID(),caseId,0,"RECALL","GENERAL","SAFETY");}finally{actor.remove();}});assertThat(recalled.await(5,TimeUnit.SECONDS)).isTrue();
            var read=pool.submit(()->{actor.set(author);try{return files.read(file,FileAccess.Purpose.ORDINARY);}finally{actor.remove();}});assertThat(readStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->read.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);release.countDown();recall.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->read.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ModerationFailure.class);
        } finally {release.countDown();}
    }
    @Test void shouldRollbackCaughtOwnerCallbackAndAdmissionFailuresWithoutPartialEffects() {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:clear");long version=reader.own(ref.resourceId()).version();var context=new AuthorContext(new ActorId(author),new SubjectId(author),Capability.NEW_REQUEST);
        doAnswer(invocation->{invocation.callRealMethod();jdbc.update("update workbench_resource set version=version+1 where id=?",ref.resourceId());throw new ModerationFailure("Synthetic callback denial");}).when(owner).lockRevision(any(),eq(ref));
        assertThatThrownBy(()->txRun(()->{try{moderation.submitForReview(context,ref,"synthetic:clear","GENERAL",List.of());}catch(ModerationFailure expected){}})).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        reset(owner);assertThat(reader.own(ref.resourceId()).version()).isEqualTo(version);assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,ref.revisionId())).isZero();
        for(int index=0;index<10;index++) {admission.actor(author,"review");}
        assertThatThrownBy(()->txRun(()->{try{moderation.submitForReview(context,ref,"synthetic:clear","GENERAL",List.of());}catch(ModerationFailure expected){}})).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,ref.revisionId())).isZero();
    }
    @Test void shouldHoldUnderlabelledAttachmentsAndRejectProhibitedManualAndAutomatedApproval() {
        actor.set(verified(30));var adult=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of(new WorkbenchService.Upload("text/plain","synthetic:adult".getBytes(StandardCharsets.UTF_8)))));submit(adult);runReview(adult);assertThat(moderation.approval(adult).state()).isEqualTo("MANUAL_REVIEW");
        actor.set(administrator);UUID adultCase=cases.reviewCase(UUID.randomUUID(),adult);assertThatThrownBy(()->cases.act(UUID.randomUUID(),adultCase,0,"APPROVE","GENERAL","SAFETY")).isInstanceOf(ModerationFailure.class);
        actor.set(verified(30));var prohibited=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PUBLIC",List.of(new WorkbenchService.Upload("text/plain","synthetic:prohibited".getBytes(StandardCharsets.UTF_8)))));submit(prohibited);runReview(prohibited);assertThat(moderation.approval(prohibited).state()).isEqualTo("REJECTED");
        actor.set(administrator);UUID prohibitedCase=cases.reviewCase(UUID.randomUUID(),prohibited);assertThatThrownBy(()->cases.act(UUID.randomUUID(),prohibitedCase,0,"APPROVE","ADULT","SAFETY")).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldPermitCanonicalEditWhileBackgroundResultHoldsOnlyModerationLocks() throws Exception {
        UUID author=verified(30);actor.set(author);var first=save("synthetic:clear");submit(first);runReview(first);activate(first);
        var second=workbench.save(draft(UUID.randomUUID(),Optional.of(first.resourceId()),reader.own(first.resourceId()).version(),"synthetic:tattoo","GENERAL","PUBLIC",List.of()));submit(second);var review=claim(second,"REVIEW");
        var resultLocked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation->{resultLocked.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return invocation.callRealMethod();}).when(decisions).record(any(),any(),isNull(),isNull());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var result=pool.submit(()->worker.process(review));assertThat(resultLocked.await(5,TimeUnit.SECONDS)).isTrue();
            var edit=pool.submit(()->{actor.set(author);try{return workbench.save(draft(UUID.randomUUID(),Optional.of(first.resourceId()),reader.own(first.resourceId()).version(),"synthetic:doubt","GENERAL","PUBLIC",List.of()));}finally{actor.remove();}});
            var newest=edit.get(5,TimeUnit.SECONDS);assertThat(newest.revisionId()).isNotEqualTo(second.revisionId());release.countDown();result.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
        reset(decisions);activate(second);assertThat(reader.retrieve(first.resourceId()).reference()).isEqualTo(first);
    }
    @Test void shouldSerializeActivationBeforeRestrictionAndGuardianSubmissionBeforeRevocation() throws Exception {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:clear");submit(ref);runReview(ref);var activation=claim(ref,"ACTIVATE");
        var protectedLock=new CountDownLatch(1);var release=new CountDownLatch(1);var restrictionStarted=new CountDownLatch(1);
        doAnswer(invocation->{var result=invocation.callRealMethod();protectedLock.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return result;}).when(owner).lockRevision(any(),eq(ref));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var activate=pool.submit(()->worker.process(activation));assertThat(protectedLock.await(5,TimeUnit.SECONDS)).isTrue();
            var restrict=pool.submit(()->{restrictionStarted.countDown();admin.restriction(administrator,author,true);});assertThat(restrictionStarted.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->restrict.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();activate.get(5,TimeUnit.SECONDS);restrict.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
        reset(owner);assertThat(reader.retrieve(ref.resourceId()).reference()).isEqualTo(ref);
        UUID guardian=verified(30),minor=verified(15);admin.evidence(administrator,minor,"MINOR_DRAFT",null);UUID link=guardians.request(minor,guardian,minor);admin.reviewGuardian(administrator,link,0,true);long linkVersion=guardians.own(guardian).stream().filter(row->row.id().equals(link)).findFirst().orElseThrow().version();
        actor.set(guardian);var guardianRef=workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PRIVATE",Optional.of(minor),Optional.empty(),List.of()));
        var guardLocked=new CountDownLatch(1);var releaseGuardian=new CountDownLatch(1);var revokeStarted=new CountDownLatch(1);
        doAnswer(invocation->{var result=invocation.callRealMethod();guardLocked.countDown();assertThat(releaseGuardian.await(5,TimeUnit.SECONDS)).isTrue();return result;}).when(owner).lockRevision(any(),eq(guardianRef));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var submit=pool.submit(()->{actor.set(guardian);try{submit(guardianRef);}finally{actor.remove();}});assertThat(guardLocked.await(5,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{revokeStarted.countDown();guardians.revoke(guardian,link,linkVersion);});assertThat(revokeStarted.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->revoke.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseGuardian.countDown();submit.get(5,TimeUnit.SECONDS);revoke.get(5,TimeUnit.SECONDS);
        } finally {releaseGuardian.countDown();}
        reset(owner);assertThat(moderation.approval(guardianRef).state()).isEqualTo("PENDING");assertThatThrownBy(()->submit(guardianRef)).isInstanceOf(IdentityFailure.class);
    }
    @Test void shouldRecheckPrivateMembershipAndPreferencesAndConfineFilesToSafePrivatePaths() throws Exception {
        UUID author=verified(30),participant=verified(30);actor.set(author);
        var ref=workbench.save(new WorkbenchService.Draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PRIVATE",Optional.empty(),Optional.of(participant),List.of(new WorkbenchService.Upload("text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8)))));submit(ref);runReview(ref);activate(ref);var file=reader.retrieve(ref.resourceId()).attachments().getFirst();
        actor.set(participant);assertThat(files.read(file,FileAccess.Purpose.ORDINARY).bytes()).isNotEmpty();actor.set(author);workbench.removeParticipant(ref.resourceId(),participant);actor.set(participant);assertThatThrownBy(()->files.read(file,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        actor.set(author);Path artifact=Path.of(".runtime/moderation-test-artifacts",file.id().toString());var original=Files.getPosixFilePermissions(artifact);assertThat(original).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(artifact,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));assertThatThrownBy(()->files.read(file,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);Files.setPosixFilePermissions(artifact,original);
        Path target=Files.createTempFile(Path.of(".runtime"),"m1-safe-target-",".private");try{Files.writeString(target,"synthetic:clear");Files.delete(artifact);Files.createSymbolicLink(artifact,target.toAbsolutePath());assertThatThrownBy(()->files.read(file,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);}finally{Files.deleteIfExists(target);}
        accounts.preferences(author,true,true);var adult=workbench.save(draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:adult","ADULT","PUBLIC",List.of()));submit(adult);runReview(adult);activate(adult);assertThat(reader.retrieve(adult.resourceId()).label()).isEqualTo("ADULT");accounts.preferences(author,true,false);assertThatThrownBy(()->reader.retrieve(adult.resourceId())).isInstanceOf(IdentityFailure.class);
        var privateContext=new AuthorContext(new ActorId(author),new SubjectId(author),Capability.SEND_MARKETPLACE_MESSAGE,Optional.of(new JobContext(UUID.randomUUID(),JobContext.FulfillmentMode.REMOTE)));
        assertThat(identity.decide(privateContext.permission()).allowed()).isTrue();assertThatThrownBy(()->tx(()->moderation.submitForReview(privateContext,ref,"synthetic:clear","GENERAL",List.of(file)))).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldLeaveDurableTasksUntouchedWhenSyntheticWorkerIsDisabled() {
        actor.set(verified(30));var ref=save("synthetic:clear");submit(ref);
        var disabled=new ModerationWorker(store,decisions,identity,ownerRegistry,files,reviewEngine,transactions,clock,false);
        assertThat(disabled.claim()).isEmpty();assertThat(disabled.processOne()).isFalse();assertThat(moderation.approval(ref).state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempts from moderation_task where submission_id=(select id from moderation_snapshot where revision_id=?)",Integer.class,ref.revisionId())).isZero();
    }

    @Test void shouldRecoverApprovedActivationAfterTransientRollbackWithExactlyOnePublication() {
        actor.set(verified(30));var ref=save("synthetic:clear");submit(ref);runReview(ref);
        long version=reader.own(ref.resourceId()).version(),generation=moderation.generation(ref);var first=worker.claim().orElseThrow();
        doAnswer(invocation->{invocation.callRealMethod();throw new org.springframework.dao.TransientDataAccessResourceException("Synthetic activation outage");}).when(owner).activate(eq(ref));
        worker.process(first);
        assertThat(moderation.approval(ref).state()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select state from moderation_task where id=?",String.class,first.id())).isEqualTo("RETRY_WAIT");
        assertThat(canonical.resource(ref.resourceId(),false).approved()).isNull();assertThat(reader.own(ref.resourceId()).version()).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?",Long.class,first.submission())).isZero();
        reset(owner);clock.advance(6);var retry=worker.claim().orElseThrow();assertThat(retry.id()).isEqualTo(first.id());
        var recovered=new ModerationWorker(store,decisions,identity,ownerRegistry,files,reviewEngine,transactions,clock,true);
        recovered.process(retry);recovered.process(retry);
        assertThat(reader.retrieve(ref.resourceId()).reference()).isEqualTo(ref);assertThat(reader.own(ref.resourceId()).version()).isEqualTo(version+1);
        assertThat(moderation.generation(ref)).isEqualTo(generation);assertThat(worker.claim()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?",Long.class,first.submission())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from moderation_decision where submission_id=?",Long.class,first.submission())).isEqualTo(1);
    }
    @Test void shouldExhaustActivationOutagesAndRecoverThroughOneCurrentGuardedRetryGeneration() {
        UUID author=verified(30);actor.set(author);var ref=save("synthetic:clear");submit(ref);runReview(ref);long version=reader.own(ref.resourceId()).version();
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("Synthetic repeated activation outage")).when(owner).activate(eq(ref));
        for(int attempt=0;attempt<3;attempt++) {worker.process(worker.claim().orElseThrow());clock.advance(16);}
        assertThat(worker.claim()).isEmpty();assertThat(moderation.approval(ref).state()).isEqualTo("FAILED");assertThat(moderation.approval(ref).reason()).isEqualTo("OUTAGE");
        var snapshot=store.submission(ref,false);
        assertThat(jdbc.queryForObject("select attempts from moderation_task where submission_id=? and kind='ACTIVATE'",Integer.class,snapshot.id())).isEqualTo(3);
        assertThat(canonical.resource(ref.resourceId(),false).approved()).isNull();assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?",Long.class,snapshot.id())).isZero();
        admin.restriction(administrator,author,true);long generation=moderation.generation(ref);
        assertThatThrownBy(()->moderation.retry(UUID.randomUUID(),ref,generation)).isInstanceOf(IdentityFailure.class);
        admin.restriction(administrator,author,false);UUID command=UUID.randomUUID();moderation.retry(command,ref,generation);moderation.retry(command,ref,generation);
        assertThat(moderation.generation(ref)).isEqualTo(generation+1);reset(owner);var retry=worker.claim().orElseThrow();worker.process(retry);worker.process(retry);
        moderation.retry(command,ref,generation);assertThat(worker.claim()).isEmpty();assertThat(reader.retrieve(ref.resourceId()).reference()).isEqualTo(ref);
        assertThat(reader.own(ref.resourceId()).version()).isEqualTo(version+1);assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?",Long.class,snapshot.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from moderation_decision where submission_id=?",Long.class,snapshot.id())).isEqualTo(1);
    }
    @Test void shouldPersistNonpositiveInvalidDecisionForExactlyBoundContradictoryAdapterOutput() {
        actor.set(verified(30));var ref=save("synthetic:clear");submit(ref);
        ReviewEngine contradictory=input->{var result=new HashMap<>(reviewEngine.review(input));result.put("reason","PROHIBITED");return result;};
        var guardedWorker=new ModerationWorker(store,decisions,identity,ownerRegistry,files,contradictory,transactions,clock,true);
        guardedWorker.process(guardedWorker.claim().orElseThrow());var snapshot=store.submission(ref,false);
        assertThat(moderation.approval(ref).state()).isEqualTo("MANUAL_REVIEW");assertThat(moderation.approval(ref).reason()).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("select count(*) from moderation_decision where submission_id=? and action='APPROVE'",Long.class,snapshot.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_task where submission_id=? and kind='ACTIVATE'",Long.class,snapshot.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where snapshot_id=?",Long.class,snapshot.id())).isZero();assertThat(canonical.resource(ref.resourceId(),false).approved()).isNull();
        assertThatThrownBy(()->reader.retrieve(ref.resourceId())).isInstanceOf(ModerationFailure.class);
    }

}
