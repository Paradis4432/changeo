package ar.changeo.conversations;

import ar.changeo.agreements.AgreementService;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.JobContentService;
import ar.changeo.marketplace.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import ar.changeo.ChangeoApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(classes = {ChangeoApplication.class, ConversationJourneyPostgresTest.Configuration.class}, properties = {
        "spring.flyway.schemas=case_origin_test", "spring.flyway.default-schema=case_origin_test",
        "spring.jpa.properties.hibernate.default_schema=case_origin_test", "spring.datasource.hikari.schema=case_origin_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.mfa-key=.runtime/conversations-test-mfa.key", "changeo.files-root=.runtime/conversations-test-artifacts"})
class CaseOriginPostgresTest extends ConversationJourneyPostgresTest {
    @Autowired OwnerRegistry originOwners;
    @Autowired WorkbenchService workbench;
    @Autowired ContentReader workbenchReader;
    @MockitoSpyBean ModerationService originMetadata;

    @ParameterizedTest
    @EnumSource(PresenceType.class)
    void cnMarketplaceExactOriginKeepsCurrentPublicAndOldOwnStatusSeparate(PresenceType type) {
        UUID publisher = adult(type.provider()), viewer = adult(false);
        var reference = listing(publisher, type);
        assertOriginLink(reference, "marketplace");
        assertThat(as(viewer, () -> marketplaceReader.origin(reference)).reference()).isEqualTo(reference);
        var original = as(publisher, () -> marketplaceReader.own(reference.resourceId()));
        for (int index = 0; index < 22; index++) {
            clock.advance(61);
            var own = as(publisher, () -> marketplaceReader.own(reference.resourceId()));
            var details = original.current().details().orElseThrow();
            as(publisher, () -> marketplace.save(new MarketplaceService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), own.version(), type,
                    publisher, details, "GENERAL", List.of())));
        }
        assertThat(as(publisher, () -> marketplaceReader.own(reference.resourceId())).revisions()).noneMatch(row -> row.reference().equals(reference));
        var exact = as(publisher, () -> marketplaceReader.origin(reference));
        assertThat(exact.reference()).isEqualTo(reference);
        assertThat(exact.details()).isPresent();
        assertThat(exact.current()).isFalse();
        assertThat(as(viewer, () -> marketplaceReader.origin(reference)).reference()).isEqualTo(reference);
        var before = effects();
        assertThat(as(publisher, () -> marketplaceReader.origin(reference)).reference()).isEqualTo(reference);
        assertThat(effects()).isEqualTo(before);
        var own = as(publisher, () -> marketplaceReader.own(reference.resourceId()));
        as(publisher, () -> { marketplace.transition(UUID.randomUUID(), reference.resourceId(), own.version(), "PAUSE"); return null; });
        assertThatThrownBy(() -> as(viewer, () -> marketplaceReader.origin(reference))).isInstanceOf(ModerationFailure.class);
        assertThat(as(publisher, () -> marketplaceReader.origin(reference)).reference()).isEqualTo(reference);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLIC", "PRIVATE"})
    void cnWorkbenchExactOldRevisionSurvivesBoundedHistoryWithoutNewOrdinaryRights(String audience) {
        UUID author = adult(false), member = adult(false), stranger = adult(false);
        var reference = as(author, () -> workbench.save(new WorkbenchService.Draft(UUID.randomUUID(), Optional.empty(), 0, "Primera revisión exacta",
                "GENERAL", audience, Optional.empty(), audience.equals("PRIVATE") ? Optional.of(member) : Optional.empty(), List.of())));
        as(author, () -> workbench.submit(reference)); approve(reference, "GENERAL");
        assertOriginLink(reference, "moderation");
        assertThat(as(member, () -> workbenchReader.origin(reference)).text()).isEqualTo("Primera revisión exacta");
        if (audience.equals("PRIVATE")) { assertThatThrownBy(() -> as(stranger, () -> workbenchReader.origin(reference))).isInstanceOf(ModerationFailure.class); }
        for (int index = 0; index < 22; index++) {
            clock.advance(61);
            long version = as(author, () -> workbenchReader.own(reference.resourceId())).version();
            as(author, () -> workbench.save(new WorkbenchService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), version, "Revisión posterior",
                    "GENERAL", audience, Optional.empty(), Optional.empty(), List.of())));
        }
        assertThat(as(author, () -> workbenchReader.own(reference.resourceId())).revisions()).noneMatch(row -> row.reference().equals(reference));
        assertThat(as(author, () -> workbenchReader.origin(reference)).text()).isEqualTo("Primera revisión exacta");
        var current = as(author, () -> workbenchReader.own(reference.resourceId())).revisions().getFirst().reference();
        as(author, () -> workbench.submit(current)); approve(current, "GENERAL");
        assertThatThrownBy(() -> as(member, () -> workbenchReader.origin(reference))).isInstanceOf(ModerationFailure.class);
        assertThat(as(author, () -> workbenchReader.origin(reference)).reference()).isEqualTo(reference);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_MESSAGE", "PROPOSAL", "QUESTION", "COMMENT"})
    void cnMessageOriginSelectsExactOwnOldRevisionAndNeverSubstitutesLatestForOthers(String kind) {
        var fixture = negotiate();
        boolean discussion = kind.equals("QUESTION") || kind.equals("COMMENT");
        var reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                discussion ? Optional.empty() : Optional.of(fixture.job().conversation().id()), discussion ? Optional.of(fixture.job().conversation().listing().resourceId()) : Optional.empty(),
                new SubjectId(fixture.provider()), kind, "Contenido exacto original", "GENERAL", List.of())));
        as(fixture.provider(), () -> messages.submit(reference)); approve(reference, "GENERAL");
        assertOriginLink(reference, "conversations");
        assertThat(as(fixture.customer(), () -> messageReader.origin(reference)).body()).isEqualTo("Contenido exacto original");
        long version = as(fixture.provider(), () -> messageReader.origin(reference)).version();
        var changed = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), version,
                discussion ? Optional.empty() : Optional.of(fixture.job().conversation().id()), discussion ? Optional.of(fixture.job().conversation().listing().resourceId()) : Optional.empty(),
                new SubjectId(fixture.provider()), kind, "Contenido posterior diferente", "GENERAL", List.of())));
        assertThat(as(fixture.provider(), () -> messageReader.origin(reference)).body()).isEqualTo("Contenido exacto original");
        assertThat(as(fixture.customer(), () -> messageReader.origin(reference)).body()).isEqualTo("Contenido exacto original");
        as(fixture.provider(), () -> messages.submit(changed)); approve(changed, "GENERAL");
        assertThatThrownBy(() -> as(fixture.customer(), () -> messageReader.origin(reference))).isInstanceOf(ModerationFailure.class);
        assertThat(as(fixture.provider(), () -> messageReader.origin(reference)).reference()).isEqualTo(reference);
        if (discussion) {
            UUID outsider = adult(false);
            assertThat(as(outsider, () -> messageReader.origin(changed)).body()).isEqualTo("Contenido posterior diferente");
        } else {
            UUID outsider = adult(false);
            assertThatThrownBy(() -> as(outsider, () -> messageReader.origin(changed))).isInstanceOf(ModerationFailure.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUESTION", "COMMENT"})
    void cnPublicDiscussionExactOriginIsNotLimitedToFirstTenAndRequiresCapturedParent(String kind) {
        UUID provider = adult(true), author = adult(false), viewer = adult(false);
        var offer = listing(provider, PresenceType.OFFER);
        List<ContentReference> refs = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            clock.advance(61);
            var ref = as(author, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.empty(), Optional.of(offer.resourceId()),
                    new SubjectId(author), kind, "Consulta exacta fuera de la primera página", "GENERAL", List.of())));
            as(author, () -> messages.submit(ref)); approve(ref, "GENERAL"); refs.add(ref);
        }
        var shown = as(viewer, () -> messageReader.discussion(offer)).stream().map(MessageReader.MessageView::reference).toList();
        var selected = refs.stream().filter(ref -> !shown.contains(ref)).findFirst().orElseThrow();
        assertThat(as(viewer, () -> messageReader.origin(selected)).reference()).isEqualTo(selected);
        long version = as(provider, () -> marketplaceReader.own(offer.resourceId())).version();
        as(provider, () -> { marketplace.transition(UUID.randomUUID(), offer.resourceId(), version, "CLOSE"); return null; });
        assertThatThrownBy(() -> as(viewer, () -> messageReader.origin(selected))).isInstanceOf(ModerationFailure.class);
        assertThat(as(author, () -> messageReader.origin(selected)).reference()).isEqualTo(selected);
    }

    @Test
    void cnQuoteOldStatusDoesNotReplaceCurrentApprovedOrTransferSelectedHistory() {
        UUID provider = adult(true), customer = adult(false);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), JobContext.FulfillmentMode.REMOTE));
        UUID job = access.discover(thread).jobId();
        var reference = quote(provider, job, JobContext.FulfillmentMode.REMOTE);
        assertOriginLink(reference, "agreements");
        long version = agreementAccess.discover(reference).version();
        var changed = as(provider, () -> agreements.save(new AgreementService.Draft(UUID.randomUUID(), job, Optional.of(reference.resourceId()), version,
                new SubjectId(provider), "INITIAL", terms(JobContext.FulfillmentMode.REMOTE), "GENERAL", List.of())));
        assertThat(as(provider, () -> agreementReader.origin(reference)).reference()).isEqualTo(reference);
        assertThat(as(customer, () -> agreementReader.origin(reference)).reference()).isEqualTo(reference);
        as(provider, () -> agreements.submit(changed)); approve(changed, "GENERAL");
        assertThatThrownBy(() -> as(customer, () -> agreementReader.origin(reference))).isInstanceOf(RuntimeException.class);
        assertThat(as(provider, () -> agreementReader.origin(reference)).reference()).isEqualTo(reference);
        UUID stranger = adult(false);
        assertThatThrownBy(() -> as(stranger, () -> agreementReader.origin(changed))).isInstanceOf(ModerationFailure.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROGRESS", "FEEDBACK", "FINAL_DELIVERY", "SUPPORT_NOTE", "REVIEW"})
    void cnAllJobKindsUseActualNegotiatedLifecycleAndExactSideWithoutEffects(String kind) {
        var fixture = negotiate();
        if (!kind.equals("SUPPORT_NOTE")) {
            fundingFacts.confirm(fixture.job());
            as(fixture.provider(), () -> execution.start(UUID.randomUUID(), fixture.job().id(), fixture.job().version(), new SubjectId(fixture.provider())));
        }
        if (kind.equals("REVIEW")) {
            var delivery = jobReference(fixture.provider(), fixture.job().id(), "FINAL_DELIVERY");
            var pending = jobs.discover(fixture.job().id());
            as(fixture.customer(), () -> execution.acceptFinal(UUID.randomUUID(), pending.id(), pending.version(), new SubjectId(fixture.customer()), delivery));
        }
        UUID author = kind.equals("FEEDBACK") || kind.equals("REVIEW") ? fixture.customer() : fixture.provider();
        var reference = jobReference(author, fixture.job().id(), kind);
        assertOriginLink(reference, "jobs");
        var before = effects();
        for (UUID participant : List.of(fixture.customer(), fixture.provider())) {
            var origin = as(participant, () -> jobReader.origin(reference));
            assertThat(origin.reference()).isEqualTo(reference);
            assertThat(origin.kind()).isEqualTo(kind);
            assertThat(origin.visible()).isTrue();
        }
        assertThat(effects()).isEqualTo(before);
        UUID stranger = adult(false);
        assertThatThrownBy(() -> as(stranger, () -> jobReader.origin(reference))).isInstanceOf(ModerationFailure.class);
        long version = as(author, () -> jobReader.origin(reference)).version();
        var changed = as(author, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.of(reference.resourceId()), version,
                new SubjectId(author), kind, "Evidencia posterior distinta", kind.equals("REVIEW") ? 5 : null, "GENERAL", List.of())));
        UUID counterpart = author.equals(fixture.customer()) ? fixture.provider() : fixture.customer();
        assertThat(as(author, () -> jobReader.origin(reference)).body()).isEqualTo("Evidencia exacta del trabajo");
        assertThat(as(counterpart, () -> jobReader.origin(reference)).body()).isEqualTo("Evidencia exacta del trabajo");
        as(author, () -> jobContent.submit(changed)); approve(changed, "GENERAL");
        assertThatThrownBy(() -> as(counterpart, () -> jobReader.origin(reference))).isInstanceOf(ModerationFailure.class);
        assertThat(as(author, () -> jobReader.origin(reference)).reference()).isEqualTo(reference);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "REVOKED", "RELINKED", "LEFT", "RESTRICTED"})
    void cnOriginalSelectedSideRetainsOnlyItsExistingAudienceAndNoSuccessorTransfer(String change) {
        var fixture = guardianJourney(true);
        UUID successor = adult(false), stranger = adult(false);
        switch (change) {
            case "BLOCK" -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
            case "REVOKED", "RELINKED" -> {
                as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                if (change.equals("RELINKED")) { link(successor, fixture.child()); }
            }
            case "LEFT" -> assertThatThrownBy(() -> as(fixture.guardian(), () -> { conversations.leave(fixture.thread(), new SubjectId(fixture.child())); return null; })).isInstanceOf(ConversationFailure.class);
            case "RESTRICTED" -> as(administrator, () -> { admin.restriction(administrator, fixture.guardian(), true); return null; });
            default -> throw new IllegalArgumentException();
        }
        var quote = jobs.discover(fixture.job()).agreement().orElseThrow();
        if (change.equals("RESTRICTED")) {
            assertThatThrownBy(() -> as(fixture.guardian(), () -> messageReader.origin(fixture.message()))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> as(fixture.guardian(), () -> agreementReader.origin(quote))).isInstanceOf(RuntimeException.class);
        } else {
            assertThat(as(fixture.guardian(), () -> messageReader.origin(fixture.message())).visible()).isTrue();
            assertThat(as(fixture.guardian(), () -> agreementReader.origin(quote)).revision()).isPresent();
        }
        for (UUID denied : List.of(fixture.child(), successor, stranger, administrator)) {
            assertThatThrownBy(() -> as(denied, () -> messageReader.origin(fixture.message()))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> as(denied, () -> agreementReader.origin(quote))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void cnUnknownLabelAndBindingFailureAreNotHiddenStatusOrDatabaseEffects() {
        var fixture = negotiate();
        var reference = jobReference(fixture.provider(), fixture.job().id(), "SUPPORT_NOTE");
        var before = effects();
        ModerationService target = AopTestUtils.getUltimateTargetObject(originMetadata);
        doAnswer(invocation -> {
            Optional<ModerationAccess.Approval> found = (Optional<ModerationAccess.Approval>) invocation.callRealMethod();
            return found.map(value -> new ModerationAccess.Approval(value.reference(), value.digest(), value.state(), "UNKNOWN", value.reason()));
        }).when(target).findApprovalForUpdate(reference);
        try { assertThatThrownBy(() -> as(fixture.provider(), () -> jobReader.origin(reference))).isInstanceOf(IllegalStateException.class).hasMessage("Clasificación desconocida"); }
        finally { reset(target); }
        assertThatThrownBy(() -> as(fixture.provider(), () -> jobReader.origin(new ContentReference(SurfaceKind.COMPLETED_JOB_REVIEW, reference.resourceId(), reference.revisionId())))).isInstanceOf(ModerationFailure.class);
        assertThat(effects()).isEqualTo(before);
    }

    @Test
    void cnUnselectedLeftThreadHasNoRetainedOriginFallback() {
        var fixture = guardianJourney(false);
        as(fixture.guardian(), () -> { conversations.leave(fixture.thread(), new SubjectId(fixture.child())); return null; });
        assertThatThrownBy(() -> as(fixture.guardian(), () -> messageReader.origin(fixture.message()))).isInstanceOf(ConversationFailure.class);
    }

    @ParameterizedTest
    @CsvSource({"RECALL,false", "RECALL,true", "REVOKED,false", "REVOKED,true", "RESTRICTED,false", "RESTRICTED,true"})
    void cnExactRetainedOriginSerializesWithCurrentChangesInBothOrders(String change, boolean changeFirst) throws Exception {
        var fixture = guardianJourney(true);
        UUID recallCase = change.equals("RECALL") ? reviewCase(fixture.message()) : null;
        Supplier<Object> mutation = () -> {
            switch (change) {
                case "RECALL" -> recall(recallCase, "GENERAL");
                case "REVOKED" -> as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                case "RESTRICTED" -> as(administrator, () -> { admin.restriction(administrator, fixture.guardian(), true); return null; });
                default -> throw new IllegalArgumentException();
            }
            return null;
        };
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        MessageReader target = AopTestUtils.getUltimateTargetObject(messageReader);
        if (!changeFirst) {
            doAnswer(invocation -> {
                Object value = invocation.callRealMethod();
                held.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Origin lock release timed out"); }
                return value;
            }).when(target).historical(fixture.message(), new SubjectId(fixture.child()));
        }
        Supplier<Object> observation = () -> as(fixture.guardian(), () -> messageReader.origin(fixture.message()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> {
                    if (!changeFirst) { return observation.get(); }
                    return tx(() -> {
                        mutation.get(); held.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Mutation lock release timed out"); } }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                        return null;
                    });
                });
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> { entered.countDown(); return (changeFirst ? observation : mutation).get(); });
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                awaitOriginDatabaseLock();
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                if (changeFirst) {
                    var assertion = assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                    if (change.equals("REVOKED")) { assertion.hasCauseInstanceOf(ar.changeo.identity.api.IdentityDiscoveryChanged.class); }
                }
                else { second.get(10, TimeUnit.SECONDS); }
            } finally { release.countDown(); reset(target); }
        }
        if (change.equals("REVOKED")) { assertThat(as(fixture.guardian(), () -> messageReader.origin(fixture.message())).visible()).isTrue(); }
        else { assertThatThrownBy(() -> as(fixture.guardian(), () -> messageReader.origin(fixture.message()))).isInstanceOf(ConversationFailure.class); }
    }

    void awaitOriginDatabaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0", Integer.class) > 0) { return; }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected actual PostgreSQL blocking relation for origin");
    }

    void assertOriginLink(ContentReference reference, String owner) {
        assertThat(originOwners.require(reference).caseOrigin(reference)).contains("/" + owner + "/origin?kind=" + reference.ownerKind()
                + "&resource=" + reference.resourceId() + "&revision=" + reference.revisionId());
    }

    ContentReference jobReference(UUID actor, UUID job, String kind) {
        var reference = as(actor, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), job, Optional.empty(), 0, new SubjectId(actor), kind,
                "Evidencia exacta del trabajo", kind.equals("REVIEW") ? 4 : null, "GENERAL", List.of())));
        as(actor, () -> jobContent.submit(reference)); approve(reference, "GENERAL");
        return reference;
    }

    Map<String, String> effects() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename<>'flyway_schema_history' order by tablename", String.class)) {
            if (!table.matches("[a-z_]+")) { throw new IllegalStateException("Unexpected test table"); }
            result.put(table, jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(t)::text,',' order by row_to_json(t)::text),'')) from " + table + " t", String.class));
        }
        return Map.copyOf(result);
    }
}
