package ar.changeo.conversations;

import ar.changeo.identity.api.SubjectId;
import ar.changeo.marketplace.MarketplaceService;
import ar.changeo.moderation.api.ContentReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MessageActivationPostgresTest extends ConversationJourneyPostgresTest {
    @MockitoSpyBean ar.changeo.identity.api.ParticipantIdentityAccess activationIdentity;
    @Test void shouldCompleteBlockedSelectedMessageReaffirmationWithoutDeliveryEffects() {
        var fixture = negotiate();
        UUID thread = fixture.job().conversation().id();
        var reference = as(fixture.customer(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(thread), Optional.empty(), new SubjectId(fixture.customer()), "PRIVATE_MESSAGE", "Mensaje retenido", "GENERAL",
                List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
        as(fixture.customer(), () -> messages.submit(reference));
        approve(reference, "GENERAL");
        var before = effects(reference);
        as(fixture.customer(), () -> { conversations.block(thread, new SubjectId(fixture.customer()), true); return null; });
        correct(reference, "SENSITIVE");
        var activation = claim(reference, "ACTIVATE");
        worker.process(activation);
        assertThat(jdbc.queryForObject("select state from moderation_task where id=?", String.class, activation.id())).isEqualTo("COMPLETE");
        assertThat(moderation.approval(reference).state()).isEqualTo("APPROVED");
        assertThat(effects(reference)).isEqualTo(before);
        assertThat(as(fixture.customer(), () -> messageReader.historical(reference, new SubjectId(fixture.customer())))).isPresent();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void shouldReaffirmOriginalGuardianAuthoredMessageAfterRevocationAndRelinking(boolean relink) {
        var fixture = guardianJourney(true);
        var reference = delivered(fixture.guardian(), fixture.child(), fixture.thread(), "PRIVATE_MESSAGE");
        var before = effects(reference);
        var file = as(fixture.guardian(), () -> messageReader.ordinary(reference, new SubjectId(fixture.child()))).attachments().getFirst();
        as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
        UUID successor = adult(false);
        if (relink) { link(successor, fixture.child()); }
        correct(reference, "SENSITIVE");
        var activation = claim(reference, "ACTIVATE");
        worker.process(activation);
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo("ACTIVATED");
        assertThat(effects(reference)).isEqualTo(before);
        assertThat(as(fixture.guardian(), () -> files.read(file, ar.changeo.files.api.FileAccess.Purpose.ORDINARY).bytes())).isEqualTo("synthetic:clear".getBytes(StandardCharsets.UTF_8));
        for (UUID denied : List.of(fixture.child(), successor, adult(false))) {
            assertThatThrownBy(() -> as(denied, () -> messageReader.historical(reference, new SubjectId(fixture.child())))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> as(denied, () -> files.read(file, ar.changeo.files.api.FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        }
        correct(reference, "ADULT"); worker.process(claim(reference, "ACTIVATE"));
        assertThat(moderation.approval(reference).state()).isEqualTo("APPROVED");
        assertThat(as(fixture.guardian(), () -> messageReader.historical(reference, new SubjectId(fixture.child())))).isEmpty();
        assertThatThrownBy(() -> as(fixture.guardian(), () -> files.read(file, ar.changeo.files.api.FileAccess.Purpose.ORDINARY))).isInstanceOf(RuntimeException.class);
        correct(reference, "GENERAL"); worker.process(claim(reference, "ACTIVATE"));
        assertThat(as(fixture.guardian(), () -> messageReader.historical(reference, new SubjectId(fixture.child())))).isPresent();
        assertThat(effects(reference)).isEqualTo(before);
        assertThatThrownBy(() -> as(fixture.guardian(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.thread()), Optional.empty(), new SubjectId(fixture.child()), "PRIVATE_MESSAGE", "Nueva comunicación denegada", "GENERAL", List.of())))).isInstanceOf(RuntimeException.class);
    }

    @Test void shouldReaffirmAdultMetadataWhileCurrentPreferenceSuppressesEveryPayload() {
        var fixture = negotiate();
        var reference = delivered(fixture.customer(), fixture.customer(), fixture.job().conversation().id(), "PRIVATE_MESSAGE");
        var before = effects(reference);
        accounts.preferences(fixture.customer(), false, false);
        correct(reference, "ADULT");
        worker.process(claim(reference, "ACTIVATE"));
        assertThat(moderation.approval(reference).state()).isEqualTo("APPROVED");
        assertThat(as(fixture.customer(), () -> messageReader.historical(reference, new SubjectId(fixture.customer())))).isEmpty();
        accounts.preferences(fixture.customer(), true, true);
        assertThat(as(fixture.customer(), () -> messageReader.historical(reference, new SubjectId(fixture.customer())))).isPresent();
        assertThat(effects(reference)).isEqualTo(before);
    }

    @Test void shouldSupersedeOldActiveCorrectionWhenNewDraftIsCurrent() {
        var fixture = negotiate();
        var reference = delivered(fixture.customer(), fixture.customer(), fixture.job().conversation().id(), "PRIVATE_MESSAGE");
        long version = jdbc.queryForObject("select version from conversations_authored where id=?", Long.class, reference.resourceId());
        var draft = as(fixture.customer(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), version,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.customer()), "PRIVATE_MESSAGE", "Borrador posterior", "GENERAL", List.of())));
        var before = effects(reference);
        as(fixture.customer(), () -> { conversations.block(fixture.job().conversation().id(), new SubjectId(fixture.customer()), true); return null; });
        correct(reference, "SENSITIVE");
        var activation = claim(reference, "ACTIVATE"); worker.process(activation);
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo("SUPERSEDED");
        assertThat(effects(reference)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select current_revision from conversations_authored where id=?", UUID.class, reference.resourceId())).isEqualTo(draft.revisionId());
    }

    @Test void shouldKeepNewPointerActivationGuardedAfterBlock() {
        var fixture = negotiate();
        var reference = as(fixture.customer(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.customer()), "PRIVATE_MESSAGE", "Todavía sin entregar", "GENERAL", List.of())));
        as(fixture.customer(), () -> messages.submit(reference));
        correct(reference, "GENERAL");
        as(fixture.customer(), () -> { conversations.block(fixture.job().conversation().id(), new SubjectId(fixture.customer()), true); return null; });
        var before = effects(reference);
        var activation = claim(reference, "ACTIVATE"); worker.process(activation);
        assertThat(jdbc.queryForObject("select state from moderation_task where id=?", String.class, activation.id())).isEqualTo("FAILED");
        assertThat(effects(reference)).isEqualTo(before);
    }

    @Test void shouldKeepDefaultOtherOwnerActivationSubjectToCurrentAuthorEligibility() {
        UUID provider = adult(true);
        var offer = listing(provider, ar.changeo.marketplace.PresenceType.OFFER);
        as(administrator, () -> { admin.restriction(administrator, provider, true); return null; });
        correct(offer, "SENSITIVE");
        var activation = claim(offer, "ACTIVATE"); worker.process(activation);
        assertThat(jdbc.queryForObject("select state from moderation_task where id=?", String.class, activation.id())).isEqualTo("FAILED");
        assertThat(moderation.approval(offer).state()).isEqualTo("AWAITING_ELIGIBILITY");
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo("ELIGIBILITY");
    }

    @Test void shouldReaffirmProviderProposalWithoutASecondDeliveryOrDuplicateSignal() {
        var fixture = negotiate();
        var reference = delivered(fixture.provider(), fixture.provider(), fixture.job().conversation().id(), "PROPOSAL");
        var before = effects(reference);
        correct(reference, "GENERAL");
        var activation = claim(reference, "ACTIVATE");
        worker.process(activation); worker.process(activation);
        assertThat(effects(reference)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo("ACTIVATED");
    }

    @Test void shouldFreshlyProtectCompleteOriginalAndReplacementIdentityAfterDiscoveryMovement() throws Exception {
        var fixture = guardianJourney(true);
        var reference = delivered(fixture.guardian(), fixture.child(), fixture.thread(), "PRIVATE_MESSAGE");
        correct(reference, "SENSITIVE");
        var activation = claim(reference, "ACTIVATE");
        var before = effects(reference);
        UUID replacement = adult(false);
        var observed = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        var observations = new AtomicInteger();
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            if (observations.incrementAndGet() == 1) { observed.countDown(); await(changed); }
            return result;
        }).when(messageOwner).activationLifecycle(reference);
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var processing = pool.submit(() -> worker.process(activation));
                await(observed);
                as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                link(replacement, fixture.child());
                changed.countDown(); processing.get(10, TimeUnit.SECONDS);
            } finally { changed.countDown(); }
        } finally { reset(messageOwner); }
        assertThat(observations.get()).isEqualTo(6);
        assertThat(effects(reference)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id())).isEqualTo("ACTIVATED");
        verify(activationIdentity, atLeastOnce()).requireForUpdate(
                eq(ar.changeo.identity.api.PermissionRequest.self(new ar.changeo.identity.api.AccountId(fixture.guardian()), ar.changeo.identity.api.Capability.ACCESS_SUPPORT)),
                argThat(accounts -> accounts.containsAll(Set.of(new ar.changeo.identity.api.AccountId(fixture.guardian()), new ar.changeo.identity.api.AccountId(fixture.child()), new ar.changeo.identity.api.AccountId(replacement)))));
    }

    @Test void shouldDurablyDeferThreeDiscoveryMovesRefundOnlyItsLiveClaimAndCompleteWhenStable() throws Exception {
        var fixture = guardianJourney(true);
        var reference = delivered(fixture.guardian(), fixture.child(), fixture.thread(), "PRIVATE_MESSAGE");
        while (worker.processOne()) {}
        var replacements = List.of(adult(false), adult(false), adult(false));
        correct(reference, "SENSITIVE");
        var activation = worker.claim().orElseThrow();
        assertThat(activation.kind()).isEqualTo("ACTIVATE");
        var before = effects(reference);
        var approvalBefore = moderation.approval(reference);
        var versionsBefore = jdbc.queryForMap("select generation,version,decision_id from moderation_submission where id=?", activation.submission());
        var observed = new LinkedBlockingQueue<Integer>();
        var release = new Semaphore(0);
        var observations = new AtomicInteger();
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            int observation = observations.incrementAndGet();
            if (observation <= 5 && observation % 2 == 1) {
                observed.add(observation);
                assertThat(release.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(messageOwner).activationLifecycle(reference);
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var processing = pool.submit(() -> worker.process(activation));
                UUID currentGuardian = fixture.guardian(), relationship = fixture.relationship();
                for (int index = 0; index < 3; index++) {
                    assertThat(observed.poll(10, TimeUnit.SECONDS)).isEqualTo(index * 2 + 1);
                    UUID guardian = currentGuardian, link = relationship;
                    as(guardian, () -> { guardians.revoke(guardian, link, 1); return null; });
                    currentGuardian = replacements.get(index);
                    relationship = link(currentGuardian, fixture.child());
                    release.release();
                }
                processing.get(10, TimeUnit.SECONDS);
            } finally { release.release(3); }
        } finally { reset(messageOwner); }
        assertThat(observations.get()).isEqualTo(6);
        assertThat(jdbc.queryForMap("select state,reason,attempts,claim_token,lease_until from moderation_task where id=?", activation.id()))
                .containsEntry("state", "RETRY_WAIT").containsEntry("reason", "CHANGED").containsEntry("attempts", 0).containsEntry("claim_token", null).containsEntry("lease_until", null);
        assertThat(jdbc.queryForObject("select available_at from moderation_task where id=?", java.sql.Timestamp.class, activation.id()).toInstant()).isEqualTo(clock.instant().plusSeconds(5));
        assertThat(moderation.approval(reference)).isEqualTo(approvalBefore);
        assertThat(jdbc.queryForMap("select generation,version,decision_id from moderation_submission where id=?", activation.submission())).isEqualTo(versionsBefore);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
        assertThat(effects(reference)).isEqualTo(before);
        worker.process(activation);
        assertThat(jdbc.queryForObject("select attempts from moderation_task where id=?", Integer.class, activation.id())).isZero();
        assertThat(worker.claim()).isEmpty();
        clock.advance(6);
        var retry = worker.claim().orElseThrow();
        assertThat(retry.id()).isEqualTo(activation.id()); assertThat(retry.token()).isNotEqualTo(activation.token());
        worker.process(retry);
        assertThat(jdbc.queryForMap("select state,reason,attempts from moderation_task where id=?", activation.id()))
                .containsEntry("state", "COMPLETE").containsEntry("reason", "ACTIVATED").containsEntry("attempts", 1);
        assertThat(effects(reference)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"TOKEN", "LEASE", "GENERATION", "DECISION", "BARRIER", "SUBMISSION"})
    void shouldNeverRefundExpiredStaleOrForeignDiscoveryClaim(String change) {
        var fixture = negotiate();
        var reference = delivered(fixture.customer(), fixture.customer(), fixture.job().conversation().id(), "PRIVATE_MESSAGE");
        correct(reference, "SENSITIVE");
        var original = claim(reference, "ACTIVATE");
        var activation = switch (change) {
            case "TOKEN" -> new ar.changeo.moderation.ModerationWorker.Claim(original.id(), original.barrier(), original.submission(), original.kind(), original.generation(), UUID.randomUUID(), original.decision(), false);
            case "GENERATION" -> new ar.changeo.moderation.ModerationWorker.Claim(original.id(), original.barrier(), original.submission(), original.kind(), original.generation() + 1, original.token(), original.decision(), false);
            case "DECISION" -> new ar.changeo.moderation.ModerationWorker.Claim(original.id(), original.barrier(), original.submission(), original.kind(), original.generation(), original.token(), UUID.randomUUID(), false);
            case "BARRIER" -> new ar.changeo.moderation.ModerationWorker.Claim(original.id(), UUID.randomUUID(), original.submission(), original.kind(), original.generation(), original.token(), original.decision(), false);
            case "SUBMISSION" -> new ar.changeo.moderation.ModerationWorker.Claim(original.id(), original.barrier(), UUID.randomUUID(), original.kind(), original.generation(), original.token(), original.decision(), false);
            default -> original;
        };
        if (change.equals("LEASE")) { jdbc.update("update moderation_task set lease_until=? where id=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)), original.id()); }
        var before = jdbc.queryForMap("select * from moderation_task where id=?", original.id());
        var effects = effects(reference);
        var observations = new AtomicInteger();
        doAnswer(invocation -> observations.incrementAndGet() % 2 == 1 ? ar.changeo.moderation.api.ContentOwner.ActivationLifecycle.REAFFIRMATION
                : ar.changeo.moderation.api.ContentOwner.ActivationLifecycle.DELIVERY).when(messageOwner).activationLifecycle(reference);
        try {
            if (change.equals("BARRIER") || change.equals("SUBMISSION")) {
                assertThatThrownBy(() -> worker.process(activation)).isInstanceOf(RuntimeException.class);
            } else { worker.process(activation); }
        } finally { reset(messageOwner); }
        assertThat(jdbc.queryForMap("select * from moderation_task where id=?", original.id())).isEqualTo(before);
        assertThat(effects(reference)).isEqualTo(effects);
        assertThat(moderation.approval(reference).state()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, original.decision())).isZero();
    }

    @Test void shouldKeepRealOutageAttemptsBoundedWithoutDiscoveryRefund() {
        var fixture = negotiate();
        var reference = as(fixture.customer(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.customer()), "PRIVATE_MESSAGE", "Nueva entrega con falla técnica", "GENERAL", List.of())));
        as(fixture.customer(), () -> messages.submit(reference));
        correct(reference, "GENERAL");
        var before = effects(reference);
        doThrow(new org.springframework.dao.CannotAcquireLockException("Synthetic transient owner outage")).when(messageOwner).prepareActivation(reference);
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                var activation = claim(reference, "ACTIVATE"); worker.process(activation);
                assertThat(jdbc.queryForMap("select state,reason,attempts from moderation_task where id=?", activation.id()))
                        .containsEntry("state", attempt < 3 ? "RETRY_WAIT" : "FAILED").containsEntry("reason", "OUTAGE").containsEntry("attempts", attempt);
            }
        } finally { reset(messageOwner); }
        assertThat(moderation.approval(reference).state()).isEqualTo("FAILED");
        assertThat(effects(reference)).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"false,BLOCK", "true,BLOCK", "false,GUARDIAN", "true,GUARDIAN", "false,RECALL", "true,RECALL"})
    void shouldSerializeReaffirmationAgainstBlockGuardianChangeAndRecall(boolean changeFirst, String changeKind) throws Exception {
        var fixture = guardianJourney(true);
        var reference = delivered(fixture.guardian(), fixture.child(), fixture.thread(), "PRIVATE_MESSAGE");
        correct(reference, "SENSITIVE");
        UUID caseId = changeKind.equals("RECALL") ? reviewCase(reference) : null;
        var activation = claim(reference, "ACTIVATE");
        var before = effects(reference);
        var protectedFirst = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        if (!changeFirst) {
            doAnswer(invocation -> { var result = invocation.callRealMethod(); protectedFirst.countDown(); await(release); return result; }).when(messageOwner).activate(reference);
        }
        Runnable change = () -> {
            switch (changeKind) {
                case "RECALL" -> recall(caseId, "GENERAL");
                case "GUARDIAN" -> as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                default -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = changeFirst ? pool.submit(() -> tx(() -> { change.run(); protectedFirst.countDown(); await(release); return null; }))
                        : pool.submit(() -> worker.process(activation));
                await(protectedFirst);
                var second = pool.submit(() -> { secondEntered.countDown(); if (changeFirst) { worker.process(activation); } else { change.run(); } });
                await(secondEntered);
                assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        } finally { reset(messageOwner); }
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, activation.id()))
                .isEqualTo(changeFirst && changeKind.equals("RECALL") ? "SUPERSEDED" : "ACTIVATED");
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where resource_id=?", Integer.class, reference.resourceId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from conversations_event where resource_id=? and kind='DELIVERED'", Integer.class, reference.resourceId())).isEqualTo(1);
        if (!changeKind.equals("RECALL")) { assertThat(effects(reference)).isEqualTo(before); }
        else { assertThat(jdbc.queryForObject("select approved_revision from conversations_authored where id=?", UUID.class, reference.resourceId())).isNull(); }
    }

    @Test void shouldRestartStaleDeliveryDiscoveryAfterCompetingDeliveryAndOriginalGuardianRevocation() throws Exception {
        var fixture = guardianJourney(true);
        var reference = as(fixture.guardian(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.thread()), Optional.empty(), new SubjectId(fixture.child()), "PRIVATE_MESSAGE", "Entrega concurrente del autor original", "GENERAL", List.of())));
        as(fixture.guardian(), () -> messages.submit(reference));
        correct(reference, "GENERAL");
        var original = claim(reference, "ACTIVATE");
        var discovered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observations = new AtomicInteger();
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            if (observations.incrementAndGet() == 1) { discovered.countDown(); await(release); }
            return result;
        }).when(messageOwner).activationLifecycle(reference);
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var processing = pool.submit(() -> worker.process(original));
                await(discovered);
                as(administrator, () -> {
                    UUID caseId = cases.reviewCase(UUID.randomUUID(), reference);
                    cases.act(UUID.randomUUID(), caseId, 0, "CORRECT_LABEL", "SENSITIVE", "MISLABEL");
                    return null;
                });
                var competing = claim(reference, "ACTIVATE"); worker.process(competing);
                as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                release.countDown(); processing.get(10, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, competing.id())).isEqualTo("ACTIVATED");
            } finally { release.countDown(); }
        } finally { reset(messageOwner); }
        assertThat(jdbc.queryForObject("select reason from moderation_task where id=?", String.class, original.id())).isEqualTo("SUPERSEDED");
        assertThat(moderation.approval(reference).state()).isEqualTo("APPROVED");
        assertThat(as(fixture.guardian(), () -> messageReader.historical(reference, new SubjectId(fixture.child())))).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from conversations_event where resource_id=?", Integer.class, reference.resourceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications_intent where resource_id=?", Integer.class, reference.resourceId())).isEqualTo(2);
    }

    @Test void shouldRejectCrossThreadAuthorBindingAndRollbackCaughtSubmissionDenial() {
        var fixture = negotiate();
        UUID stranger = adult(false), resource = UUID.randomUUID(), revision = UUID.randomUUID(), intent = UUID.randomUUID();
        var reference = new ContentReference(ar.changeo.moderation.api.SurfaceKind.PRIVATE_MESSAGE, resource, revision);
        var author = new ar.changeo.moderation.api.AuthorContext(new ar.changeo.identity.api.ActorId(stranger), new SubjectId(stranger),
                ar.changeo.identity.api.Capability.SEND_MARKETPLACE_MESSAGE, Optional.of(fixture.job().conversation().jobContext()));
        String body = "Synthetic foreign canonical binding", text = "CHANGEO_WHOLE_MESSAGE_V1\nPRIVATE_MESSAGE\n" + body;
        String digest = ar.changeo.moderation.ContentDigest.payload(text, "GENERAL", List.of());
        tx(() -> {
            jdbc.update("insert into conversations_authored(id,thread_id,kind,actor_id,subject_id) values(?,?,'PRIVATE_MESSAGE',?,?)", resource, fixture.job().conversation().id(), stranger, stranger);
            jdbc.update("insert into conversations_revision values(?,?,?,'GENERAL',?,?)", revision, resource, body, digest, java.sql.Timestamp.from(clock.instant()));
            jdbc.update("update conversations_authored set current_revision=?,approved_revision=? where id=?", revision, revision, resource);
            return null;
        });
        jdbc.execute("create table if not exists conversations_cfr_intent(id uuid primary key)");
        assertThatThrownBy(() -> as(stranger, () -> tx(() -> {
            try { moderation.submitForReview(author, reference, text, "GENERAL", List.of()); }
            catch (ar.changeo.moderation.ModerationFailure denied) { jdbc.update("insert into conversations_cfr_intent values(?)", intent); }
            return null;
        }))).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from conversations_cfr_intent where id=?", Integer.class, intent)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from moderation_reference where resource_id=?", Integer.class, resource)).isZero();
        assertThatThrownBy(() -> as(stranger, () -> messageReader.historical(reference, new SubjectId(stranger)))).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void shouldNeverRetryOrdinaryAuthorDenialAsMetadataAdmissionMovement(boolean typedOrdinaryFailure) {
        var fixture = guardianJourney(true);
        var reference = as(fixture.guardian(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(fixture.thread()), Optional.empty(), new SubjectId(fixture.child()), "PRIVATE_MESSAGE", "Nueva entrega protegida", "GENERAL", List.of())));
        as(fixture.guardian(), () -> messages.submit(reference));
        correct(reference, "GENERAL");
        if (typedOrdinaryFailure) {
            var permission = new ar.changeo.moderation.api.AuthorContext(new ar.changeo.identity.api.ActorId(fixture.guardian()), new SubjectId(fixture.child()),
                    ar.changeo.identity.api.Capability.SEND_MARKETPLACE_MESSAGE, Optional.of(access.discover(fixture.thread()).jobContext())).permission();
            doThrow(new ar.changeo.identity.api.IdentityDiscoveryChanged()).when(activationIdentity).requireForUpdate(eq(permission), anySet());
        } else { as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; }); }
        var before = effects(reference);
        var activation = claim(reference, "ACTIVATE");
        try { worker.process(activation); }
        finally { reset(activationIdentity); }
        assertThat(jdbc.queryForMap("select state,reason,attempts from moderation_task where id=?", activation.id()))
                .containsEntry("state", "FAILED").containsEntry("reason", "ELIGIBILITY").containsEntry("attempts", 1);
        assertThat(moderation.approval(reference).state()).isEqualTo("AWAITING_ELIGIBILITY");
        assertThat(effects(reference)).isEqualTo(before);
    }

    @Test void shouldRejectInconsistentAttemptCountWithoutRefundOrActivation() {
        var fixture = negotiate();
        var reference = delivered(fixture.customer(), fixture.customer(), fixture.job().conversation().id(), "PRIVATE_MESSAGE");
        correct(reference, "GENERAL");
        var activation = claim(reference, "ACTIVATE");
        jdbc.update("update moderation_task set attempts=0 where id=?", activation.id());
        var before = effects(reference);
        var observations = new AtomicInteger();
        doAnswer(invocation -> observations.incrementAndGet() % 2 == 1 ? ar.changeo.moderation.api.ContentOwner.ActivationLifecycle.REAFFIRMATION
                : ar.changeo.moderation.api.ContentOwner.ActivationLifecycle.DELIVERY).when(messageOwner).activationLifecycle(reference);
        try { worker.process(activation); }
        finally { reset(messageOwner); }
        assertThat(jdbc.queryForMap("select state,reason,attempts from moderation_task where id=?", activation.id()))
                .containsEntry("state", "FAILED").containsEntry("reason", "BINDING").containsEntry("attempts", 0);
        assertThat(jdbc.queryForObject("select count(*) from moderation_signal where decision_id=?", Integer.class, activation.decision())).isZero();
        assertThat(effects(reference)).isEqualTo(before);
    }

    ContentReference delivered(UUID actor, UUID subject, UUID thread, String kind) {
        var reference = as(actor, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.of(thread), Optional.empty(), new SubjectId(subject), kind, "Texto privado aprobado", "GENERAL",
                List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
        as(actor, () -> messages.submit(reference)); approve(reference, "GENERAL");
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

    Object effects(ContentReference reference) {
        return jdbc.queryForMap("select approved_revision,version,"
                + "(select count(*) from conversations_event where resource_id=a.id) events,"
                + "(select count(*) from notifications_intent where resource_id=a.id) notices,"
                + "(select count(*) from conversations_attachment where revision_id=?) files "
                + "from conversations_authored a where id=?", reference.revisionId(), reference.resourceId());
    }
}
