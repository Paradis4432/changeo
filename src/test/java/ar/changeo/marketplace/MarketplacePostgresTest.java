package ar.changeo.marketplace;

import ar.changeo.ChangeoApplication;
import ar.changeo.files.api.*;
import ar.changeo.identity.*;
import ar.changeo.identity.api.*;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.listings.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.security.SessionProof;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes={ChangeoApplication.class,MarketplacePostgresTest.Configuration.class},properties={"spring.flyway.schemas=profiles_test","spring.flyway.default-schema=profiles_test","spring.jpa.properties.hibernate.default_schema=profiles_test","spring.datasource.hikari.schema=profiles_test","changeo.synthetic-evidence=true","changeo.synthetic-moderation=true","changeo.moderation-worker=false","changeo.mfa-key=.runtime/profiles-test-mfa.key","changeo.files-root=.runtime/profiles-test-artifacts"})
class MarketplacePostgresTest {
    @Autowired MarketplaceService service;
    @Autowired MarketplaceReader reader;
    @Autowired DiscoveryService discovery;
    @Autowired ListingAccess listings;
    @Autowired ModerationService moderation;
    @Autowired ModerationWorker worker;
    @Autowired CaseService cases;
    @Autowired ContentReader evidence;
    @Autowired IdentityAccess identity;
    @Autowired PublicIdentityBadgeAccess badges;
    @Autowired OperatorService operators;
    @Autowired GuardianService guardians;
    @Autowired FileAccess files;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManager entities;
    @Autowired TestClock clock;
    @MockitoSpyBean MarketplaceOwner owner;
    @MockitoSpyBean MarketplaceStore store;
    @MockitoSpyBean AttachmentAccessPolicy policy;
    @MockitoSpyBean AccountService accounts;
    @MockitoSpyBean AdminService admin;
    @MockitoBean SessionProof proof;
    @MockitoBean TokenDelivery delivery;
    final ThreadLocal<UUID> actor=new ThreadLocal<>();
    final Map<String,String> tokens=new ConcurrentHashMap<>();
    UUID administrator;
    Set<Path> previousFiles;

    @TestConfiguration static class Configuration {
        @Bean @Primary TestClock profilesClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(Instant.parse("2026-10-01T12:00:00Z"));
        void advance(long seconds) { value.updateAndGet(now -> now.plusSeconds(seconds)); }
        @Override public Instant instant() { return value.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @BeforeEach void setup() throws Exception {
        clock.advance(61); jdbc.update("delete from moderation_task");
        reset(proof,delivery,owner,store,policy,accounts,admin); tokens.clear(); actor.remove();
        when(proof.actor()).thenAnswer(invocation -> Optional.ofNullable(actor.get()));
        when(proof.mfa(any(),anyLong())).thenReturn(true); when(proof.freshPassword(any(),anyLong())).thenReturn(true);
        when(delivery.deliver(any(),anyString(),anyString())).thenAnswer(invocation -> { tokens.put(invocation.getArgument(0)+":"+invocation.getArgument(1),invocation.getArgument(2)); return true; });
        String contact="p1operator"+UUID.randomUUID()+"@example.test";
        Path enrollment=Path.of(".runtime/p1-enrollment-"+UUID.randomUUID());
        try { operators.provision(contact,Set.of("IDENTITY_ADMIN","RESTRICTION_ADMIN","MODERATION_REVIEWER","MODERATION_AUDITOR"),enrollment); administrator=accounts.credentials(contact).orElseThrow().id(); }
        finally { Files.deleteIfExists(enrollment); }
        previousFiles=artifactFiles();
    }
    @AfterEach void cleanup() throws Exception {
        actor.remove(); for (Path file:artifactFiles()) { if (!previousFiles.contains(file)) { Files.deleteIfExists(file); } }
    }
    Set<Path> artifactFiles() throws Exception {
        Path root=Path.of(".runtime/profiles-test-artifacts"); if (!Files.exists(root)) { return Set.of(); }
        try (var stream=Files.list(root)) { return Set.copyOf(stream.toList()); }
    }
    UUID verified(int age, boolean provider) {
        UUID id=accounts.register("p1"+UUID.randomUUID()+"@example.test","Synthetic-password-12345").accountId().value();
        accounts.verifyContact(id,tokens.get(id+":CONTACT")); admin.evidence(administrator,id,"AGE",age);
        if (provider) { admin.evidence(administrator,id,"PROVIDER_ELIGIBILITY",null); }
        accounts.preferences(id,true,age>=18); return id;
    }
    <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    <T> T as(UUID id, Supplier<T> action) { actor.set(id); try { return action.get(); } finally { actor.remove(); } }
    MarketplaceDetails details(String title, boolean badge) { return new MarketplaceDetails(title,"Impresión de piezas y modelos personalizados",List.of("impresión","3d"),"Buenos Aires","La Plata",Set.of(FulfillmentMode.LOCAL,FulfillmentMode.REMOTE,FulfillmentMode.SHIPPED),"Lunes a viernes",Optional.empty(),Optional.empty(),badge); }
    ContentReference draft(PresenceType type, MarketplaceDetails details, List<MarketplaceService.Upload> uploads) {
        return service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.empty(),0,type,actor.get(),details,"GENERAL",uploads));
    }
    ContentReference prepare(ContentReference draft, List<MarketplaceService.Upload> uploads) {
        return service.prepare(new MarketplaceService.Preparation(UUID.randomUUID(),draft,reader.own(draft.resourceId()).version(),true,uploads));
    }
    ContentReference published(PresenceType type, MarketplaceDetails details, List<MarketplaceService.Upload> uploads) {
        var draft=draft(type,details,List.of()); var ref=prepare(draft,uploads); service.submit(ref); approve(ref,"GENERAL"); return ref;
    }
    void approve(ContentReference reference, String label) {
        UUID current=actor.get(); actor.set(administrator);
        UUID caseId=cases.reviewCase(UUID.randomUUID(),reference);
        cases.act(UUID.randomUUID(),caseId,0,"APPROVE",label,"SAFETY");
        worker.process(claim(reference,"ACTIVATE")); actor.set(current);
    }
    ModerationWorker.Claim claim(ContentReference reference,String kind) {
        return tx(() -> {
            var snapshot=jdbc.queryForMap("select p.id,p.reference_id,s.generation,s.decision_id from moderation_snapshot p join moderation_submission s on s.id=p.id where p.revision_id=?",reference.revisionId());
            UUID task=jdbc.queryForObject("select id from moderation_task where submission_id=? and kind=? and generation=?",UUID.class,snapshot.get("id"),kind,snapshot.get("generation"));
            UUID token=UUID.randomUUID();
            jdbc.update("update moderation_task set state='CLAIMED',claim_token=?,lease_until=?,attempts=attempts+1 where id=?",token,Timestamp.from(clock.instant().plusSeconds(30)),task);
            return new ModerationWorker.Claim(task,(UUID)snapshot.get("reference_id"),(UUID)snapshot.get("id"),kind,((Number)snapshot.get("generation")).longValue(),token,(UUID)snapshot.get("decision_id"),false);
        });
    }
    MarketplaceService.Upload upload() { return new MarketplaceService.Upload("text/plain","synthetic:clear".getBytes(StandardCharsets.UTF_8)); }
    DiscoveryService.Query query(PresenceType type,String text,int page) { return new DiscoveryService.Query(type,text,"","","",Optional.empty(),page); }

    Optional<ModerationAccess.Approval> lockedStatus(ContentReference reference) {
        var discovered=store.resource(reference.resourceId(),false);
        badges.findCurrent(Set.of(new AccountId(discovered.subject())));
        identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.get()),Capability.ACCESS_SUPPORT));
        var resource=store.resource(reference.resourceId(),true);
        var revision=store.revision(resource,reference.revisionId());
        assertThat(revision.reference()).isEqualTo(reference);
        return moderation.findApprovalForUpdate(reference);
    }

    @Test void shouldCommitAbsentDraftAndPreparedStatusWithoutCreatingModerationRows() {
        actor.set(verified(30,true)); var original=draft(PresenceType.REQUEST,details("Ausencia legítima",false),List.of());
        var prepared=prepare(original,List.of());
        jdbc.execute("create table if not exists profiles_intent(id uuid primary key)");
        for (var reference:List.of(original,prepared)) {
            UUID intent=UUID.randomUUID();
            tx(() -> { assertThat(lockedStatus(reference)).isEmpty(); jdbc.update("insert into profiles_intent values(?)",intent); return null; });
            assertThat(jdbc.queryForObject("select count(*) from profiles_intent where id=?",Long.class,intent)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from moderation_reference where resource_id=?",Long.class,reference.resourceId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,reference.revisionId())).isZero();
        }
        service.submit(prepared);
        var edit=service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.of(prepared.resourceId()),reader.own(prepared.resourceId()).version(),PresenceType.REQUEST,actor.get(),details("Nueva ausencia",false),"GENERAL",List.of()));
        assertThat(tx(() -> lockedStatus(edit))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from moderation_reference where resource_id=?",Long.class,prepared.resourceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where reference_id in (select id from moderation_reference where resource_id=?)",Long.class,prepared.resourceId())).isEqualTo(1);
        assertThatThrownBy(() -> reader.publicView(prepared.resourceId())).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldKeepKnownExactStatusCurrentThroughLabelCorrectionRecallAndDenyBrokenBinding() {
        actor.set(verified(30,true)); var ref=published(PresenceType.REQUEST,details("Estado exacto",false),List.of(upload()));
        var initial=tx(() -> lockedStatus(ref)).orElseThrow();
        assertThat(initial.reference()).isEqualTo(ref); assertThat(initial.digest()).isEqualTo(moderation.approval(ref).digest());
        UUID author=actor.get(); actor.set(administrator); UUID labelCase=cases.reviewCase(UUID.randomUUID(),ref);
        cases.act(UUID.randomUUID(),labelCase,0,"CORRECT_LABEL","ADULT","MISLABEL");
        actor.set(author); accounts.preferences(author,true,false);
        assertThat(tx(() -> lockedStatus(ref)).orElseThrow().label()).isEqualTo("ADULT");
        assertThat(reader.own(ref.resourceId()).current().details()).isEmpty();
        actor.set(administrator); UUID recallCase=cases.reviewCase(UUID.randomUUID(),ref); cases.act(UUID.randomUUID(),recallCase,0,"RECALL","ADULT","SAFETY");
        actor.set(author); assertThat(tx(() -> lockedStatus(ref)).orElseThrow().state()).isEqualTo("RECALLED");
        assertThatThrownBy(() -> files.read(store.attachments(ref).getFirst(),FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        doAnswer(invocation -> {
            var revision=(MarketplaceStore.Revision)invocation.callRealMethod();
            return new MarketplaceStore.Revision(revision.reference(),revision.author(),revision.details(),revision.text()+" altered",revision.label(),revision.digest(),revision.attachments());
        }).when(store).revision(any(),eq(ref.revisionId()));
        assertThatThrownBy(() -> reader.own(ref.resourceId())).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldRollbackCaughtInvalidOptionalLookupAndRetainRequiredLookupAbsenceFailure() {
        actor.set(verified(30,true)); var ref=draft(PresenceType.REQUEST,details("Ausencia no otorga acceso",false),List.of());
        jdbc.execute("create table if not exists profiles_intent(id uuid primary key)");
        assertThatThrownBy(() -> moderation.findApprovalForUpdate(ref)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        for (var invalid:List.of(new ContentReference(SurfaceKind.OFFER_BODY,ref.resourceId(),ref.revisionId()),new ContentReference(ref.ownerKind(),ref.resourceId(),UUID.randomUUID()))) {
            UUID intent=UUID.randomUUID();
            assertThatThrownBy(() -> tx(() -> {
                lockedStatus(ref); jdbc.update("insert into profiles_intent values(?)",intent);
                try { moderation.findApprovalForUpdate(invalid); } catch (ModerationFailure expected) { }
                return null;
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(jdbc.queryForObject("select count(*) from profiles_intent where id=?",Long.class,intent)).isZero();
        }
        assertThatThrownBy(() -> moderation.approval(ref)).isInstanceOf(ModerationFailure.class);
        UUID intent=UUID.randomUUID();
        assertThatThrownBy(() -> tx(() -> {
            lockedStatus(ref); jdbc.update("insert into profiles_intent values(?)",intent);
            try { moderation.approvalForUpdate(ref); } catch (ModerationFailure expected) { }
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from profiles_intent where id=?",Long.class,intent)).isZero();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void shouldSerializeAbsenceAndSubmissionAtActualCanonicalOwnerInBothOrders(boolean submitFirst) throws Exception {
        UUID author=verified(30,true); actor.set(author); var ref=prepare(draft(PresenceType.REQUEST,details("Carrera de estado",false),List.of()),List.of());
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var competitorEntered=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            try {
                var first=pool.submit(() -> as(author,() -> tx(() -> {
                    if (submitFirst) { service.submit(ref); } else { assertThat(lockedStatus(ref)).isEmpty(); }
                    held.countDown(); await(release); return null;
                })));
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var second=pool.submit(() -> as(author,() -> {
                    competitorEntered.countDown();
                    return submitFirst ? tx(() -> lockedStatus(ref)).orElseThrow() : service.submit(ref);
                }));
                assertThat(competitorEntered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); first.get(5,TimeUnit.SECONDS);
                assertThat(second.get(5,TimeUnit.SECONDS).reference()).isEqualTo(ref);
                assertThat(tx(() -> lockedStatus(ref)).orElseThrow().state()).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,ref.revisionId())).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @EnumSource(PresenceType.class)
    void shouldKeepEveryDraftPrivatePrepareFreshPublicationAndIntegrateExactOwnerEvidence(PresenceType type) {
        actor.set(verified(30,true)); var details=details("Trabajo "+UUID.randomUUID(),type.profile());
        UUID command=UUID.randomUUID(); var input=new MarketplaceService.Draft(command,Optional.empty(),0,type,actor.get(),details,"GENERAL",List.of(upload()));
        var original=service.save(input); assertThat(service.save(input)).isEqualTo(original);
        assertThatThrownBy(() -> reader.publicView(original.resourceId())).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> service.submit(original)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> service.prepare(new MarketplaceService.Preparation(UUID.randomUUID(),original,reader.own(original.resourceId()).version(),false,List.of()))).isInstanceOf(ModerationFailure.class);
        var ref=prepare(original,List.of(upload())); assertThat(ref).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("select capability from marketplace_revision where id=?",String.class,original.revisionId())).isEqualTo(type.draftCapability().name());
        assertThat(jdbc.queryForObject("select capability from marketplace_revision where id=?",String.class,ref.revisionId())).isEqualTo(type.publishCapability().name());
        assertThat(reader.own(ref.resourceId()).current().state()).isEqualTo("PREPARED_UNSUBMITTED");
        service.submit(ref); service.submit(ref); worker.process(claim(ref,"REVIEW"));
        assertThat(moderation.approval(ref).state()).isEqualTo("MANUAL_REVIEW");
        UUID author=actor.get(); actor.set(administrator); var inspected=evidence.evidence(ref);
        assertThat(inspected.text()).contains(details.title(),details.body(),details.province(),details.area(),details.availability(),"SHIPPED","A convenir",Boolean.toString(details.badgeOptIn()));
        assertThat(inspected.attachments()).hasSize(1);
        assertThat(files.read(inspected.attachments().getFirst(),FileAccess.Purpose.REVIEW).bytes()).isEqualTo(upload().bytes());
        actor.set(author); approve(ref,"GENERAL"); var view=reader.publicView(ref.resourceId());
        assertThat(view.details()).isEqualTo(details); assertThat(view.reference()).isEqualTo(ref);
        assertThat(files.read(view.attachments().getFirst(),FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(upload().bytes());
        assertThatThrownBy(() -> files.read(new FileReference(inspected.attachments().getFirst().id(),original,inspected.attachments().getFirst().digest()),FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> tx(() -> { jdbc.update("update marketplace_revision set title='changed' where id=?",ref.revisionId()); return null; })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from workbench_resource where id=?",Long.class,ref.resourceId())).isZero();
        actor.set(author); assertThat(cases.open(UUID.randomUUID(),ref,"REPORT","SAFETY","")).isNotNull();
    }

    @Test void shouldDenyGuardianPublicationRevocationAndPreserveMinorDraftProvenance() {
        UUID guardian=verified(30,true),minor=verified(16,true); admin.evidence(administrator,minor,"MINOR_DRAFT",null);
        UUID link=guardians.request(minor,guardian,minor); admin.reviewGuardian(administrator,link,0,true);
        actor.set(minor); var original=draft(PresenceType.OFFER,details("Borrador menor sintético",false),List.of(upload()));
        assertThatThrownBy(() -> prepare(original,List.of())).isInstanceOf(IdentityFailure.class);
        actor.set(guardian); var ref=service.prepare(new MarketplaceService.Preparation(UUID.randomUUID(),original,reader.own(original.resourceId()).version(),true,List.of(upload())));
        assertThat(jdbc.queryForObject("select actor_id from marketplace_revision where id=?",UUID.class,original.revisionId())).isEqualTo(minor);
        assertThat(jdbc.queryForObject("select actor_id from marketplace_revision where id=?",UUID.class,ref.revisionId())).isEqualTo(guardian);
        service.submit(ref); UUID review=cases.open(UUID.randomUUID(),ref,"APPEAL","FALSE_POSITIVE","");
        actor.set(administrator); cases.act(UUID.randomUUID(),review,0,"TAKE","GENERAL","FALSE_POSITIVE"); cases.act(UUID.randomUUID(),review,1,"APPROVE","GENERAL","FALSE_POSITIVE");
        actor.set(guardian); guardians.revoke(guardian,link,1); worker.process(claim(ref,"ACTIVATE"));
        assertThat(moderation.approval(ref).state()).isEqualTo("AWAITING_ELIGIBILITY");
        assertThatThrownBy(() -> service.submit(ref)).isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> service.transition(UUID.randomUUID(),ref.resourceId(),reader.own(ref.resourceId()).version(),"CLOSE")).isInstanceOf(IdentityFailure.class);
        actor.set(minor); assertThat(reader.own(ref.resourceId()).revisions()).hasSize(2);
    }

    @Test void shouldRejectCaughtSubmissionBindingFailureAndWrongActorWithoutPartialQueue() {
        actor.set(verified(30,true)); var ref=prepare(draft(PresenceType.REQUEST,details("Restaurar instrumento raro",false),List.of()),List.of());
        var resource=tx(() -> store.resource(ref.resourceId(),false)); var revision=store.revision(resource,ref.revisionId());
        jdbc.execute("create table if not exists profiles_intent(id uuid primary key)"); UUID intent=UUID.randomUUID();
        assertThatThrownBy(() -> tx(() -> {
            jdbc.update("insert into profiles_intent values(?)",intent);
            try { moderation.submitForReview(revision.author(),ref,"Texto falsificado","GENERAL",List.of()); } catch (ModerationFailure expected) { }
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from profiles_intent where id=?",Long.class,intent)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,ref.revisionId())).isZero();
        actor.set(verified(30,true)); assertThatThrownBy(() -> service.submit(ref)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.of(ref.resourceId()),resource.version(),PresenceType.REQUEST,resource.subject(),details("Suplantar",false),"GENERAL",List.of()))).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldRetainOldApprovalRejectStaleCommandsAndNeverReactivatePausedOrClosedListings() {
        actor.set(verified(30,true)); var first=published(PresenceType.OFFER,details("Impresión aprobada",false),List.of(upload()));
        long version=reader.own(first.resourceId()).version();
        var edit=service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.of(first.resourceId()),version,PresenceType.OFFER,actor.get(),details("Pendiente secreta",false),"GENERAL",List.of()));
        var prepared=prepare(edit,List.of()); service.submit(prepared);
        actor.set(administrator); UUID caseId=cases.reviewCase(UUID.randomUUID(),prepared); cases.act(UUID.randomUUID(),caseId,0,"APPROVE","GENERAL","SAFETY");
        var activation=claim(prepared,"ACTIVATE"); UUID author=jdbc.queryForObject("select actor_id from marketplace_revision where id=?",UUID.class,prepared.revisionId()); actor.set(author);
        assertThat(reader.publicView(first.resourceId()).reference()).isEqualTo(first);
        assertThat(discovery.search(query(PresenceType.OFFER,"secreta",0)).items()).isEmpty();
        UUID pause=UUID.randomUUID(); long current=reader.own(first.resourceId()).version(); service.transition(pause,first.resourceId(),current,"PAUSE"); service.transition(pause,first.resourceId(),current,"PAUSE");
        worker.process(activation); assertThatThrownBy(() -> reader.publicView(first.resourceId())).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> files.read(store.attachments(first).getFirst(),FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> service.transition(UUID.randomUUID(),first.resourceId(),version,"RESUME")).isInstanceOf(ModerationFailure.class);
        service.transition(UUID.randomUUID(),first.resourceId(),reader.own(first.resourceId()).version(),"RESUME");
        assertThat(reader.publicView(first.resourceId()).reference()).isEqualTo(first);
        service.transition(UUID.randomUUID(),first.resourceId(),reader.own(first.resourceId()).version(),"CLOSE");
        assertThatThrownBy(() -> tx(() -> listings.requireOpenForResponse(first.resourceId()))).isInstanceOf(ModerationFailure.class);
        assertThat(tx(() -> listings.historical(first)).reference()).isEqualTo(first);
        assertThatThrownBy(() -> service.transition(UUID.randomUUID(),first.resourceId(),reader.own(first.resourceId()).version(),"RESUME")).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> listings.currentPublic(first.resourceId())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void shouldDiscoverSpanishLexemesTagsCoarseAreaAllModesAndVisiblePagination() {
        actor.set(verified(30,true)); String token="único"+UUID.randomUUID().toString().replace("-","");
        List<ContentReference> refs=new ArrayList<>();
        for (int index=0;index<12;index++) {
            clock.advance(61);
            var details=new MarketplaceDetails("Impresiones "+token+" "+index,"Fabricar piezas y diseños",List.of("maker"),"Córdoba","Centro",Set.of(FulfillmentMode.values()),"Agosto",Optional.of(new BigDecimal("123.45")),Optional.of(LocalDate.of(2026,12,1)),false);
            refs.add(published(PresenceType.OFFER,details,List.of()));
        }
        var page0=discovery.search(query(PresenceType.OFFER,"impresión "+token,0)); var page1=discovery.search(query(PresenceType.OFFER,"impresión "+token,1));
        assertThat(page0.items()).hasSize(10); assertThat(page0.hasMore()).isTrue(); assertThat(page1.items()).hasSize(2); assertThat(page1.hasMore()).isFalse();
        assertThat(page0.items().stream().map(MarketplaceReader.PublicView::reference).toList()).doesNotContainAnyElementsOf(page1.items().stream().map(MarketplaceReader.PublicView::reference).toList());
        for (FulfillmentMode mode:FulfillmentMode.values()) { assertThat(discovery.search(new DiscoveryService.Query(PresenceType.OFFER,token,"maker","Córdoba","centro",Optional.of(mode),0)).items()).hasSize(10); }
        assertThat(discovery.search(new DiscoveryService.Query(PresenceType.OFFER,token,"otro","Córdoba","Centro",Optional.empty(),0)).items()).isEmpty();
        assertThat(discovery.search(query(PresenceType.REQUEST,token,0)).items()).isEmpty();
        assertThat(discovery.search(query(PresenceType.OFFER,"noexiste"+token,0)).items()).isEmpty();
        actor.remove(); assertThat(discovery.search(query(PresenceType.OFFER,token,0)).items()).hasSize(10);
    }

    @ParameterizedTest @ValueSource(strings={"title","body","tags","province","area","modes","availability","amount","deadline","label","media","badge"})
    void shouldReviewEachAuthoredEditAndMediaAsOneImmutableRevision(String field) {
        UUID author=verified(30,true); actor.set(author);
        PresenceType type=field.equals("badge") ? PresenceType.PROVIDER_PROFILE : PresenceType.OFFER;
        var before=details("Revisión anterior "+UUID.randomUUID(),false);
        var original=published(type,before,List.of(upload()));
        String token="nuevo"+UUID.randomUUID().toString().replace("-","");
        var after=new MarketplaceDetails(field.equals("title") ? token : before.title(),field.equals("body") ? token : before.body(),
                field.equals("tags") ? List.of(token) : before.tags(),field.equals("province") ? "Córdoba" : before.province(),
                field.equals("area") ? token : before.area(),field.equals("modes") ? Set.of(FulfillmentMode.REMOTE) : before.modes(),
                field.equals("availability") ? token : before.availability(),field.equals("amount") ? Optional.of(new BigDecimal("456.78")) : before.amount(),
                field.equals("deadline") ? Optional.of(LocalDate.of(2027,1,12)) : before.deadline(),field.equals("badge"));
        String label=field.equals("label") ? "SENSITIVE" : "GENERAL";
        var edit=service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.of(original.resourceId()),reader.own(original.resourceId()).version(),type,author,after,label,List.of()));
        byte[] editedBytes=(field.equals("media") ? "synthetic:tattoo" : "synthetic:clear").getBytes(StandardCharsets.UTF_8);
        var prepared=prepare(edit,List.of(new MarketplaceService.Upload("text/plain",editedBytes))); service.submit(prepared);
        var pending=store.attachments(prepared).getFirst();
        assertThatThrownBy(() -> files.read(pending,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        var visible=reader.publicView(original.resourceId());
        assertThat(visible.reference()).isEqualTo(original); assertThat(visible.details()).isEqualTo(before); assertThat(visible.label()).isEqualTo("GENERAL");
        assertThat(files.read(visible.attachments().getFirst(),FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(upload().bytes());
        if (!type.profile()) { assertThat(discovery.search(query(type,token,0)).items()).isEmpty(); }
        actor.set(administrator); assertThat(evidence.evidence(prepared).text()).isEqualTo(after.reviewText(type));
        actor.set(author); approve(prepared,label);
        var published=reader.publicView(original.resourceId()); assertThat(published.reference()).isEqualTo(prepared);
        assertThat(published.details()).isEqualTo(after); assertThat(published.label()).isEqualTo(label);
        assertThat(files.read(published.attachments().getFirst(),FileAccess.Purpose.ORDINARY).bytes()).isEqualTo(editedBytes);
        assertThatThrownBy(() -> files.read(visible.attachments().getFirst(),FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldRankSpanishTitleMatchesAndDeriveContinuationOnlyFromVisibleResults() {
        actor.set(verified(30,true)); UUID author=actor.get(); String token="ranking"+UUID.randomUUID().toString().replace("-","");
        List<ContentReference> visible=new ArrayList<>();
        for (int index=0;index<24;index++) {
            clock.advance(61);
            var details=new MarketplaceDetails(index==23 ? "Restauraciones "+token : "Trabajo "+index,
                    "Restaurar "+token,List.of("ranking"),"Buenos Aires","La Plata",Set.of(FulfillmentMode.REMOTE),"A convenir",Optional.empty(),Optional.empty(),false);
            var draft=draft(PresenceType.REQUEST,details,List.of()); var prepared=prepare(draft,List.of()); service.submit(prepared);
            approve(prepared,index<13 ? "ADULT" : "GENERAL");
            if(index>=13) { visible.add(prepared); }
        }
        actor.remove(); var first=discovery.search(query(PresenceType.REQUEST,"restauración "+token,0));
        assertThat(first.items()).hasSize(10); assertThat(first.hasMore()).isTrue();
        assertThat(first.items().getFirst().reference()).isEqualTo(visible.getLast());
        var last=discovery.search(query(PresenceType.REQUEST,"restauración "+token,1));
        assertThat(last.items()).hasSize(1); assertThat(last.hasMore()).isFalse();
        assertThat(discovery.search(query(PresenceType.REQUEST,"restauración "+token,2)).items()).isEmpty();
        assertThat(first.items().stream().map(MarketplaceReader.PublicView::reference)).allMatch(visible::contains);
        actor.set(author); assertThat(reader.own(visible.getFirst().resourceId()).current().state()).isEqualTo("APPROVED");
    }

    @Test void shouldKeepOpaqueAndUnsafeNewOwnerFilesQuarantinedThroughManualReview() {
        actor.set(verified(30,true)); var original=draft(PresenceType.PROVIDER_PROFILE,details("Portafolio en cuarentena",false),List.of());
        var ref=prepare(original,List.of(new MarketplaceService.Upload("application/pdf","synthetic opaque pdf".getBytes(StandardCharsets.UTF_8))));
        service.submit(ref); worker.process(claim(ref,"REVIEW"));
        var file=store.attachments(ref).getFirst(); UUID author=actor.get(); actor.set(administrator);
        UUID caseId=cases.reviewCase(UUID.randomUUID(),ref);
        assertThatThrownBy(() -> cases.act(UUID.randomUUID(),caseId,0,"APPROVE","GENERAL","SAFETY")).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> files.read(file,FileAccess.Purpose.REVIEW)).isInstanceOf(ModerationFailure.class);
        actor.set(author); assertThatThrownBy(() -> files.read(file,FileAccess.Purpose.ORDINARY)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> reader.publicView(ref.resourceId())).isInstanceOf(ModerationFailure.class);
        assertThat(moderation.approval(ref).state()).isEqualTo("MANUAL_REVIEW");
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void shouldSerializeActivationWithCurrentAuthorRestrictionOrGuardianRevocation(boolean guardian,boolean activationFirst) throws Exception {
        UUID subject=verified(guardian ? 16 : 30,true),author=subject;
        UUID link=null;
        if (guardian) {
            author=verified(30,true); admin.evidence(administrator,subject,"MINOR_DRAFT",null);
            link=guardians.request(subject,author,subject); admin.reviewGuardian(administrator,link,0,true);
        }
        actor.set(subject); var original=draft(PresenceType.OFFER,details("Autoría actual "+UUID.randomUUID(),false),List.of());
        actor.set(author); var ref=prepare(original,List.of()); service.submit(ref);
        actor.set(administrator); UUID caseId=cases.reviewCase(UUID.randomUUID(),ref); cases.act(UUID.randomUUID(),caseId,0,"APPROVE","GENERAL","SAFETY");
        var activation=claim(ref,"ACTIVATE"); UUID publishingAuthor=author,relationship=link;
        Runnable withdraw=guardian ? () -> guardians.revoke(publishingAuthor,relationship,1) : () -> admin.restriction(administrator,subject,true);
        UUID mutator=guardian ? author : administrator;
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var competitorEntered=new CountDownLatch(1);
        if(activationFirst) {
            doAnswer(invocation -> { var result=invocation.callRealMethod(); held.countDown(); await(release); return result; }).when(owner).lockRevision(any(),eq(ref));
        }
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                Future<?> first=activationFirst ? pool.submit(() -> as(publishingAuthor,() -> { worker.process(activation); return null; }))
                        : pool.submit(() -> as(mutator,() -> tx(() -> { withdraw.run(); entities.flush(); held.countDown(); await(release); return null; })));
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var second=pool.submit(() -> as(activationFirst ? mutator : publishingAuthor,() -> {
                    competitorEntered.countDown(); if(activationFirst) { withdraw.run(); } else { worker.process(activation); } return null;
                }));
                assertThat(competitorEntered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); first.get(5,TimeUnit.SECONDS); second.get(5,TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?",UUID.class,ref.resourceId())).isEqualTo(activationFirst ? ref.revisionId() : null);
        assertThat(moderation.approval(ref).state()).isEqualTo(activationFirst ? "APPROVED" : "AWAITING_ELIGIBILITY");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void shouldSerializeDelayedActivationAndEditWithoutPublishingNewUnreviewedRevision(boolean activationFirst) throws Exception {
        UUID author=verified(30,true); actor.set(author); var ref=prepare(draft(PresenceType.REQUEST,details("Aprobación tardía",false),List.of()),List.of());
        service.submit(ref); actor.set(administrator); UUID caseId=cases.reviewCase(UUID.randomUUID(),ref); cases.act(UUID.randomUUID(),caseId,0,"APPROVE","GENERAL","SAFETY");
        var activation=claim(ref,"ACTIVATE"); actor.set(author); long version=reader.own(ref.resourceId()).version();
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var competitorEntered=new CountDownLatch(1);
        if(activationFirst) {
            doAnswer(invocation -> { var result=invocation.callRealMethod(); held.countDown(); await(release); return result; }).when(owner).lockRevision(any(),eq(ref));
        }
        var edited=new AtomicReference<ContentReference>();
        Runnable edit=() -> {
            long currentVersion=activationFirst ? jdbc.queryForObject("select version from marketplace_resource where id=?",Long.class,ref.resourceId()) : version;
            edited.set(service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.of(ref.resourceId()),currentVersion,PresenceType.REQUEST,author,details("Nueva edición privada",false),"GENERAL",List.of())));
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                Future<?> first=activationFirst ? pool.submit(() -> as(author,() -> { worker.process(activation); return null; }))
                        : pool.submit(() -> as(author,() -> tx(() -> { edit.run(); held.countDown(); await(release); return null; })));
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var second=pool.submit(() -> as(author,() -> { competitorEntered.countDown(); if(activationFirst) { edit.run(); } else { worker.process(activation); } return null; }));
                assertThat(competitorEntered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); first.get(5,TimeUnit.SECONDS);
                if(activationFirst) {
                    assertThatThrownBy(() -> second.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ModerationFailure.class);
                    as(author,() -> { edit.run(); return null; });
                } else { second.get(5,TimeUnit.SECONDS); }
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("select current_revision from marketplace_resource where id=?",UUID.class,ref.resourceId())).isEqualTo(edited.get().revisionId());
        assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?",UUID.class,ref.resourceId())).isEqualTo(activationFirst ? ref.revisionId() : null);
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?",String.class,activation.id())).isEqualTo(activationFirst ? "ACTIVATED" : "SUPERSEDED");
    }

    @Test void shouldShowOnlyCurrentOptedInMinimalBadgeAndSuppressItAfterCanonicalWaitCrossesExpiry() throws Exception {
        UUID author=verified(30,true); actor.set(author); admin.evidence(administrator,author,"OPTIONAL_BADGE",null);
        var ref=published(PresenceType.PROVIDER_PROFILE,details("Perfil con insignia",true),List.of());
        var view=reader.publicView(ref.resourceId()); assertThat(view.badge()).isPresent();
        assertThat(view.badge().orElseThrow().provenance()).isEqualTo("LOCAL_SYNTHETIC_CHECK");
        admin.restriction(administrator,author,true); assertThat(reader.publicView(ref.resourceId()).badge()).isEmpty(); admin.restriction(administrator,author,false);
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var readEntered=new CountDownLatch(1);
        doAnswer(invocation -> { if (Boolean.TRUE.equals(invocation.getArgument(1))) { readEntered.countDown(); } return invocation.callRealMethod(); }).when(store).resource(eq(ref.resourceId()),anyBoolean());
        try (var pool=Executors.newFixedThreadPool(2)) {
            try {
                var holder=pool.submit(() -> tx(() -> { jdbc.queryForList("select id from marketplace_resource where id=? for update",ref.resourceId()); held.countDown(); await(release); return null; }));
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var read=pool.submit(() -> as(author,() -> reader.publicView(ref.resourceId()))); assertThat(readEntered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> read.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                clock.advance(86401); release.countDown(); holder.get(5,TimeUnit.SECONDS); assertThat(read.get(5,TimeUnit.SECONDS).badge()).isEmpty();
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @MethodSource("withdrawals")
    void shouldFinishCurrentReadBeforeConflictingWithdrawalAndDenyNextRead(Withdrawal withdrawal) throws Exception {
        var fixture=withdrawalFixture(withdrawal);
        var readHeld=new CountDownLatch(1); var releaseRead=new CountDownLatch(1); var mutationEntered=new CountDownLatch(1);
        if (withdrawal.bytes()) {
            doAnswer(invocation -> { invocation.callRealMethod(); readHeld.countDown(); await(releaseRead); return null; }).when(policy).requireCurrentAccess(fixture.file(),FileAccess.Purpose.ORDINARY);
        } else {
            doAnswer(invocation -> { var result=invocation.callRealMethod(); readHeld.countDown(); await(releaseRead); return result; }).when(store).resource(fixture.reference().resourceId(),true);
        }
        try (var pool=Executors.newFixedThreadPool(2)) {
            try {
                var read=pool.submit(() -> as(fixture.viewer(),fixture.read())); assertThat(readHeld.await(5,TimeUnit.SECONDS)).isTrue();
                var mutation=pool.submit(() -> as(fixture.mutator(),() -> { mutationEntered.countDown(); fixture.mutation().run(); return null; }));
                assertThat(mutationEntered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> mutation.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseRead.countDown(); assertThat(read.get(5,TimeUnit.SECONDS)).isEqualTo(fixture.expected()); mutation.get(5,TimeUnit.SECONDS);
            } finally { releaseRead.countDown(); }
        }
        reset(store,policy);
        assertThatThrownBy(() -> as(fixture.viewer(),fixture.read())).isInstanceOf(fixture.denial());
    }

    @ParameterizedTest @MethodSource("withdrawals")
    void shouldReturnNoProjectionOrBytesWhenWithdrawalCommitsBeforeRead(Withdrawal withdrawal) throws Exception {
        var fixture=withdrawalFixture(withdrawal);
        var mutationHeld=new CountDownLatch(1); var releaseMutation=new CountDownLatch(1); var readEntered=new CountDownLatch(1);
        doAnswer(invocation -> { if (fixture.viewer().equals(actor.get())) { readEntered.countDown(); } return invocation.callRealMethod(); }).when(store).resource(fixture.reference().resourceId(),false);
        try (var pool=Executors.newFixedThreadPool(2)) {
            try {
                var mutation=pool.submit(() -> as(fixture.mutator(),() -> tx(() -> { fixture.mutation().run(); entities.flush(); mutationHeld.countDown(); await(releaseMutation); return null; })));
                assertThat(mutationHeld.await(5,TimeUnit.SECONDS)).isTrue();
                var read=pool.submit(() -> as(fixture.viewer(),fixture.read())); assertThat(readEntered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> read.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseMutation.countDown(); mutation.get(5,TimeUnit.SECONDS);
                assertThatThrownBy(() -> read.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(fixture.denial());
            } finally { releaseMutation.countDown(); }
        }
    }

    static java.util.stream.Stream<Withdrawal> withdrawals() {
        List<Withdrawal> result=new ArrayList<>();
        for (boolean bytes:List.of(false,true)) {
            result.add(new Withdrawal(PresenceType.CUSTOMER_PROFILE,"SENSITIVE_PREFERENCE",bytes));
            result.add(new Withdrawal(PresenceType.PROVIDER_PROFILE,"ADULT_PREFERENCE",bytes));
            result.add(new Withdrawal(PresenceType.OFFER,"RESTRICTION",bytes));
            result.add(new Withdrawal(PresenceType.REQUEST,"PAUSE",bytes));
            result.add(new Withdrawal(PresenceType.PROVIDER_PROFILE,"CLOSE",bytes));
            result.add(new Withdrawal(PresenceType.REQUEST,"RECALL",bytes));
        }
        return result.stream();
    }

    RaceFixture withdrawalFixture(Withdrawal withdrawal) {
        UUID author=verified(30,true),viewer=verified(30,false); actor.set(author);
        String label=withdrawal.action().equals("SENSITIVE_PREFERENCE") ? "SENSITIVE" : Set.of("ADULT_PREFERENCE","RESTRICTION").contains(withdrawal.action()) ? "ADULT" : "GENERAL";
        var original=draft(withdrawal.type(),details("Carrera "+UUID.randomUUID(),false),List.of());
        byte[] bytes=(label.equals("SENSITIVE") ? "synthetic:medical" : label.equals("ADULT") ? "synthetic:adult" : "synthetic:clear").getBytes(StandardCharsets.UTF_8);
        var ref=prepare(original,List.of(new MarketplaceService.Upload("text/plain",bytes))); service.submit(ref); approve(ref,label);
        var file=reader.publicView(ref.resourceId()).attachments().getFirst();
        long version=reader.own(ref.resourceId()).version();
        actor.set(administrator); UUID caseId=withdrawal.action().equals("RECALL") ? cases.reviewCase(UUID.randomUUID(),ref) : null;
        UUID mutator=switch(withdrawal.action()) { case "RESTRICTION","RECALL" -> administrator; case "PAUSE","CLOSE" -> author; default -> viewer; };
        Runnable mutation=switch(withdrawal.action()) {
            case "SENSITIVE_PREFERENCE" -> () -> accounts.preferences(viewer,false,true);
            case "ADULT_PREFERENCE" -> () -> accounts.preferences(viewer,true,false);
            case "RESTRICTION" -> () -> admin.restriction(administrator,viewer,true);
            case "RECALL" -> () -> cases.act(UUID.randomUUID(),caseId,0,"RECALL",label,"SAFETY");
            default -> () -> service.transition(UUID.randomUUID(),ref.resourceId(),version,withdrawal.action());
        };
        Supplier<Object> read=withdrawal.bytes() ? () -> files.read(file,FileAccess.Purpose.ORDINARY).bytes() : () -> reader.publicView(ref.resourceId()).details().body();
        Class<? extends RuntimeException> denial=Set.of("SENSITIVE_PREFERENCE","ADULT_PREFERENCE","RESTRICTION").contains(withdrawal.action()) ? IdentityFailure.class : ModerationFailure.class;
        return new RaceFixture(ref,file,viewer,mutator,mutation,read,withdrawal.bytes() ? bytes : details("unused",false).body(),denial);
    }

    @Test void shouldHideUnapprovedBadgeOptInAndRevalidateSearchSelectionAfterConcurrentEdit() {
        UUID author=verified(30,true); actor.set(author); admin.evidence(administrator,author,"OPTIONAL_BADGE",null);
        var profile=published(PresenceType.CUSTOMER_PROFILE,details("Sin insignia",false),List.of());
        assertThat(reader.publicView(profile.resourceId()).badge()).isEmpty();
        var edit=service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.of(profile.resourceId()),reader.own(profile.resourceId()).version(),PresenceType.CUSTOMER_PROFILE,author,details("Insignia pendiente",true),"GENERAL",List.of()));
        assertThat(reader.publicView(profile.resourceId()).badge()).isEmpty(); assertThat(reader.publicView(profile.resourceId()).details().title()).isEqualTo("Sin insignia");
        var first=published(PresenceType.OFFER,details("Búsqueda anterior",false),List.of());
        var before=reader.publicView(first.resourceId()); var selection=new MarketplaceReader.Selection(first,author,moderation.approval(first).digest(),before.label());
        var next=service.save(new MarketplaceService.Draft(UUID.randomUUID(),Optional.of(first.resourceId()),reader.own(first.resourceId()).version(),PresenceType.OFFER,author,details("Otra selección",false),"GENERAL",List.of()));
        var prepared=prepare(next,List.of()); service.submit(prepared); approve(prepared,"GENERAL");
        assertThatThrownBy(() -> tx(() -> reader.currentInTransaction(first.resourceId(),Optional.of(selection)))).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldEnforceProviderAndAdultAudiencesAndKeepUnknownPendingMetadataControlled() {
        UUID author=verified(30,false); actor.set(author);
        assertThatThrownBy(() -> draft(PresenceType.OFFER,details("Proveedor no habilitado",false),List.of())).isInstanceOf(IdentityFailure.class);
        var request=published(PresenceType.REQUEST,details("Necesito restaurar una pieza rara",false),List.of());
        var original=draft(PresenceType.CUSTOMER_PROFILE,details("Perfil adulto",false),List.of()); var adult=prepare(original,List.of()); service.submit(adult); accounts.preferences(author,true,true); approve(adult,"ADULT");
        actor.remove(); assertThat(reader.publicView(request.resourceId()).reference()).isEqualTo(request);
        assertThatThrownBy(() -> reader.publicView(adult.resourceId())).isInstanceOf(ModerationFailure.class);
        UUID unknown=accounts.register("unknown"+UUID.randomUUID()+"@example.test","Synthetic-password-12345").accountId().value(); actor.set(unknown);
        assertThatThrownBy(() -> reader.publicView(adult.resourceId())).isInstanceOf(IdentityFailure.class);
        actor.set(author); accounts.preferences(author,true,false); var pending=draft(PresenceType.REQUEST,details("Pendiente privado",false),List.of());
        assertThat(reader.own(pending.resourceId()).current().details()).isEmpty();
        actor.set(unknown); assertThatThrownBy(() -> reader.own(pending.resourceId())).isInstanceOf(ModerationFailure.class);
    }

    record Withdrawal(PresenceType type,String action,boolean bytes) {}
    record RaceFixture(ContentReference reference,FileReference file,UUID viewer,UUID mutator,Runnable mutation,Supplier<Object> read,Object expected,Class<? extends RuntimeException> denial) {}

    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5,TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
