package ar.changeo.moderation;

import ar.changeo.ChangeoApplication;
import ar.changeo.files.api.*;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.SessionProof;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes={ChangeoApplication.class,WorkbenchDraftRecoveryPostgresTest.Configuration.class},properties={"spring.flyway.schemas=workbench_recovery_pg_test","spring.flyway.default-schema=workbench_recovery_pg_test","spring.jpa.properties.hibernate.default_schema=workbench_recovery_pg_test","spring.datasource.hikari.schema=workbench_recovery_pg_test","changeo.synthetic-evidence=true","changeo.synthetic-moderation=true","changeo.moderation-worker=false","changeo.notification-worker=false","changeo.synthetic-payments=false","changeo.synthetic-subscriptions=false","changeo.mfa-key=.runtime/workbench-pg-mfa.key","changeo.files-root=.runtime/workbench-pg-artifacts"})
class WorkbenchDraftRecoveryPostgresTest {
    @Autowired AccountService accounts;
    @Autowired AdminService admin;
    @Autowired OperatorService operators;
    @Autowired GuardianService guardians;
    @Autowired WorkbenchService workbench;
    @MockitoSpyBean WorkbenchStore canonical;
    @Autowired ContentReader reader;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ModerationPostgresTest.TestClock clock;
    @MockitoBean SessionProof proof;
    @MockitoBean TokenDelivery delivery;
    private final Map<String,String> tokens=new ConcurrentHashMap<>();
    private final ThreadLocal<UUID> actor=new ThreadLocal<>();
    private UUID administrator;
    @TestConfiguration static class Configuration {
        @Bean @Primary ModerationPostgresTest.TestClock testClock() { return new ModerationPostgresTest.TestClock(); }
    }
    @BeforeEach void setup() throws Exception {
        clock.advance(61);reset(proof,delivery,canonical);tokens.clear();
        when(proof.actor()).thenAnswer(invocation->Optional.ofNullable(actor.get()));
        when(proof.mfa(any(),anyLong())).thenReturn(true);when(proof.freshPassword(any(),anyLong())).thenReturn(true);
        when(delivery.deliver(any(),anyString(),anyString())).thenAnswer(invocation->{tokens.put(invocation.getArgument(0)+":"+invocation.getArgument(1),invocation.getArgument(2));return true;});
        Path enrollment=Path.of(".runtime/workbench-pg-operator-"+UUID.randomUUID());
        String contact="workbenchpg"+UUID.randomUUID()+"@example.test";
        operators.provision(contact,Set.of("IDENTITY_ADMIN","RESTRICTION_ADMIN"),enrollment);
        administrator=accounts.credentials(contact).orElseThrow().id();
    }
    @AfterEach void cleanup() { actor.remove(); }
    private UUID adult() { return account(30); }
    private UUID account(int age) {
        UUID id=accounts.register("workbenchpg"+UUID.randomUUID()+"@example.test","Synthetic-password-12345").accountId().value();
        accounts.verifyContact(id,tokens.get(id+":CONTACT"));admin.evidence(administrator,id,"AGE",age);return id;
    }
    private WorkbenchService.Draft draft(UUID command,UUID resource,long version,String audience,List<WorkbenchService.Upload> uploads) {
        return new WorkbenchService.Draft(command,Optional.ofNullable(resource),version,"synthetic:clear","GENERAL",audience,Optional.empty(),Optional.empty(),uploads);
    }
    private ContentReference save(String audience) { return workbench.save(draft(UUID.randomUUID(),null,0,audience,List.of())); }
    private long version(UUID resource) { return jdbc.queryForObject("select version from workbench_resource where id=?",Long.class,resource); }
    private Map<String,List<String>> snapshot() throws Exception {
        var result=new TreeMap<String,List<String>>();
        for(String table:jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'workbench_%' or tablename like 'moderation_%' or tablename like 'files_%') order by tablename",String.class)) {
            assertThat(table).matches("[a-z_]+");result.put(table,jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class));
        }
        var paths=new TreeMap<String,String>();Path root=Path.of(".runtime/workbench-pg-artifacts");
        if(Files.exists(root)) { try(var files=Files.list(root)) { for(var path:files.toList()) { paths.put(path.getFileName().toString(),ContentDigest.bytes(Files.readAllBytes(path))); } } }
        result.put("artifactBytes",paths.entrySet().stream().map(Object::toString).toList());return result;
    }
    @Test void shouldRequireOriginalPublicPrivateCapabilitiesWithoutReadApprovalOrEffects() throws Exception {
        actor.set(adult());var before=snapshot();
        workbench.requireDraftAccess(Optional.empty(),0,"PUBLIC",Optional.empty());
        workbench.requireDraftAccess(Optional.empty(),0,"PRIVATE",Optional.empty());
        assertThat(snapshot()).isEqualTo(before);
        for(String audience:List.of("PUBLIC","PRIVATE")) {
            var ref=save(audience);before=snapshot();
            assertThat(reader.own(ref.resourceId()).revisions().getFirst().text()).isNull();
            workbench.requireDraftAccess(Optional.of(ref.resourceId()),version(ref.resourceId()),audience,Optional.of(UUID.randomUUID()));
            assertThat(snapshot()).isEqualTo(before);
        }
    }
    @Test void shouldDenyForeignSubjectOnlyStaleAudienceMissingAndRestrictedContextsWithoutEffects() throws Exception {
        UUID author=adult(),stranger=adult();actor.set(author);var ref=save("PRIVATE");long version=version(ref.resourceId());
        var before=snapshot();actor.set(stranger);
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PRIVATE",Optional.empty())).isInstanceOf(ModerationFailure.class);
        actor.set(author);
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version-1,"PRIVATE",Optional.empty())).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PUBLIC",Optional.empty())).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(UUID.randomUUID()),0,"PRIVATE",Optional.empty())).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.empty(),0,"PUBLIC",Optional.of(stranger))).isInstanceOf(IdentityFailure.class);
        assertThat(snapshot()).isEqualTo(before);admin.restriction(administrator,author,true);before=snapshot();
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PRIVATE",Optional.empty())).isInstanceOf(IdentityFailure.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void shouldPreserveImmutableGuardianAuthorAndDenyMinorAndRevokedAuthority() throws Exception {
        UUID guardian=adult(),minor=account(15);admin.evidence(administrator,minor,"MINOR_DRAFT",null);
        UUID link=guardians.request(minor,guardian,minor);admin.reviewGuardian(administrator,link,0,true);actor.set(guardian);
        workbench.requireDraftAccess(Optional.empty(),0,"PRIVATE",Optional.of(minor));
        var input=new WorkbenchService.Draft(UUID.randomUUID(),Optional.empty(),0,"synthetic:clear","GENERAL","PRIVATE",Optional.of(minor),Optional.empty(),List.of());
        var ref=workbench.save(input);long version=version(ref.resourceId());var before=snapshot();actor.set(minor);
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PRIVATE",Optional.empty())).isInstanceOf(ModerationFailure.class);
        actor.set(guardian);workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PRIVATE",Optional.of(UUID.randomUUID()));assertThat(snapshot()).isEqualTo(before);
        long linkVersion=guardians.own(guardian).stream().filter(row->row.id().equals(link)).findFirst().orElseThrow().version();guardians.revoke(guardian,link,linkVersion);before=snapshot();
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PRIVATE",Optional.empty())).isInstanceOf(IdentityFailure.class);
        assertThat(snapshot()).isEqualTo(before);
        UUID successor=adult();UUID newLink=guardians.request(minor,successor,minor);admin.reviewGuardian(administrator,newLink,0,true);actor.set(successor);before=snapshot();
        assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PRIVATE",Optional.of(minor))).isInstanceOf(ModerationFailure.class);
        actor.set(guardian);assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PRIVATE",Optional.of(minor))).isInstanceOf(IdentityFailure.class);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void shouldRejectOuterTransactionAndNotRunCanonicalRecoveryUnderIt() throws Exception {
        actor.set(adult());var ref=save("PUBLIC");var before=snapshot();long version=version(ref.resourceId());
        new TransactionTemplate(transactions).executeWithoutResult(status->assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PUBLIC",Optional.empty())).isInstanceOf(ModerationFailure.class));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void shouldReplaySameSuccessfulOldVersionAndUploadBeforeVersionCheckWithoutMoreEffects() throws Exception {
        actor.set(adult());var first=save("PRIVATE");long version=version(first.resourceId());
        var upload=new WorkbenchService.Upload("text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8));
        var edit=new WorkbenchService.Draft(UUID.randomUUID(),Optional.of(first.resourceId()),version,"new exact text","GENERAL","PRIVATE",Optional.of(UUID.randomUUID()),Optional.of(UUID.randomUUID()),List.of(upload));
        var saved=workbench.save(edit);var before=snapshot();assertThat(workbench.save(edit)).isEqualTo(saved);assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(()->workbench.requireDraftAccess(edit.resource(),version,"PRIVATE",Optional.empty())).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->workbench.save(draft(UUID.randomUUID(),first.resourceId(),version,"PRIVATE",List.of(upload)))).isInstanceOf(ModerationFailure.class);
        var conflict=new WorkbenchService.Draft(edit.command(),edit.resource(),edit.version(),"changed","GENERAL",edit.audience(),edit.subject(),edit.participant(),edit.uploads());
        assertThatThrownBy(()->workbench.save(conflict)).isInstanceOf(ModerationFailure.class);assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from workbench_member where resource_id=?",Long.class,first.resourceId())).isZero();
    }
    @Test void shouldKeepFileInspectionRollbackAndQuarantineWithNoRecoveryEffects() throws Exception {
        actor.set(adult());var before=snapshot();
        for(var upload:List.of(new WorkbenchService.Upload("image/png",new byte[]{1,2,3}),new WorkbenchService.Upload("text/html","<html>active</html>".getBytes(StandardCharsets.UTF_8)),new WorkbenchService.Upload("text/plain",new byte[]{(byte)0xff}))) {
            assertThatThrownBy(()->workbench.save(draft(UUID.randomUUID(),null,0,"PUBLIC",List.of(upload)))).isInstanceOf(ModerationFailure.class);assertThat(snapshot()).isEqualTo(before);
        }
        assertThatThrownBy(()->new WorkbenchService.Upload("text/plain",new byte[1048577])).isInstanceOf(ModerationFailure.class);
        for(String type:List.of("application/pdf","model/stl")) {
            var ref=workbench.save(draft(UUID.randomUUID(),null,0,"PUBLIC",List.of(new WorkbenchService.Upload(type,"unsupported opaque bytes".getBytes(StandardCharsets.UTF_8)))));
            assertThat(jdbc.queryForObject("select safe from files_artifact where revision_id=?",Boolean.class,ref.revisionId())).isFalse();
            before=snapshot();workbench.requireDraftAccess(Optional.of(ref.resourceId()),version(ref.resourceId()),"PUBLIC",Optional.empty());assertThat(snapshot()).isEqualTo(before);
        }
    }
    @Test void shouldHoldCurrentIdentityThroughCanonicalObservationAndRecheckNextCall() throws Exception {
        UUID author=adult();actor.set(author);var ref=save("PUBLIC");long version=version(ref.resourceId());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var restrictionStarted=new CountDownLatch(1);
        doAnswer(invocation->{Object result=invocation.callRealMethod();locked.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return result;}).when(canonical).resource(ref.resourceId(),true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var recovery=pool.submit(()->{actor.set(author);try { workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PUBLIC",Optional.empty()); } finally { actor.remove(); } });
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{restrictionStarted.countDown();admin.restriction(administrator,author,true);});
            assertThat(restrictionStarted.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->revoke.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();recovery.get(5,TimeUnit.SECONDS);revoke.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        reset(canonical);var before=snapshot();assertThatThrownBy(()->workbench.requireDraftAccess(Optional.of(ref.resourceId()),version,"PUBLIC",Optional.empty())).isInstanceOf(IdentityFailure.class);assertThat(snapshot()).isEqualTo(before);
    }
}
