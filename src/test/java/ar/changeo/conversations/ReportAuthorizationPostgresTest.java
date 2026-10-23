package ar.changeo.conversations;

import ar.changeo.identity.api.SubjectId;
import ar.changeo.identity.api.*;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.jobs.api.JobSnapshot;
import ar.changeo.jobs.JobContentService;
import ar.changeo.marketplace.MarketplaceService;
import ar.changeo.marketplace.PresenceType;
import ar.changeo.moderation.Admission;
import ar.changeo.moderation.ContentDigest;
import ar.changeo.moderation.ModerationFailure;
import ar.changeo.moderation.WorkbenchService;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.*;

class ReportAuthorizationPostgresTest extends ConversationJourneyPostgresTest {
    @MockitoSpyBean Admission reportAdmission;
    @MockitoSpyBean ConversationAccess reportConversations;
    @Autowired WorkbenchService workbench;

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_MESSAGE", "PROPOSAL", "QUOTE_DESCRIPTION", "SUPPORT_NOTE"})
    void shouldDenyUnrelatedKnownReferenceWithoutCaseEffects(String kind) throws Exception {
        var fixture = negotiate();
        var reference = reportReference(fixture, kind);
        UUID stranger = adult(false);
        var before = effects();
        var artifacts = artifactFiles();
        var failure = catchThrowable(() -> as(stranger, () -> cases.open(UUID.randomUUID(), reference, "REPORT", "SAFETY", "Informe sintético")));
        var after = effects();
        var changed = before.keySet().stream().filter(table -> !before.get(table).equals(after.get(table))).toList();
        System.out.println("REPORT_AUTHORIZATION_BASELINE " + kind + " denied=" + (failure != null) + " changedTables=" + changed);
        assertAll(
                () -> assertThat(failure).as("Unrelated reporter must be denied for %s", kind)
                        .isInstanceOfAny(ConversationFailure.class, ModerationFailure.class, ar.changeo.identity.IdentityFailure.class),
                () -> assertThat(changed).as("Denied report must have no database effects").isEmpty(),
                () -> assertThat(artifactFiles()).isEqualTo(artifacts),
                () -> verify(reportAdmission, never()).actor(stranger, "case"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_MESSAGE", "PROPOSAL", "QUOTE_DESCRIPTION", "SUPPORT_NOTE"})
    void shouldAllowBothOriginalParticipantsAndReplayExactReports(String kind) {
        var fixture = negotiate();
        var reference = reportReference(fixture, kind);
        for (UUID reporter : List.of(fixture.customer(), fixture.provider())) {
            UUID command = UUID.randomUUID();
            UUID opened = as(reporter, () -> cases.open(command, reference, "REPORT", "SAFETY", "Informe sintético propio"));
            var beforeReplay = effects();
            assertThat(as(reporter, () -> cases.open(command, reference, "REPORT", "SAFETY", "Informe sintético propio"))).isEqualTo(opened);
            assertThat(effects()).isEqualTo(beforeReplay);
            assertThat(jdbc.queryForObject("select count(*) from moderation_case where id=? and reporter_id=? and kind='REPORT'", Integer.class, opened, reporter)).isEqualTo(1);
        }
    }

    @Test
    void shouldAllowUnrelatedReporterForApprovedPublicListingAndQuestion() {
        UUID provider = adult(true), customer = adult(false), stranger = adult(false);
        var offer = listing(provider, PresenceType.OFFER);
        var question = as(customer, () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                Optional.empty(), Optional.of(offer.resourceId()), new SubjectId(customer), "QUESTION", "Consulta pública sintética", "GENERAL", List.of())));
        as(customer, () -> messages.submit(question));
        approve(question, "GENERAL");
        for (var reference : List.of(offer, question)) {
            UUID opened = as(stranger, () -> cases.open(UUID.randomUUID(), reference, "REPORT", "SAFETY", "Informe público sintético"));
            assertThat(jdbc.queryForObject("select count(*) from moderation_case where id=? and reporter_id=?", Integer.class, opened, stranger)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROGRESS", "FEEDBACK", "FINAL_DELIVERY", "SUPPORT_NOTE", "REVIEW"})
    void raEveryJobKindRequiresExactSideWithoutReadingMoneyOrDeliveringAgain(String kind) throws Exception {
        var fixture = negotiate();
        if (!kind.equals("SUPPORT_NOTE")) {
            fundingFacts.confirm(fixture.job());
            as(fixture.provider(), () -> execution.start(UUID.randomUUID(), fixture.job().id(), fixture.job().version(), new SubjectId(fixture.provider())));
        }
        if (kind.equals("REVIEW")) {
            var delivery = jobReference(fixture.provider(), fixture.provider(), fixture.job().id(), "FINAL_DELIVERY");
            var pending = jobs.discover(fixture.job().id());
            as(fixture.customer(), () -> execution.acceptFinal(UUID.randomUUID(), pending.id(), pending.version(), new SubjectId(fixture.customer()), delivery));
        }
        UUID author = kind.equals("FEEDBACK") || kind.equals("REVIEW") ? fixture.customer() : fixture.provider();
        var reference = jobReference(author, author, fixture.job().id(), kind);
        as(fixture.customer(), () -> { conversations.block(fixture.job().conversation().id(), new SubjectId(fixture.customer()), true); return null; });
        clearInvocations(fundingFacts);
        for (UUID reporter : List.of(fixture.customer(), fixture.provider())) { report(reporter, reference); }
        deny(adult(false), reference);
        deny(administrator, reference);
        verify(fundingFacts, never()).currentForUpdate(any(), any());
    }

    @Test
    void raFocusedReviewerRolePermitsEvidenceButGrantsNoParticipantReportRight() throws Exception {
        var fixture = negotiate();
        var reference = reportReference(fixture, "PRIVATE_MESSAGE");
        String contact = "reportreviewer" + UUID.randomUUID() + "@example.test";
        Path enrollment = Path.of(".runtime/report-reviewer-" + UUID.randomUUID());
        UUID reviewer;
        try {
            operators.provision(contact, Set.of("MODERATION_REVIEWER"), enrollment);
            reviewer = accounts.credentials(contact).orElseThrow().id();
        } finally { Files.deleteIfExists(enrollment); }
        assertThat(jdbc.queryForList("select role from identity_account_role where account_id=?", String.class, reviewer)).containsExactly("MODERATION_REVIEWER");
        assertThat(as(reviewer, () -> evidence.evidence(reference)).text()).contains("Contenido privado sintético");
        deny(reviewer, reference);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLIC", "PRIVATE"})
    void raWorkbenchKeepsPublicReportsAndExactPrivateReporterMembership(String audience) throws Exception {
        UUID author = adult(false), member = adult(false), stranger = adult(false);
        var reference = as(author, () -> workbench.save(new WorkbenchService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                "Contenido sintético de taller", "GENERAL", audience, Optional.empty(), audience.equals("PRIVATE") ? Optional.of(member) : Optional.empty(), List.of())));
        as(author, () -> workbench.submit(reference));
        approve(reference, "GENERAL");
        report(author, reference);
        report(member, reference);
        if (audience.equals("PUBLIC")) { report(stranger, reference); }
        else { deny(stranger, reference); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "REVOKED", "EXPIRED", "RELINKED", "RESTRICTED", "COUNTERPART_RESTRICTED"})
    void raRetainsOriginalSelectedSideWithoutSendPermissionOrSuccessorTransfer(String change) throws Exception {
        var fixture = guardianJourney(true);
        var note = jobReference(fixture.guardian(), fixture.child(), fixture.job(), "SUPPORT_NOTE");
        var quote = jobs.discover(fixture.job()).agreement().orElseThrow();
        UUID successor = adult(false), stranger = adult(false);
        switch (change) {
            case "BLOCK" -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
            case "REVOKED", "RELINKED" -> {
                as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                if (change.equals("RELINKED")) { link(successor, fixture.child()); }
            }
            case "EXPIRED" -> clock.advance(86401);
            case "RESTRICTED" -> as(administrator, () -> { admin.restriction(administrator, fixture.guardian(), true); return null; });
            case "COUNTERPART_RESTRICTED" -> as(administrator, () -> { admin.restriction(administrator, fixture.provider(), true); return null; });
            default -> throw new IllegalArgumentException();
        }
        for (var reference : List.of(fixture.message(), quote, note)) {
            report(fixture.guardian(), reference);
            report(fixture.provider(), reference);
            for (UUID denied : List.of(fixture.child(), successor, stranger, administrator)) { deny(denied, reference); }
        }
        assertThatThrownBy(() -> as(fixture.guardian(), () -> messageReader.ordinary(fixture.message(), new SubjectId(fixture.child())))).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEFT", "BLOCK", "REVOKED"})
    void raUnselectedThreadGetsNoRetainedReportFallback(String change) throws Exception {
        var fixture = guardianJourney(false);
        var quote = quote(fixture.provider(), fixture.job(), FulfillmentMode.REMOTE);
        report(fixture.guardian(), fixture.message());
        switch (change) {
            case "LEFT" -> as(fixture.guardian(), () -> { conversations.leave(fixture.thread(), new SubjectId(fixture.child())); return null; });
            case "BLOCK" -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
            case "REVOKED" -> as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
            default -> throw new IllegalArgumentException();
        }
        for (var reference : List.of(fixture.message(), quote)) {
            deny(fixture.guardian(), reference);
            deny(fixture.provider(), reference);
        }
    }

    @Test
    void raRetainedQuoteMustBeExactSelectedTermsWhileOtherQuoteKeepsCurrentRights() throws Exception {
        UUID customer = adult(false), provider = adult(true);
        var offer = listing(provider, PresenceType.OFFER);
        UUID thread = as(customer, () -> conversations.respond(offer.resourceId(), new SubjectId(customer), FulfillmentMode.REMOTE));
        UUID job = access.discover(thread).jobId();
        var selected = quote(provider, job, FulfillmentMode.REMOTE);
        var other = quote(provider, job, FulfillmentMode.REMOTE);
        long version = agreementAccess.discover(selected).version();
        as(customer, () -> agreements.accept(UUID.randomUUID(), selected, version, new SubjectId(customer)));
        as(provider, () -> agreements.accept(UUID.randomUUID(), selected, version, new SubjectId(provider)));
        report(customer, other);
        report(provider, other);
        as(customer, () -> { conversations.block(thread, new SubjectId(customer), true); return null; });
        for (UUID reporter : List.of(customer, provider)) {
            report(reporter, selected);
            deny(reporter, other);
        }
    }

    @Test
    void raPreservesDistinctControllerSchemaAndMinorSubjectAdultBoundary() throws Exception {
        UUID controller = adult(true), child = minor(14, false);
        link(controller, child);
        var offer = listing(controller, PresenceType.OFFER);
        var before = effects();
        assertThatThrownBy(() -> as(controller, () -> conversations.respond(offer.resourceId(), new SubjectId(child), FulfillmentMode.REMOTE)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(effects()).isEqualTo(before);
        var separate = guardianJourney(true);
        correct(separate.message(), "ADULT");
        deny(separate.guardian(), separate.message());
        report(separate.provider(), separate.message());
    }

    @Test
    void raCurrentEffectiveAudienceAndReplayCannotBypassRecallOrChangedPayload() throws Exception {
        var fixture = negotiate();
        var reference = reportReference(fixture, "PRIVATE_MESSAGE");
        correct(reference, "SENSITIVE");
        accounts.preferences(fixture.customer(), false, false);
        deny(fixture.customer(), reference);
        accounts.preferences(fixture.customer(), true, true);
        UUID command = UUID.randomUUID();
        as(fixture.customer(), () -> cases.open(command, reference, "REPORT", "SAFETY", "Exacto"));
        var before = effects();
        assertThatThrownBy(() -> as(fixture.customer(), () -> cases.open(command, reference, "REPORT", "SAFETY", "Cambiado"))).isInstanceOf(ModerationFailure.class);
        assertThat(effects()).isEqualTo(before);
        long version = jdbc.queryForObject("select version from conversations_authored where id=?", Long.class, reference.resourceId());
        var pending = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.of(reference.resourceId()), version,
                Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), "PRIVATE_MESSAGE", "Edición privada pendiente", "GENERAL", List.of())));
        report(fixture.customer(), reference);
        deny(fixture.customer(), pending);
        as(fixture.provider(), () -> messages.submit(pending));
        approve(pending, "GENERAL");
        deny(fixture.customer(), reference);
        report(fixture.customer(), pending);
        recall(reviewCase(pending), "GENERAL");
        deny(fixture.customer(), pending);
        assertThatThrownBy(() -> as(fixture.customer(), () -> cases.open(command, reference, "REPORT", "SAFETY", "Exacto"))).isInstanceOf(RuntimeException.class);
        deny(fixture.customer(), new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, pending.resourceId(), pending.revisionId()));
    }

    @Test
    void raCaughtDenialRollsBackCallerIntentAndRealDatabaseFailureStaysTechnical() throws Exception {
        var fixture = negotiate();
        var reference = reportReference(fixture, "PRIVATE_MESSAGE");
        UUID stranger = adult(false), intent = UUID.randomUUID();
        jdbc.execute("create table if not exists report_denied_intent(id uuid primary key)");
        var before = effects();
        assertThatThrownBy(() -> tx(() -> {
            try { as(stranger, () -> cases.open(UUID.randomUUID(), reference, "REPORT", "SAFETY", "Denegado")); }
            catch (ConversationFailure denied) { jdbc.update("insert into report_denied_intent values(?)", intent); }
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(effects()).isEqualTo(before);
        ConversationAccess target = AopTestUtils.getUltimateTargetObject(reportConversations);
        doAnswer(invocation -> { jdbc.execute("select 1/0"); return null; }).when(target)
                .requireReportable(fixture.job().conversation().id(), new ActorId(fixture.customer()), "GENERAL", Optional.empty());
        try {
            assertThatThrownBy(() -> as(fixture.customer(), () -> cases.open(UUID.randomUUID(), reference, "REPORT", "SAFETY", "Fallo técnico")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { reset(target); }
        assertThat(effects()).isEqualTo(before);
        verify(reportAdmission, never()).actor(fixture.customer(), "case");
        assertThatThrownBy(() -> as(fixture.customer(), () -> tx(() -> {
            access.protect(fixture.job().conversation().id(), new ActorId(fixture.customer()), new SubjectId(fixture.customer()), Capability.SEND_MARKETPLACE_MESSAGE);
            access.requireReportable(fixture.job().conversation().id(), new ActorId(fixture.customer()), "UNKNOWN", Optional.empty());
            return null;
        }))).isInstanceOf(ConversationFailure.class);
    }

    @ParameterizedTest
    @CsvSource({"false,LEFT", "true,LEFT", "false,BLOCK", "true,BLOCK", "false,GUARDIAN", "true,GUARDIAN",
            "false,SELECTION", "true,SELECTION", "false,RECALL", "true,RECALL", "false,PREFERENCE", "true,PREFERENCE"})
    void raSerializesActualReportAndMembershipSelectionGuardianRecallOrPreference(boolean changeFirst, String change) throws Exception {
        var fixture = guardianJourney(false);
        var reference = fixture.message();
        ContentReference selection;
        if (change.equals("SELECTION")) {
            selection = quote(fixture.provider(), fixture.job(), FulfillmentMode.REMOTE);
            as(fixture.guardian(), () -> agreements.accept(UUID.randomUUID(), selection, agreementAccess.discover(selection).version(), new SubjectId(fixture.child())));
        } else { selection = null; }
        if (change.equals("PREFERENCE")) { correct(reference, "SENSITIVE"); }
        UUID recallCase = change.equals("RECALL") ? reviewCase(reference) : null;
        long before = jdbc.queryForObject("select count(*) from moderation_case", Long.class);
        Supplier<Object> mutation = () -> {
            switch (change) {
                case "LEFT" -> as(fixture.guardian(), () -> { conversations.leave(fixture.thread(), new SubjectId(fixture.child())); return null; });
                case "BLOCK" -> as(fixture.guardian(), () -> { conversations.block(fixture.thread(), new SubjectId(fixture.child()), true); return null; });
                case "GUARDIAN" -> as(fixture.guardian(), () -> { guardians.revoke(fixture.guardian(), fixture.relationship(), 1); return null; });
                case "SELECTION" -> as(fixture.provider(), () -> agreements.accept(UUID.randomUUID(), selection, agreementAccess.discover(selection).version(), new SubjectId(fixture.provider())));
                case "RECALL" -> recall(recallCase, "GENERAL");
                case "PREFERENCE" -> accounts.preferences(fixture.guardian(), false, false);
                default -> throw new IllegalArgumentException();
            }
            return null;
        };
        Supplier<Object> reporting = () -> as(fixture.guardian(), () -> cases.open(UUID.randomUUID(), reference, "REPORT", "SAFETY", "Informe concurrente"));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        boolean reportDenied = changeFirst && !change.equals("SELECTION");
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> tx(() -> { var result = (changeFirst ? mutation : reporting).get(); held.countDown(); await(release); return result; }));
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> tx(() -> {
                    jdbc.execute("set local application_name='report_auth_second'");
                    entered.countDown();
                    return (changeFirst ? reporting : mutation).get();
                }));
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                awaitDatabaseLock();
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                if (reportDenied) { assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class); }
                else { second.get(10, TimeUnit.SECONDS); }
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("select count(*) from moderation_case", Long.class)).isEqualTo(before + (reportDenied ? 0 : 1));
        if (reportDenied) { deny(fixture.guardian(), reference); }
        if (change.equals("SELECTION")) {
            assertThat(jobs.discover(fixture.job()).agreement()).contains(selection);
            report(fixture.guardian(), reference);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void raThreadCanonicalLockAloneSerializesReportAndMembershipChange(boolean membershipFirst) throws Exception {
        var fixture = guardianJourney(false);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        long before = jdbc.queryForObject("select count(*) from moderation_case", Long.class);
        Supplier<Object> membership = () -> {
            access.lock(fixture.thread());
            jdbc.update("update conversations_membership set active=false where thread_id=? and subject_id=?", fixture.thread(), fixture.child());
            return null;
        };
        Supplier<Object> reporting = () -> as(fixture.guardian(), () -> cases.open(UUID.randomUUID(), fixture.message(), "REPORT", "SAFETY", "Informe serializado"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> tx(() -> { var result = (membershipFirst ? membership : reporting).get(); held.countDown(); await(release); return result; }));
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> tx(() -> {
                    jdbc.execute("set local application_name='report_auth_second'");
                    entered.countDown();
                    return (membershipFirst ? reporting : membership).get();
                }));
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                awaitDatabaseLock();
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                if (membershipFirst) { assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class); }
                else { second.get(10, TimeUnit.SECONDS); }
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("select count(*) from moderation_case", Long.class)).isEqualTo(before + (membershipFirst ? 0 : 1));
        deny(fixture.guardian(), fixture.message());
    }

    void awaitDatabaseLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where application_name='report_auth_second' and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0", Integer.class) == 1) { return; }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected actual PostgreSQL blocking relation");
    }

    ContentReference jobReference(UUID actor, UUID subject, UUID job, String kind) {
        var reference = as(actor, () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), job, Optional.empty(), 0,
                new SubjectId(subject), kind, "Evidencia privada aprobada", kind.equals("REVIEW") ? 4 : null, "GENERAL",
                List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
        as(actor, () -> jobContent.submit(reference));
        approve(reference, "GENERAL");
        assertThat(jdbc.queryForObject("select approved_revision from jobs_authored where id=?", UUID.class, reference.resourceId())).isEqualTo(reference.revisionId());
        return reference;
    }

    void correct(ContentReference reference, String label) {
        clock.advance(61);
        as(administrator, () -> { var id = cases.reviewCase(UUID.randomUUID(), reference); cases.act(UUID.randomUUID(), id, 0, "CORRECT_LABEL", label, "MISLABEL"); return null; });
    }

    UUID report(UUID reporter, ContentReference reference) {
        UUID command = UUID.randomUUID();
        UUID opened = as(reporter, () -> cases.open(command, reference, "REPORT", "SAFETY", "Informe sintético"));
        var before = effects();
        assertThat(as(reporter, () -> cases.open(command, reference, "REPORT", "SAFETY", "Informe sintético"))).isEqualTo(opened);
        assertThat(effects()).isEqualTo(before);
        return opened;
    }

    void deny(UUID reporter, ContentReference reference) throws Exception {
        var before = effects();
        var files = new LinkedHashMap<Path, String>();
        for (var file : artifactFiles()) { files.put(file, ContentDigest.bytes(Files.readAllBytes(file))); }
        clearInvocations(reportAdmission);
        assertThatThrownBy(() -> as(reporter, () -> cases.open(UUID.randomUUID(), reference, "REPORT", "SAFETY", "Denegado")))
                .isInstanceOfAny(ConversationFailure.class, ModerationFailure.class, ar.changeo.identity.IdentityFailure.class);
        assertThat(effects()).isEqualTo(before);
        var afterFiles = new LinkedHashMap<Path, String>();
        for (var file : artifactFiles()) { afterFiles.put(file, ContentDigest.bytes(Files.readAllBytes(file))); }
        assertThat(afterFiles).isEqualTo(files);
        verify(reportAdmission, never()).actor(reporter, "case");
    }

    ContentReference reportReference(Negotiated fixture, String kind) {
        if (kind.equals("QUOTE_DESCRIPTION")) { return fixture.quote(); }
        ContentReference reference;
        if (kind.equals("SUPPORT_NOTE")) {
            reference = as(fixture.customer(), () -> jobContent.save(new JobContentService.Draft(UUID.randomUUID(), fixture.job().id(), Optional.empty(), 0,
                    new SubjectId(fixture.customer()), kind, "Ayuda privada sintética", null, "GENERAL", List.of())));
            as(fixture.customer(), () -> jobContent.submit(reference));
        } else {
            reference = as(fixture.provider(), () -> messages.save(new MessageService.Draft(UUID.randomUUID(), Optional.empty(), 0,
                    Optional.of(fixture.job().conversation().id()), Optional.empty(), new SubjectId(fixture.provider()), kind,
                    "Contenido privado sintético", "GENERAL", List.of(new MarketplaceService.Upload("text/plain", "synthetic:clear".getBytes(StandardCharsets.UTF_8))))));
            as(fixture.provider(), () -> messages.submit(reference));
        }
        approve(reference, "GENERAL");
        return reference;
    }

    Map<String, String> effects() {
        Map<String, String> effects = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename<>'flyway_schema_history' order by tablename", String.class)) {
            if (!table.matches("[a-z_]+")) { throw new IllegalStateException("Unexpected test table"); }
            effects.put(table, jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(t)::text,',' order by row_to_json(t)::text),'')) from " + table + " t", String.class));
        }
        return Map.copyOf(effects);
    }
}
