package ar.changeo.conversations;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.api.SubjectId;
import ar.changeo.identity.api.ActorId;
import ar.changeo.marketplace.PresenceType;
import ar.changeo.moderation.ModerationService;
import ar.changeo.moderation.api.*;
import ar.changeo.notifications.api.NoticeOwner;
import ar.changeo.jobs.JobContentService;
import ar.changeo.payments.api.JobMoneyStatus;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {ChangeoApplication.class, ConversationJourneyPostgresTest.Configuration.class}, properties = {
        "spring.flyway.schemas=notification_usability_test", "spring.flyway.default-schema=notification_usability_test",
        "spring.jpa.properties.hibernate.default_schema=notification_usability_test", "spring.datasource.hikari.schema=notification_usability_test",
        "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false",
        "changeo.notification-worker=false", "changeo.synthetic-payments=false", "changeo.synthetic-subscriptions=false",
        "changeo.mfa-key=.runtime/conversations-test-mfa.key", "changeo.files-root=.runtime/conversations-test-artifacts"})
class NotificationUsabilityPostgresTest extends ConversationJourneyPostgresTest {
    @MockitoSpyBean JdbcTemplate noticeJdbc;
    @MockitoSpyBean ModerationService noticeMetadata;
    @Autowired List<NoticeOwner> noticeOwners;

    @Test
    void shouldPropagateRealDatabaseFailureInsteadOfReturningAnEmptyNoticeList() {
        var fixture = guardianJourney(false);
        MessageReader target = AopTestUtils.getUltimateTargetObject(messageReader);
        doAnswer(invocation -> { jdbc.execute("select 1/0"); return false; })
                .when(target).available(eq(fixture.message()), any());
        try {
            assertThatThrownBy(() -> as(fixture.guardian(), () -> notices.own())).isInstanceOf(DataAccessException.class);
        } finally { reset(target); }
    }

    @Test
    void shouldRetainExactlyTwoIdOnlyIntentsAcrossDuplicateApprovalAndLocalDelivery() {
        var fixture = guardianJourney(false);
        UUID caseId = reviewCase(fixture.message());
        as(administrator, () -> { cases.act(UUID.randomUUID(), caseId, 0, "CORRECT_LABEL", "GENERAL", "MISLABEL"); return null; });
        worker.process(claim(fixture.message(), "ACTIVATE"));
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where revision_id=?", Integer.class, fixture.message().revisionId())).isEqualTo(2);
        assertThat(as(fixture.guardian(), () -> notices.own())).singleElement().satisfies(notice -> {
            assertThat(notice.reference()).isEqualTo(fixture.message());
            assertThat(notice.state()).isEqualTo("PENDING");
            assertThat(notice.toString()).doesNotContain("Historial para una carrera real");
        });
        assertThat(notices.runOnce()).isTrue();
        assertThat(notices.runOnce()).isTrue();
        assertThat(notices.runOnce()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from notifications_local_delivery", Integer.class)).isEqualTo(2);
        assertThat(as(fixture.guardian(), () -> notices.own())).singleElement().satisfies(notice -> assertThat(notice.state()).isEqualTo("DELIVERED"));
    }

    @Test
    void shouldKeepEarlierEligibleNoticeReachableWhenNewerCandidatesAreSuppressed() {
        UUID customer = adult(false), provider = adult(true), otherProvider = adult(true);
        var earlierOffer = listing(provider, PresenceType.OFFER);
        UUID earlierThread = as(customer, () -> conversations.respond(earlierOffer.resourceId(), new SubjectId(customer), ar.changeo.identity.api.JobContext.FulfillmentMode.REMOTE));
        var earlier = as(provider, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(earlierThread), Optional.empty(),
                new SubjectId(provider), "PRIVATE_MESSAGE", "Aviso anterior disponible", "GENERAL", List.of())));
        as(provider, () -> messages.submit(earlier)); approve(earlier, "GENERAL");
        var newerOffer = listing(otherProvider, PresenceType.OFFER);
        UUID newerThread = as(customer, () -> conversations.respond(newerOffer.resourceId(), new SubjectId(customer), ar.changeo.identity.api.JobContext.FulfillmentMode.REMOTE));
        for (int index = 0; index < 21; index++) {
            clock.advance(61);
            var reference = as(otherProvider, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0, Optional.of(newerThread), Optional.empty(),
                    new SubjectId(otherProvider), "PRIVATE_MESSAGE", "Aviso más reciente", "GENERAL", List.of())));
            as(otherProvider, () -> messages.submit(reference)); approve(reference, "GENERAL");
        }
        as(customer, () -> { conversations.block(newerThread, new SubjectId(customer), true); return null; });
        var first = as(customer, () -> notices.own(Optional.empty()));
        assertThat(first.notices()).isEmpty();
        assertThat(first.next()).isPresent();
        var second = as(customer, () -> notices.own(first.next()));
        assertThat(second.notices()).extracting(ar.changeo.notifications.NotificationService.Notice::reference).containsExactly(earlier);
        assertThat(second.next()).isEmpty();
    }

    @Test
    void shouldRejectBothAmbientListEntryPointsBeforeAnyQueryOrGuard() {
        UUID actor = adult(false);
        clearInvocations(noticeJdbc, messageReader);
        assertThatThrownBy(() -> tx(() -> as(actor, () -> notices.own())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx(() -> as(actor, () -> notices.own(Optional.of(UUID.randomUUID())))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(noticeJdbc, messageReader);
    }

    @Test
    void shouldPageEqualTimestampTiesWithoutDuplicatesAndRejectForeignOrMissingAnchor() {
        var fixture = guardianJourney(false);
        var timestamp = clock.instant();
        for (int index = 0; index < 20; index++) {
            clock.now.set(timestamp.plusSeconds(61L * (index + 1)));
            var reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                    Optional.of(fixture.thread()), Optional.empty(), new SubjectId(fixture.provider()), "PROPOSAL", "Propuesta sintética", "GENERAL", List.of())));
            as(fixture.provider(), () -> messages.submit(reference));
            UUID caseId = reviewCase(reference);
            as(administrator, () -> { cases.act(UUID.randomUUID(), caseId, 0, "APPROVE", "GENERAL", "SAFETY"); return null; });
            clock.now.set(timestamp);
            worker.process(claim(reference, "ACTIVATE"));
        }
        clock.now.set(timestamp.plusSeconds(61L * 22));
        var expected = jdbc.queryForList("select id from notifications_intent where recipient_id=? order by created_at desc,id", UUID.class, fixture.guardian());
        var first = as(fixture.guardian(), () -> notices.own(Optional.empty()));
        var second = as(fixture.guardian(), () -> notices.own(first.next()));
        assertThat(first.notices()).hasSize(20);
        assertThat(first.next()).contains(expected.get(19));
        assertThat(second.notices()).hasSize(1);
        assertThat(second.next()).isEmpty();
        List<UUID> visited = new ArrayList<>();
        first.notices().forEach(notice -> visited.add(notice.id()));
        second.notices().forEach(notice -> visited.add(notice.id()));
        assertThat(visited).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        UUID foreign = jdbc.queryForObject("select id from notifications_intent where recipient_id=? limit 1", UUID.class, fixture.provider());
        for (UUID denied : List.of(foreign, UUID.randomUUID())) {
            assertThatThrownBy(() -> as(fixture.guardian(), () -> notices.own(Optional.of(denied))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Referencia de avisos no disponible");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "REVOKED", "RELINKED", "LEFT", "RESTRICTED", "SENSITIVE", "ADULT"})
    void shouldSuppressCurrentDenialWithoutTransferringSelectedHistory(String change) {
        var fixture = guardianJourney(!change.equals("LEFT"));
        UUID successor = adult(false), stranger = adult(false);
        assertThat(available(fixture.message(), fixture.guardian())).isTrue();
        for (UUID denied : List.of(fixture.child(), successor, stranger, administrator)) {
            assertThat(available(fixture.message(), denied)).isFalse();
        }
        switch (change) {
            case "BLOCK" -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
            case "REVOKED", "RELINKED" -> {
                as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                if (change.equals("RELINKED")) { link(successor, fixture.child()); }
            }
            case "LEFT" -> as(fixture.guardian(), () -> { conversations.leave(fixture.thread(), new SubjectId(fixture.child())); return null; });
            case "RESTRICTED" -> as(administrator, () -> { admin.restriction(administrator, fixture.guardian(), true); return null; });
            case "SENSITIVE", "ADULT" -> {
                UUID caseId = reviewCase(fixture.message());
                as(administrator, () -> { cases.act(UUID.randomUUID(), caseId, 0, "CORRECT_LABEL", change, "MISLABEL"); return null; });
                worker.process(claim(fixture.message(), "ACTIVATE"));
                if (change.equals("SENSITIVE")) { accounts.preferences(fixture.guardian(), false, true); }
            }
            default -> throw new IllegalArgumentException();
        }
        assertThat(available(fixture.message(), fixture.guardian())).isFalse();
        assertThat(available(fixture.message(), successor)).isFalse();
        if (Set.of("BLOCK", "REVOKED", "RELINKED").contains(change)) {
            assertThat(as(fixture.guardian(), () -> messageReader.historical(fixture.message(), new SubjectId(fixture.child())))).isPresent();
            assertThat(as(fixture.guardian(), () -> files.read(fixture.file(), ar.changeo.files.api.FileAccess.Purpose.ORDINARY)).bytes())
                    .isEqualTo("synthetic:clear".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_MESSAGE", "PROPOSAL", "QUESTION", "COMMENT"})
    void shouldSeparatePublicContentFromNoticesAndPreserveOldApprovalDuringPendingEdit(String kind) {
        var fixture = negotiate();
        boolean discussion = kind.equals("QUESTION") || kind.equals("COMMENT");
        int before = jdbc.queryForObject("select count(*) from notifications_intent", Integer.class);
        var reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                discussion ? Optional.empty() : Optional.of(fixture.job().conversation().id()), discussion ? Optional.of(fixture.job().conversation().listing().resourceId()) : Optional.empty(),
                new SubjectId(fixture.provider()), kind, "Contenido exacto sintético", "GENERAL", List.of())));
        as(fixture.provider(), () -> messages.submit(reference)); approve(reference, "GENERAL");
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent", Integer.class)).isEqualTo(before + (discussion ? 0 : 2));
        if (discussion) { return; }
        assertThat(available(reference, fixture.customer())).isTrue();
        long version = as(fixture.provider(), () -> messageReader.ownResource(reference.resourceId(), new SubjectId(fixture.provider()))).version();
        var edit = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), version,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), kind, "Edición todavía privada", "GENERAL", List.of())));
        assertThat(available(reference, fixture.customer())).isTrue();
        assertThatThrownBy(() -> available(edit, fixture.customer())).isInstanceOf(RuntimeException.class);
        as(fixture.provider(), () -> messages.submit(edit)); approve(edit, "GENERAL");
        assertThat(available(reference, fixture.customer())).isFalse();
        assertThat(available(edit, fixture.customer())).isTrue();
        UUID recallCase = reviewCase(edit); recall(recallCase, "GENERAL");
        assertThat(available(edit, fixture.customer())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROGRESS", "FEEDBACK", "FINAL_DELIVERY", "SUPPORT_NOTE", "REVIEW"})
    void shouldKeepAllFiveJobKindsBoundToExactFundingAndApproval(String kind) {
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
        clearInvocations(fundingFacts);
        assertThat(available(reference, fixture.customer())).isTrue();
        assertThat(available(reference, fixture.provider())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where revision_id=?", Integer.class, reference.revisionId())).isEqualTo(2);
        if (kind.equals("SUPPORT_NOTE")) {
            verify(fundingFacts, never()).currentForUpdate(any(), any());
            return;
        }
        fundingFacts.facts.remove(fixture.job().id());
        assertThat(available(reference, fixture.customer())).isFalse();
        assertThatThrownBy(() -> as(fixture.customer(), () -> jobReader.view(reference, new SubjectId(fixture.customer()))))
                .isInstanceOf(ar.changeo.moderation.ModerationFailure.class)
                .hasMessage("Integración financiera pendiente: la ejecución requiere financiación completa exacta");
        var target = AopTestUtils.<TestFundingFacts>getUltimateTargetObject(fundingFacts);
        try {
            doReturn(new JobMoneyStatus(fixture.job().id(), fixture.quote(), JobMoneyStatus.Funding.UNKNOWN, JobMoneyStatus.Release.UNKNOWN, JobMoneyStatus.BankPayout.UNKNOWN))
                    .when(target).currentForUpdate(fixture.job().id(), fixture.quote());
            assertThat(available(reference, fixture.customer())).isFalse();
            doReturn(new JobMoneyStatus(fixture.job().id(), fixture.quote(), JobMoneyStatus.Funding.CONFIRMED_REMAINING, JobMoneyStatus.Release.NOT_RELEASED, JobMoneyStatus.BankPayout.NOT_PAID))
                    .when(target).currentForUpdate(fixture.job().id(), fixture.quote());
            assertThat(available(reference, fixture.customer())).isTrue();
            doReturn(new JobMoneyStatus(UUID.randomUUID(), fixture.quote(), JobMoneyStatus.Funding.CONFIRMED_FULL, JobMoneyStatus.Release.NOT_RELEASED, JobMoneyStatus.BankPayout.NOT_PAID))
                    .when(target).currentForUpdate(fixture.job().id(), fixture.quote());
            assertThatThrownBy(() -> available(reference, fixture.customer())).isInstanceOf(IllegalArgumentException.class).hasMessage("Estado financiero ajeno al acuerdo");
            doReturn(null).when(target).currentForUpdate(fixture.job().id(), fixture.quote());
            assertThatThrownBy(() -> available(reference, fixture.customer())).isInstanceOf(NullPointerException.class);
            doAnswer(invocation -> { jdbc.execute("select 1/0"); return null; }).when(target).currentForUpdate(fixture.job().id(), fixture.quote());
            assertThatThrownBy(() -> available(reference, fixture.customer())).isInstanceOf(DataAccessException.class);
        } finally { reset(target); }
    }

    @ParameterizedTest
    @CsvSource({"PRIVATE_MESSAGE,LABEL", "PRIVATE_MESSAGE,STATE", "PRIVATE_MESSAGE,DIGEST", "QUOTE_DESCRIPTION,LABEL", "QUOTE_DESCRIPTION,STATE", "QUOTE_DESCRIPTION,DIGEST",
            "SUPPORT_NOTE,LABEL", "SUPPORT_NOTE,STATE", "SUPPORT_NOTE,DIGEST", "SUPPORT_NOTE,TERMS_LABEL", "SUPPORT_NOTE,TERMS_STATE", "SUPPORT_NOTE,TERMS_DIGEST"})
    @SuppressWarnings("unchecked")
    void shouldPropagateUnknownMetadataAndPoisonCaughtParticipatingFault(String kind, String field) {
        var fixture = negotiate();
        var reference = noticeReference(fixture, kind);
        var observed = kind.equals("SUPPORT_NOTE") ? Set.of(reference, fixture.quote()) : Set.of(reference);
        var changed = field.startsWith("TERMS_") ? fixture.quote() : reference;
        var target = AopTestUtils.<ModerationService>getUltimateTargetObject(noticeMetadata);
        doAnswer(invocation -> {
            Map<ContentReference, ModerationAccess.Approval> original = (Map<ContentReference, ModerationAccess.Approval>) invocation.callRealMethod();
            var approval = original.get(changed);
            Map<ContentReference, ModerationAccess.Approval> result = new HashMap<>(original);
            result.put(changed, new ModerationAccess.Approval(approval.reference(), field.endsWith("DIGEST") ? "0".repeat(64) : approval.digest(),
                    field.endsWith("STATE") ? "UNKNOWN" : approval.state(), field.endsWith("LABEL") ? "UNKNOWN" : approval.label(), approval.reason()));
            return Map.copyOf(result);
        }).when(target).approvalsForUpdate(observed);
        try {
            assertThatThrownBy(() -> available(reference, fixture.customer())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> tx(() -> {
                try { noticeOwner(reference).available(reference, new ActorId(fixture.customer())); }
                catch (RuntimeException failure) { }
                return null;
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        } finally { reset(target); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_MESSAGE", "QUOTE_DESCRIPTION", "SUPPORT_NOTE"})
    void shouldPropagateRealSqlFailureFromEveryInstalledNoticeOwner(String kind) {
        var fixture = negotiate();
        var reference = noticeReference(fixture, kind);
        var observed = kind.equals("SUPPORT_NOTE") ? Set.of(reference, fixture.quote()) : Set.of(reference);
        var target = AopTestUtils.<ModerationService>getUltimateTargetObject(noticeMetadata);
        doAnswer(invocation -> { jdbc.execute("select 1/0"); return null; }).when(target).approvalsForUpdate(observed);
        try {
            assertThatThrownBy(() -> available(reference, fixture.customer())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> as(fixture.customer(), () -> notices.own())).isInstanceOf(DataAccessException.class);
        } finally { reset(target); }
    }

    @Test
    void shouldPropagateUnknownExactReferenceInsteadOfHidingIt() {
        var fixture = guardianJourney(false);
        var unknown = new ContentReference(SurfaceKind.PRIVATE_MESSAGE, fixture.message().resourceId(), UUID.randomUUID());
        tx(() -> { notices.enqueue(unknown, Set.of(new ActorId(fixture.guardian()))); return null; });
        assertThatThrownBy(() -> as(fixture.guardian(), () -> notices.own())).isInstanceOf(ConversationFailure.class);
        assertThat(jdbc.queryForObject("select count(*) from notifications_local_delivery", Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"BLOCK,false", "BLOCK,true", "RECALL,false", "RECALL,true", "RESTRICTED,false", "RESTRICTED,true", "LEFT,false", "LEFT,true", "GUARDIAN,false", "GUARDIAN,true"})
    void shouldSerializeCurrentAvailabilityAndChangesInBothOrders(String change, boolean changeFirst) throws Exception {
        var fixture = guardianJourney(false);
        UUID caseId = change.equals("RECALL") ? reviewCase(fixture.message()) : null;
        Supplier<Object> mutation = () -> {
            switch (change) {
                case "BLOCK" -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
                case "RECALL" -> recall(caseId, "GENERAL");
                case "RESTRICTED" -> as(administrator, () -> { admin.restriction(administrator, fixture.guardian(), true); return null; });
                case "LEFT" -> as(fixture.guardian(), () -> { conversations.leave(fixture.thread(), new SubjectId(fixture.child())); return null; });
                case "GUARDIAN" -> as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                default -> throw new IllegalArgumentException();
            }
            return null;
        };
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var target = AopTestUtils.<MessageReader>getUltimateTargetObject(messageReader);
        if (!changeFirst) {
            doAnswer(invocation -> { var result = invocation.callRealMethod(); held.countDown(); await(release); return result; })
                    .when(target).available(fixture.message(), new ActorId(fixture.guardian()));
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> {
                    if (!changeFirst) { return available(fixture.message(), fixture.guardian()); }
                    return tx(() -> { mutation.get(); held.countDown(); await(release); return null; });
                });
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> { entered.countDown(); return changeFirst ? available(fixture.message(), fixture.guardian()) : mutation.get(); });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                awaitNoticeDatabaseLock();
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(changeFirst ? null : true);
                if (changeFirst && Set.of("RECALL", "LEFT").contains(change)) {
                    assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ConversationFailure.class);
                } else if (changeFirst && change.equals("GUARDIAN")) {
                    assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ar.changeo.identity.api.IdentityDiscoveryChanged.class);
                } else if (changeFirst) { assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(false); }
                else { second.get(5, TimeUnit.SECONDS); }
            } finally { release.countDown(); reset(target); }
        }
        assertThat(available(fixture.message(), fixture.guardian())).isFalse();
    }

    void awaitNoticeDatabaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0", Integer.class) > 0) { return; }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected an actual PostgreSQL blocking relation");
    }

    ContentReference jobReference(UUID actor, UUID job, String kind) {
        var reference = as(actor, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), job, Optional.empty(), 0,
                new SubjectId(actor), kind, "Evidencia exacta sintética", kind.equals("REVIEW") ? 4 : null, "GENERAL", List.of())));
        as(actor, () -> jobContent.submit(reference)); approve(reference, "GENERAL");
        return reference;
    }

    ContentReference noticeReference(Negotiated fixture, String kind) {
        return switch (kind) {
            case "QUOTE_DESCRIPTION" -> fixture.quote();
            case "SUPPORT_NOTE" -> jobReference(fixture.provider(), fixture.job().id(), kind);
            case "PRIVATE_MESSAGE" -> {
                var reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                        Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), kind, "Aviso privado exacto", "GENERAL", List.of())));
                as(fixture.provider(), () -> messages.submit(reference)); approve(reference, "GENERAL");
                yield reference;
            }
            default -> throw new IllegalArgumentException();
        };
    }

    boolean available(ContentReference reference, UUID recipient) {
        return tx(() -> noticeOwner(reference).available(reference, new ActorId(recipient)));
    }

    NoticeOwner noticeOwner(ContentReference reference) {
        return noticeOwners.stream().filter(owner -> owner.noticeKinds().contains(reference.ownerKind())).findFirst().orElseThrow();
    }
}
