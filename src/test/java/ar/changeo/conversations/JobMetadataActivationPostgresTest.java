package ar.changeo.conversations;

import ar.changeo.identity.api.SubjectId;
import ar.changeo.jobs.JobContentService;
import ar.changeo.marketplace.MarketplaceService;
import ar.changeo.moderation.api.ContentReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import ar.changeo.files.api.FileAccess;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobSnapshot;
import ar.changeo.moderation.ModerationWorker;
import ar.changeo.moderation.api.ContentOwner.ActivationLifecycle;
import ar.changeo.moderation.api.SurfaceKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobMetadataActivationPostgresTest extends ConversationJourneyPostgresTest {
    @DynamicPropertySource static void jobMetadataSchema(DynamicPropertyRegistry properties) {
        String schema = "job_metadata_activation_test";
        properties.add("spring.flyway.schemas", () -> schema);
        properties.add("spring.flyway.default-schema", () -> schema);
        properties.add("spring.datasource.hikari.schema", () -> schema);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }
    @Test void shouldReaffirmActiveSupportNoteAfterBlockWithoutDomainEffects() {
        var fixture = negotiate();
        var reference = active(fixture.customer(), fixture.customer(), fixture.job().id(), "SUPPORT_NOTE");
        correct(reference, "SENSITIVE");
        as(fixture.customer(), () -> { conversations.block(fixture.job().conversation().id(), new SubjectId(fixture.customer()), true); return null; });
        var before = effects();
        var activation = claim(reference, "ACTIVATE");
        worker.process(activation);
        assertThat(jdbc.queryForMap("select state,reason from moderation_task where id=?", activation.id()))
                .containsEntry("state", "COMPLETE").containsEntry("reason", "ACTIVATED");
        assertThat(moderation.approval(reference).state()).isEqualTo("APPROVED");
        assertThat(effects()).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"PROGRESS,false", "PROGRESS,true", "FEEDBACK,false", "FEEDBACK,true", "FINAL_DELIVERY,false", "FINAL_DELIVERY,true", "SUPPORT_NOTE,false", "SUPPORT_NOTE,true", "REVIEW,false", "REVIEW,true"})
    void shouldNeverRestoreAnyKindWhenRecallAndQueuedCorrectionCompete(String kind, boolean recallFirst) {
        var active = legitimateKind(kind); var reference = active.reference();
        correct(reference, "SENSITIVE");
        UUID caseId = reviewCase(reference); var activation = claim(reference, "ACTIVATE");
        if (recallFirst) {
            recall(caseId, "GENERAL"); var before = effects(); worker.process(activation);
            assertThat(effects()).isEqualTo(before);
            assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo("SUPERSEDED");
            assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
        } else {
            var before = effects(); worker.process(activation); activated(activation); assertThat(effects()).isEqualTo(before);
            recall(caseId, "GENERAL"); var recalled = effects(); worker.process(activation); assertThat(effects()).isEqualTo(recalled);
        }
        assertThat(jdbc.queryForObject("select approved_revision from jobs_authored where id=?", UUID.class, reference.resourceId())).isNull();
        assertThat(moderation.approval(reference).state()).isEqualTo("RECALLED");
        assertThatThrownBy(() -> correct(reference, "GENERAL")).isInstanceOf(ar.changeo.moderation.ModerationFailure.class);
    }

    @MockitoSpyBean ParticipantIdentityAccess activationIdentity;
    @MockitoSpyBean FileAccess activationFiles;

    @ParameterizedTest @CsvSource({"PROGRESS,false", "PROGRESS,true", "FEEDBACK,false", "FEEDBACK,true", "FINAL_DELIVERY,false", "FINAL_DELIVERY,true", "SUPPORT_NOTE,false", "SUPPORT_NOTE,true", "REVIEW,false", "REVIEW,true"})
    void shouldReaffirmEveryLegitimateKindInBothCorrectionBlockOrders(String kind, boolean blockFirst) {
        var active = legitimateKind(kind);
        var fixture = active.fixture(); var reference = active.reference();
        Runnable block = () -> as(fixture.customer(), () -> { conversations.block(fixture.job().conversation().id(), new SubjectId(fixture.customer()), true); return null; });
        if (blockFirst) { block.run(); }
        correct(reference, "SENSITIVE");
        if (!blockFirst) { block.run(); }
        var before = effects();
        clearInvocations(fundingFacts);
        var activation = claim(reference, "ACTIVATE");
        worker.process(activation); worker.process(activation);
        activated(activation);
        assertThat(effects()).isEqualTo(before);
        verify(fundingFacts, never()).currentForUpdate(any(), any());
        assertThat(jdbc.queryForObject("select version from moderation_signal where decision_id=?", Long.class, activation.decision()))
                .isEqualTo(jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, reference.resourceId()));
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"RESTRICTION", "REVOKED", "RELINKED"})
    void shouldReaffirmOriginalGuardianNoteWithoutTransferringHistoryOrBytes(String change) {
        var fixture = guardianJourney(true);
        var reference = active(fixture.guardian(), fixture.child(), fixture.job(), "SUPPORT_NOTE");
        var file = as(fixture.guardian(), () -> jobReader.historical(reference, new SubjectId(fixture.child()))).orElseThrow().attachments().getFirst();
        UUID successor = adult(false);
        if (change.equals("RESTRICTION")) { as(administrator, () -> { admin.restriction(administrator, fixture.guardian(), true); return null; }); }
        else {
            as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
            if (change.equals("RELINKED")) { link(successor, fixture.child()); }
        }
        correct(reference, "SENSITIVE");
        var before = effects();
        var activation = claim(reference, "ACTIVATE"); worker.process(activation); activated(activation);
        assertThat(effects()).isEqualTo(before);
        if (change.equals("RESTRICTION")) {
            assertThat(as(fixture.guardian(), () -> jobReader.historical(reference, new SubjectId(fixture.child())))).isEmpty();
            assertThatThrownBy(() -> as(fixture.guardian(), () -> files.read(file, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        } else {
            assertThat(as(fixture.guardian(), () -> files.read(file, FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        }
        for (UUID denied : List.of(fixture.child(), successor, adult(false))) {
            assertThatThrownBy(() -> as(denied, () -> jobReader.historical(reference, new SubjectId(fixture.child())))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> as(denied, () -> files.read(file, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> as(fixture.guardian(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job(), Optional.empty(), 0,
                new SubjectId(fixture.child()), "SUPPORT_NOTE", "Nueva acción denegada", null, "GENERAL", List.of())))).isInstanceOf(RuntimeException.class);
    }

    @Test void shouldSettleAdultMetadataButRetainCurrentAudienceAndExactSafeByteChecks() {
        var fixture = negotiate();
        var reference = active(fixture.provider(), fixture.provider(), fixture.job().id(), "SUPPORT_NOTE");
        var file = as(fixture.customer(), () -> jobReader.view(reference, new SubjectId(fixture.customer()))).attachments().getFirst();
        accounts.preferences(fixture.customer(), false, false);
        var before = effects();
        correct(reference, "ADULT"); var activation = claim(reference, "ACTIVATE"); worker.process(activation); activated(activation);
        assertThat(as(fixture.customer(), () -> jobReader.historical(reference, new SubjectId(fixture.customer())))).isEmpty();
        assertThatThrownBy(() -> as(fixture.customer(), () -> files.read(file, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        accounts.preferences(fixture.customer(), true, true);
        assertThat(as(fixture.customer(), () -> files.read(file, FileAccess.Purpose.ORDINARY)).bytes()).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        assertThat(effects()).isEqualTo(before);
        recall(reviewCase(reference), "GENERAL");
        assertThatThrownBy(() -> as(fixture.customer(), () -> files.read(file, FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        assertThat(jobOwner.activationLifecycle(reference)).isEqualTo(ActivationLifecycle.DELIVERY);
    }

    @Test void shouldNotReselectFinalRestartDeadlineOrResumeSupportWhenFundingChanges() {
        var fixture = negotiate(); start(fixture);
        var reference = active(fixture.provider(), fixture.provider(), fixture.job().id(), "FINAL_DELIVERY");
        var selected = jobs.discover(fixture.job().id());
        as(fixture.customer(), () -> { support.request(UUID.randomUUID(), selected.id(), selected.version(), new SubjectId(fixture.customer()), true); return null; });
        fundingFacts.facts.remove(fixture.job().id());
        var before = effects();
        correct(reference, "SENSITIVE");
        clearInvocations(fundingFacts);
        var activation = claim(reference, "ACTIVATE"); worker.process(activation); activated(activation);
        assertThat(effects()).isEqualTo(before);
        verify(fundingFacts, never()).currentForUpdate(any(), any());
        assertThat(jobs.discover(selected.id()).state()).isEqualTo(JobSnapshot.State.SUPPORT_HOLD);
        assertThatThrownBy(() -> as(fixture.customer(), () -> execution.acceptFinal(UUID.randomUUID(), selected.id(), jobs.discover(selected.id()).version(), new SubjectId(fixture.customer()), reference))).isInstanceOf(RuntimeException.class);
        assertThat(effects()).isEqualTo(before);
    }

    @Test void shouldKeepOlderApprovedRevisionOrdinaryAndSupersedeItBeneathNewDraft() {
        var fixture = negotiate();
        var reference = active(fixture.provider(), fixture.provider(), fixture.job().id(), "SUPPORT_NOTE");
        long version = jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, reference.resourceId());
        var draft = as(fixture.provider(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.of(reference.resourceId()), version,
                new SubjectId(fixture.provider()), "SUPPORT_NOTE", "Borrador posterior", null, "GENERAL", List.of())));
        assertThat(jobOwner.activationLifecycle(reference)).isEqualTo(ActivationLifecycle.DELIVERY);
        assertThat(jobOwner.activationLifecycle(draft)).isEqualTo(ActivationLifecycle.DELIVERY);
        correct(reference, "SENSITIVE");
        var before = effects(); var activation = claim(reference, "ACTIVATE"); worker.process(activation);
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo("SUPERSEDED");
        assertThat(effects()).isEqualTo(before);
        assertThatThrownBy(() -> jobOwner.activationLifecycle(new ContentReference(SurfaceKind.COMPLETED_JOB_REVIEW, reference.resourceId(), reference.revisionId()))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"BLOCK", "RESTRICTION", "GUARDIAN"})
    void shouldNeverUseMetadataForAStillUndeliveredOrdinaryNote(String denial) {
        var fixture = guardianJourney(true);
        var reference = as(fixture.guardian(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job(), Optional.empty(), 0,
                new SubjectId(fixture.child()), "SUPPORT_NOTE", "Nueva entrega no activada", null, "GENERAL", List.of())));
        as(fixture.guardian(), () -> jobContent.submit(reference)); correct(reference, "GENERAL");
        switch (denial) {
            case "BLOCK" -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
            case "RESTRICTION" -> as(administrator, () -> { admin.restriction(administrator, fixture.guardian(), true); return null; });
            default -> as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
        }
        var before = effects(); var activation = claim(reference, "ACTIVATE"); worker.process(activation);
        assertThat(jdbc.queryForObject("select state from moderation_task where id=?", String.class, activation.id())).isEqualTo("FAILED");
        assertThat(effects()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"RECALLED", "CANONICAL"})
    void shouldRequireExactSelectedTermsApprovalEvenForAlreadyActiveMetadata(String failure) {
        var fixture = negotiate();
        var reference = active(fixture.provider(), fixture.provider(), fixture.job().id(), "SUPPORT_NOTE");
        correct(reference, "SENSITIVE");
        if (failure.equals("RECALLED")) { recall(reviewCase(fixture.quote()), "GENERAL"); }
        else { jdbc.update("update agreements_quote set approved_revision=null where id=?", fixture.quote().resourceId()); }
        var before = effects(); var activation = claim(reference, "ACTIVATE");
        if (failure.equals("CANONICAL")) {
            var task = jdbc.queryForMap("select * from moderation_task where id=?", activation.id());
            assertThatThrownBy(() -> worker.process(activation)).isInstanceOf(IllegalStateException.class).hasMessage("El presupuesto exacto no está aprobado");
            assertThat(jdbc.queryForMap("select * from moderation_task where id=?", activation.id())).isEqualTo(task);
        } else {
            worker.process(activation);
            assertThat(jdbc.queryForMap("select state,reason from moderation_task where id=?", activation.id())).containsEntry("state", "FAILED").containsEntry("reason", "BINDING");
        }
        assertThat(effects()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
        if (failure.equals("CANONICAL")) {
            jdbc.update("update agreements_quote set approved_revision=? where id=?", fixture.quote().revisionId(), fixture.quote().resourceId());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TOKEN", "LEASE", "GENERATION", "DECISION"})
    void shouldRejectStaleClaimsWithoutAnyMetadataOrDomainEffect(String change) {
        var fixture = negotiate();
        var reference = active(fixture.provider(), fixture.provider(), fixture.job().id(), "SUPPORT_NOTE");
        correct(reference, "SENSITIVE"); var original = claim(reference, "ACTIVATE");
        var activation = switch (change) {
            case "TOKEN" -> new ModerationWorker.Claim(original.id(), original.barrier(), original.submission(), original.kind(), original.generation(), UUID.randomUUID(), original.decision(), false);
            case "GENERATION" -> new ModerationWorker.Claim(original.id(), original.barrier(), original.submission(), original.kind(), original.generation()+1, original.token(), original.decision(), false);
            case "DECISION" -> new ModerationWorker.Claim(original.id(), original.barrier(), original.submission(), original.kind(), original.generation(), original.token(), UUID.randomUUID(), false);
            default -> original;
        };
        if (change.equals("LEASE")) { jdbc.update("update moderation_task set lease_until=? where id=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)), original.id()); }
        var task = jdbc.queryForMap("select * from moderation_task where id=?", original.id()); var before = effects();
        worker.process(activation);
        assertThat(jdbc.queryForMap("select * from moderation_task where id=?", original.id())).isEqualTo(task);
        assertThat(effects()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
    }

    @ParameterizedTest @CsvSource({"false,BLOCK", "true,BLOCK", "false,GUARDIAN", "true,GUARDIAN", "false,RECALL", "true,RECALL"})
    void shouldSerializeJobReaffirmationAndActualConflictingChanges(boolean changeFirst, String changeKind) throws Exception {
        var fixture = guardianJourney(true);
        var reference = active(fixture.guardian(), fixture.child(), fixture.job(), "SUPPORT_NOTE");
        correct(reference, "SENSITIVE");
        UUID caseId = changeKind.equals("RECALL") ? reviewCase(reference) : null;
        var activation = claim(reference, "ACTIVATE");
        var protectedFirst = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        if (!changeFirst) { doAnswer(invocation -> { var result = invocation.callRealMethod(); protectedFirst.countDown(); await(release); return result; }).when(jobOwner).activate(reference); }
        Runnable change = () -> {
            switch (changeKind) {
                case "RECALL" -> recall(caseId, "GENERAL");
                case "GUARDIAN" -> as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                default -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = changeFirst ? pool.submit(() -> tx(() -> { change.run(); protectedFirst.countDown(); await(release); return null; })) : pool.submit(() -> worker.process(activation));
                await(protectedFirst);
                var second = pool.submit(() -> { entered.countDown(); if (changeFirst) { worker.process(activation); } else { change.run(); } });
                await(entered);
                assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        } finally { reset(jobOwner); }
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo(changeFirst && changeKind.equals("RECALL") ? "SUPERSEDED" : "ACTIVATED");
        assertThat(jdbc.queryForObject("select version from jobs_authored where id=?", Long.class, reference.resourceId())).isEqualTo(changeKind.equals("RECALL") ? 3 : 2);
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where resource_id=?", Integer.class, reference.resourceId())).isEqualTo(2);
    }

    @Test void shouldRediscoverAndProtectOriginalSubjectAndSuccessorAfterGuardianMovement() throws Exception {
        var fixture = guardianJourney(true);
        var reference = active(fixture.guardian(), fixture.child(), fixture.job(), "SUPPORT_NOTE");
        correct(reference, "SENSITIVE"); var activation = claim(reference, "ACTIVATE"); var before = effects();
        UUID successor = adult(false);
        var observed = new CountDownLatch(1); var release = new CountDownLatch(1); var observations = new AtomicInteger();
        doAnswer(invocation -> { var result = invocation.callRealMethod(); if (observations.incrementAndGet() == 1) { observed.countDown(); await(release); } return result; }).when(jobOwner).activationLifecycle(reference);
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var process = pool.submit(() -> worker.process(activation)); await(observed);
                as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; }); link(successor, fixture.child());
                release.countDown(); process.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        } finally { reset(jobOwner); }
        activated(activation); assertThat(effects()).isEqualTo(before);
        assertThat(observations.get()).isEqualTo(6);
        verify(activationIdentity, atLeastOnce()).requireForUpdate(eq(PermissionRequest.self(new AccountId(fixture.guardian()), Capability.ACCESS_SUPPORT)),
                argThat(accounts -> accounts.containsAll(Set.of(new AccountId(fixture.guardian()), new AccountId(fixture.child()), new AccountId(successor)))));
    }

    @Test void shouldKeepGenuineOwnerOutageBudgetWithoutDiscoveryRefund() {
        var fixture = negotiate(); var reference = active(fixture.provider(), fixture.provider(), fixture.job().id(), "SUPPORT_NOTE");
        correct(reference, "SENSITIVE"); var before = effects();
        doThrow(new org.springframework.dao.CannotAcquireLockException("Synthetic owner outage")).when(jobOwner).activationLifecycle(reference);
        try {
            for (int attempt=1; attempt<=3; attempt++) {
                var activation = claim(reference, "ACTIVATE"); worker.process(activation);
                assertThat(jdbc.queryForMap("select state,reason,attempts from moderation_task where id=?", activation.id()))
                        .containsEntry("state", attempt<3 ? "RETRY_WAIT" : "FAILED").containsEntry("reason", "OUTAGE").containsEntry("attempts", attempt);
            }
        } finally { reset(jobOwner); }
        assertThat(effects()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"DIGEST", "FILES", "UNSAFE"})
    void shouldKeepExactCanonicalDigestAttachmentsAndSafeFilesRequired(String failure) {
        var fixture = negotiate(); var reference = active(fixture.provider(), fixture.provider(), fixture.job().id(), "SUPPORT_NOTE");
        var file = as(fixture.customer(), () -> jobReader.view(reference, new SubjectId(fixture.customer()))).attachments().getFirst();
        correct(reference, "SENSITIVE"); var before = effects();
        if (failure.equals("UNSAFE")) {
            var actual = files.metadata(file);
            doReturn(new FileAccess.Metadata(file, actual.mediaType(), actual.size(), false, "UNINSPECTABLE")).when(activationFiles).metadata(file);
        } else {
            doAnswer(invocation -> {
                var actual = (ar.changeo.moderation.ReviewInput) invocation.callRealMethod();
                return new ar.changeo.moderation.ReviewInput(reference, actual.text(), actual.declaredLabel(), failure.equals("DIGEST") ? "0".repeat(64) : actual.digest(), failure.equals("FILES") ? List.of() : actual.attachments());
            }).when(jobOwner).lockRevision(any(), eq(reference));
        }
        var activation = claim(reference, "ACTIVATE");
        try { worker.process(activation); } finally { reset(jobOwner, activationFiles); }
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo(failure.equals("UNSAFE") ? "UNINSPECTABLE" : "SUPERSEDED");
        assertThat(effects()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
    }

    @Test void shouldBoundRealGuardianDiscoveryChurnRefundOnceAndCompleteAfterStability() throws Exception {
        var fixture = guardianJourney(true);
        var reference = active(fixture.guardian(), fixture.child(), fixture.job(), "SUPPORT_NOTE");
        while (worker.processOne()) {}
        var successors = List.of(adult(false), adult(false), adult(false));
        correct(reference, "SENSITIVE"); var activation = worker.claim().orElseThrow();
        assertThat(activation.kind()).isEqualTo("ACTIVATE");
        var before = effects(); var approval = moderation.approval(reference);
        var observations = new AtomicInteger(); var discovered = new LinkedBlockingQueue<Integer>(); var release = new Semaphore(0);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod(); int count = observations.incrementAndGet();
            if (count<=5 && count%2==1) { discovered.add(count); assertThat(release.tryAcquire(10, TimeUnit.SECONDS)).isTrue(); }
            return result;
        }).when(jobOwner).activationLifecycle(reference);
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var processing = pool.submit(() -> worker.process(activation));
                UUID guardian = fixture.guardian(), relationship = fixture.relationship();
                for (int index=0; index<3; index++) {
                    assertThat(discovered.poll(10, TimeUnit.SECONDS)).isEqualTo(index*2+1);
                    UUID oldGuardian=guardian, oldRelationship=relationship;
                    as(oldGuardian, () -> { guardians.revoke(oldGuardian, oldRelationship, 1); return null; });
                    guardian=successors.get(index); relationship=link(guardian, fixture.child()); release.release();
                }
                processing.get(10, TimeUnit.SECONDS);
            } finally { release.release(3); }
        } finally { reset(jobOwner); }
        assertThat(observations.get()).isEqualTo(6);
        assertThat(jdbc.queryForMap("select state,reason,attempts,claim_token,lease_until from moderation_task where id=?", activation.id()))
                .containsEntry("state", "RETRY_WAIT").containsEntry("reason", "CHANGED").containsEntry("attempts", 0).containsEntry("claim_token", null).containsEntry("lease_until", null);
        assertThat(jdbc.queryForObject("select available_at from moderation_task where id=?", java.sql.Timestamp.class, activation.id()).toInstant()).isEqualTo(clock.instant().plusSeconds(5));
        assertThat(moderation.approval(reference)).isEqualTo(approval); assertThat(effects()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
        worker.process(activation);
        assertThat(jdbc.queryForObject("select attempts from moderation_task where id=?", Integer.class, activation.id())).isZero();
        assertThat(worker.claim()).isEmpty(); clock.advance(6);
        var retry = worker.claim().orElseThrow(); worker.process(retry); activated(retry);
        assertThat(effects()).isEqualTo(before);
    }

    @Test void shouldRestartStaleOrdinaryJobDiscoveryAfterCompetingActivationAndGuardianRevocation() throws Exception {
        var fixture = guardianJourney(true);
        var reference = as(fixture.guardian(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job(), Optional.empty(), 0,
                new SubjectId(fixture.child()), "SUPPORT_NOTE", "Entrega concurrente", null, "GENERAL", List.of())));
        as(fixture.guardian(), () -> jobContent.submit(reference)); correct(reference, "GENERAL");
        var original = claim(reference, "ACTIVATE"); var observed = new CountDownLatch(1); var release = new CountDownLatch(1); var observations = new AtomicInteger();
        doAnswer(invocation -> { var result = invocation.callRealMethod(); if (observations.incrementAndGet()==1) { observed.countDown(); await(release); } return result; }).when(jobOwner).activationLifecycle(reference);
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var processing = pool.submit(() -> worker.process(original)); await(observed);
                as(administrator, () -> { UUID caseId = cases.reviewCase(UUID.randomUUID(), reference); cases.act(UUID.randomUUID(), caseId, 0, "CORRECT_LABEL", "SENSITIVE", "MISLABEL"); return null; });
                var competing = claim(reference, "ACTIVATE"); worker.process(competing); activated(competing);
                as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                var before = effects(); release.countDown(); processing.get(10, TimeUnit.SECONDS);
                assertThat(effects()).isEqualTo(before);
            } finally { release.countDown(); }
        } finally { reset(jobOwner); }
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, original.id())).isEqualTo("SUPERSEDED");
        assertThat(moderation.approval(reference).state()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where resource_id=?", Integer.class, reference.resourceId())).isEqualTo(2);
    }

    ActiveKind legitimateKind(String kind) {
        var fixture = negotiate();
        if (!kind.equals("SUPPORT_NOTE")) { start(fixture); }
        if (kind.equals("REVIEW")) {
            var delivery = active(fixture.provider(), fixture.provider(), fixture.job().id(), "FINAL_DELIVERY");
            var pending = jobs.discover(fixture.job().id());
            as(fixture.customer(), () -> execution.acceptFinal(UUID.randomUUID(), pending.id(), pending.version(), new SubjectId(fixture.customer()), delivery));
        }
        UUID author = kind.equals("FEEDBACK") || kind.equals("REVIEW") ? fixture.customer() : fixture.provider();
        var reference = active(author, author, fixture.job().id(), kind);
        return new ActiveKind(fixture, reference);
    }
    record ActiveKind(Negotiated fixture, ContentReference reference) {}

    void start(Negotiated fixture) {
        fundingFacts.confirm(fixture.job());
        as(fixture.provider(), () -> execution.start(UUID.randomUUID(), fixture.job().id(), fixture.job().version(), new SubjectId(fixture.provider())));
    }

    void activated(ModerationWorker.Claim activation) {
        assertThat(jdbc.queryForMap("select state,reason from moderation_task where id=?", activation.id())).containsEntry("state", "COMPLETE").containsEntry("reason", "ACTIVATED");
    }

    ContentReference active(UUID actor, UUID subject, UUID job, String kind) {
        var reference = as(actor, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), job, Optional.empty(), 0,
                new SubjectId(subject), kind, "Evidencia privada aprobada", kind.equals("REVIEW") ? 4 : null, "GENERAL",
                List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
        as(actor, () -> jobContent.submit(reference)); approve(reference, "GENERAL");
        assertThat(jdbc.queryForObject("select approved_revision from jobs_authored where id=?", UUID.class, reference.resourceId())).isEqualTo(reference.revisionId());
        return reference;
    }

    void correct(ContentReference reference, String label) {
        clock.advance(61);
        as(administrator, () -> {
            UUID caseId = cases.reviewCase(UUID.randomUUID(), reference);
            cases.act(UUID.randomUUID(), caseId, 0, "CORRECT_LABEL", label, "MISLABEL");
            return null;
        });
    }

    Map<String, String> effects() {
        Map<String, String> result = new TreeMap<>();
        var tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema=current_schema() and table_type='BASE TABLE' and (table_name like 'jobs_%' or table_name like 'conversations_%' or table_name like 'agreements_%' or table_name like 'notifications_%' or table_name like 'payments_%' or table_name like 'subscriptions_%' or table_name like 'files_%') order by table_name", String.class);
        for (String table : tables) {
            result.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return Map.copyOf(result);
    }
}
