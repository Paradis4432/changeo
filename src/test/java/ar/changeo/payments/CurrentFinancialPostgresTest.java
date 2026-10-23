package ar.changeo.payments;

import ar.changeo.identity.ParticipantIdentityService;
import ar.changeo.moderation.CaseService;
import ar.changeo.payments.api.RefundDestination;
import ar.changeo.payments.gateway.GatewayRequest;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import ar.changeo.identity.api.AccountId;
import ar.changeo.identity.api.Capability;
import ar.changeo.identity.api.PermissionRequest;
import ar.changeo.security.AccountPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CurrentFinancialPostgresTest extends PaymentPostgresTest {
    @Autowired CaseService cases;
    @Autowired HikariDataSource nativePool;
    @Autowired ApplicationAvailability availability;
    @MockitoSpyBean ParticipantIdentityService participants;

    @DynamicPropertySource
    static void currentFixture(DynamicPropertyRegistry properties) {
        CurrentFinancialTestFixture.supplied().register(properties, true);
    }

    @Override @BeforeEach
    void setup() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(CurrentFinancialTestFixture.database());
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(CurrentFinancialTestFixture.schema());
        enrollment = CurrentFinancialTestFixture.supplied().enrollment(false);
        var audit = CurrentFinancialTestFixture.supplied().enrollment(true);
        try {
            UUID reviewer = UUID.fromString(enrollment.getProperty("account"));
            UUID auditor = UUID.fromString(audit.getProperty("account"));
            assertThat(reviewer).isNotEqualTo(auditor);
            assertThat(jdbc.queryForObject("select count(*) from identity_admin_mfa where account_id in (?,?)", Integer.class, reviewer, auditor)).isEqualTo(2);
            long counter = jdbc.queryForObject("select max(last_counter) from identity_admin_mfa where account_id in (?,?)", Long.class, reviewer, auditor);
            Instant floor = Instant.ofEpochSecond(Math.multiplyExact(Math.addExact(counter, 1), 30));
            clock.now.accumulateAndGet(floor, (current, minimum) -> current.isAfter(minimum) ? current : minimum);
        } finally { audit.clear(); }
        clock.advance(61); tokens.clear();
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true;
        });
        operator = login(enrollment.getProperty("contact"), enrollment.getProperty("password")); operatorProof();
    }

    @Override @Test
    void shouldRequireGenuineSeparateAuditAuthorityAndFreshServerProof() throws Exception {
        var ordinary = author(false);
        mvc.perform(get("/payments/audit").session(ordinary.session()).param("mfaEpoch", "0")).andExpect(status().isForbidden());
        operatorProof();
        var context = (SecurityContext) operator.getAttribute("SPRING_SECURITY_CONTEXT");
        var principal = (AccountPrincipal) context.getAuthentication().getPrincipal();
        boolean allowed = as(new AccountFixture(principal.id(), operator), () -> {
            assertThat(proof.mfa(principal.id(), principal.epoch())).isTrue();
            assertThat(proof.freshPassword(principal.id(), principal.epoch())).isTrue();
            return identity.decide(PermissionRequest.self(new AccountId(principal.id()), Capability.ADMIN_AUDIT)).allowed();
        });
        assertThat(allowed).isFalse();
        mvc.perform(get("/payments/audit").session(operator)).andExpect(status().isForbidden());
        operator.removeAttribute("passwordEpoch"); operator.removeAttribute("passwordUntil");
        mvc.perform(get("/payments/audit").session(operator)).andExpect(status().isForbidden());
        operator.removeAttribute("mfaEpoch"); operator.removeAttribute("mfaUntil");
        mvc.perform(get("/payments/audit").session(operator)).andExpect(status().isForbidden());
        receipt("qa-review-audit-denials", "\"genuineCurrentQaSession\":true,\"mfaCurrent\":true,\"passwordFresh\":true,\"adminAuditAllowed\":false");
    }

    @Override @Test
    void shouldReadMetadataOnlyAuditWithTheActuallyHandedOffExistingAuditRoleAndExpireEachRealProof() throws Exception {
        var journey = negotiate(); UUID funding = queueFund(journey, 0); drain();
        var auditMaterial = CurrentFinancialTestFixture.supplied().enrollment(true);
        try {
            var auditSession = login(auditMaterial.getProperty("contact"), auditMaterial.getProperty("password"));
            mvc.perform(get("/payments/audit").session(auditSession).param("mfaEpoch", "0")).andExpect(status().isForbidden());
            clock.advance(61);
            mvc.perform(post("/admin/mfa").session(auditSession).with(csrf()).param("code", totp(auditMaterial.getProperty("totpBase32")))).andExpect(redirectedUrl("/admin/reauth"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isForbidden());
            mvc.perform(post("/admin/reauth").session(auditSession).with(csrf()).param("password", auditMaterial.getProperty("password"))).andExpect(redirectedUrl("/admin/accounts"));
            var context = (SecurityContext) auditSession.getAttribute("SPRING_SECURITY_CONTEXT");
            var principal = (AccountPrincipal) context.getAuthentication().getPrincipal();
            assertThat(as(new AccountFixture(principal.id(), auditSession), () -> {
                assertThat(proof.mfa(principal.id(), principal.epoch())).isTrue(); assertThat(proof.freshPassword(principal.id(), principal.epoch())).isTrue();
                return identity.decide(PermissionRequest.self(new AccountId(principal.id()), Capability.ADMIN_AUDIT)).allowed();
            })).isTrue();
            UUID after = null; boolean found = false; int pages = 0;
            do {
                var request = get("/payments/audit").session(auditSession); if (after != null) { request.param("after", after.toString()); }
                var result = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                        .andExpect(content().string(not(containsString("Alcance privado sin exposición")))).andExpect(content().string(not(containsString("Entrega privada real")))).andReturn();
                @SuppressWarnings("unchecked") var entries = (List<PaymentQueryService.AuditView>) result.getModelAndView().getModel().get("entries");
                assertThat(entries).hasSizeLessThanOrEqualTo(50);
                found |= entries.stream().anyMatch(entry -> entry.operationId().equals(funding) && entry.amountMinor() == 12345 && entry.state().equals("SUCCESS"));
                after = (UUID) result.getModelAndView().getModel().get("next"); pages++;
            } while (after != null && pages < 20);
            assertThat(after).isNull(); assertThat(found).isTrue();
            clock.advance(601);
            mvc.perform(post("/admin/mfa").session(auditSession).with(csrf()).param("code", totp(auditMaterial.getProperty("totpBase32")))).andExpect(redirectedUrl("/admin/reauth"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isForbidden());
            mvc.perform(post("/admin/reauth").session(auditSession).with(csrf()).param("password", auditMaterial.getProperty("password"))).andExpect(redirectedUrl("/admin/accounts"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isOk());
            clock.advance(601);
            mvc.perform(post("/admin/reauth").session(auditSession).with(csrf()).param("password", auditMaterial.getProperty("password"))).andExpect(redirectedUrl("/admin/accounts"));
            mvc.perform(get("/payments/audit").session(auditSession)).andExpect(status().isForbidden());
            receipt("qa-metadata-audit", "\"genuineCurrentQaSession\":true,\"sourceKeyApplicable\":true,\"adminAuditAllowed\":true,\"mfaCurrent\":true,\"passwordFresh\":true,\"metadataOnly\":true,\"mfaAndPasswordExpiredDenials\":true");
        } finally { auditMaterial.clear(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldSerializeCurrentDispatchAndActualDisputeInBothOrders(boolean dispatchFirst) throws Exception {
        var journey = negotiate(); queueFund(journey, 0); drain(); complete(journey); release(journey);
        var claim = worker.claim().orElseThrow(); var operation = store.operation(claim.operationId(), false);
        assertThat(operation.kind()).isEqualTo("RELEASE");
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var holderPid = new AtomicInteger(); var once = new AtomicBoolean();
        if (dispatchFirst) {
            doAnswer(invocation -> {
                invocation.callRealMethod();
                if (once.compareAndSet(false, true)) {
                    holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); held.countDown(); await(release);
                }
                return null;
            }).when(store).protectExisting(eq(operation.rootId()));
        } else {
            doAnswer(invocation -> {
                invocation.callRealMethod();
                if (once.compareAndSet(false, true)) {
                    holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); held.countDown(); await(release);
                }
                return null;
            }).when(store).saveOutcome(any(), any());
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                if (dispatchFirst) { return worker.dispatch(claim); }
                pause(journey); return Optional.<GatewayRequest>empty();
            });
            await(held);
            var second = pool.submit(() -> {
                if (dispatchFirst) { pause(journey); return Optional.<GatewayRequest>empty(); }
                return worker.dispatch(claim);
            });
            awaitKnownBlocker(holderPid.get());
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=? and kind in ('RELEASE','PAYOUT')", Integer.class, operation.rootId())).isZero();
            release.countDown();
            var firstResult = first.get(5, TimeUnit.SECONDS); var secondResult = second.get(5, TimeUnit.SECONDS);
            var dispatched = dispatchFirst ? firstResult : secondResult;
            assertThat(dispatched.isPresent()).isEqualTo(dispatchFirst);
            assertThat(store.root(operation.rootId(), false).paused()).isTrue();
            assertThat(jdbc.queryForObject("select state from jobs_job where id=?", String.class, journey.job())).isEqualTo("SUPPORT_HOLD");
            if (dispatchFirst) {
                assertThat(store.operation(operation.id(), false).state()).isEqualTo("UNKNOWN");
                var observation = gateway.execute(dispatched.orElseThrow());
                assertThat(outcomes.record(observation)).isTrue(); assertThat(outcomes.record(observation)).isFalse();
                assertThat(store.operation(operation.id(), false).state()).isEqualTo("SUCCESS");
                assertThat(store.operations(operation.rootId(), false).stream().filter(value -> value.kind().equals("PAYOUT")).toList())
                        .singleElement().satisfies(value -> assertThat(value.state()).isEqualTo("CANCELLED"));
            } else {
                assertThat(store.operation(operation.id(), false).state()).isEqualTo("CANCELLED");
                assertThat(store.operation(operation.id(), false).dispatchedAt()).isEmpty();
            }
            var root = store.root(operation.rootId(), false);
            assertThat(root.releasedMinor()).isEqualTo(dispatchFirst ? 12345 : 0); assertThat(root.paidMinor()).isZero();
            assertThat(root.paused()).isTrue(); assertThat(root.disputed()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=? and kind='PAYOUT'", Integer.class, root.id())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=? and kind='RELEASE'", Integer.class, root.id())).isEqualTo(dispatchFirst ? 1 : 0);
            assertThat(jdbc.queryForObject("select sum(backing_minor)::bigint from payments_source where origin_root=?", Long.class, root.id())).isEqualTo(12345);
            assertThat(jdbc.queryForObject("select sum(signed_minor)::bigint from payments_ledger where root_id=?", Long.class, root.id())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='COMPLETED'", Integer.class, journey.job())).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    AccountFixture genuineOperator() {
        var context = (SecurityContext) operator.getAttribute("SPRING_SECURITY_CONTEXT");
        var principal = (ar.changeo.security.AccountPrincipal) context.getAuthentication().getPrincipal();
        return new AccountFixture(principal.id(), operator);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void shouldSerializeActualFundWithSensitivePreferenceWithdrawal(boolean preferenceFirst, boolean customerChanges) throws Exception {
        var journey = negotiate();
        operatorProof();
        as(genuineOperator(), () -> {
            UUID review = cases.reviewCase(UUID.randomUUID(), journey.quote());
            cases.act(UUID.randomUUID(), review, 0, "CORRECT_LABEL", "SENSITIVE", "SAFETY");
            return true;
        });
        for (int count = 0; count < 100 && moderation.processOne(); count++) { }
        assertThat(jdbc.queryForObject("select label from moderation_submission s join moderation_snapshot p on p.id=s.id where p.revision_id=?", String.class, journey.quote().revisionId())).isEqualTo("SENSITIVE");
        view(journey, journey.customer());
        String before = financialFingerprint();
        var changing = customerChanges ? journey.customer() : journey.provider();
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        if (!preferenceFirst) {
            doAnswer(invocation -> {
                var result = invocation.callRealMethod();
                firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release);
                return result;
            }).when(AopTestUtils.<PaymentFinancialAdapter>getUltimateTargetObject(financialAdapter)).prepareFundingForUpdate(any(), any());
        }
        java.util.concurrent.Callable<Boolean> withdraw = () -> {
            mvc.perform(post("/account/preferences").session(changing.session()).with(csrf()).param("sensitive", "false").param("adult", "true"))
                    .andExpect(status().is3xxRedirection()); return true;
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> first; java.util.concurrent.Future<?> second;
            if (preferenceFirst) {
                first = pool.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
                    try {
                        firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        withdraw.call(); ready.countDown(); await(release); return true;
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                }));
                await(ready); second = pool.submit(() -> mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden()).andReturn());
            } else {
                first = pool.submit(() -> mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().is3xxRedirection()).andReturn());
                await(ready); second = pool.submit(withdraw);
            }
            awaitKnownBlocker(firstPid.get());
            assertThat(financialFingerprint()).isEqualTo(before);
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, journey.job())).isEqualTo(preferenceFirst ? 0 : 1);
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth g join payments_root r on r.id=g.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            String deniedBefore = financialFingerprint();
            mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden());
            assertThat(financialFingerprint()).isEqualTo(deniedBefore);
            if (preferenceFirst) { assertThat(financialFingerprint()).isEqualTo(before); }
            else {
                UUID funding = jdbc.queryForObject("select o.id from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", UUID.class, journey.job());
                drain(); assertFundingBacking(journey, funding);
                assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345);
            }
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='STARTED'", Integer.class, journey.job())).isZero();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSerializeRelinkVerificationWithOriginalPayerFundWithoutTransferringObligation(boolean verificationFirst) throws Exception {
        var oldGuardian = author(false); var child = minor(); UUID oldLink = link(oldGuardian, child);
        var journey = negotiate(new AccountFixture(child.id(), oldGuardian.session()), author(true));
        view(journey, journey.customer());
        as(oldGuardian, () -> {
            long version = jdbc.queryForObject("select version from identity_guardian_link where id=?", Long.class, oldLink);
            guardians.revoke(oldGuardian.id(), oldLink, version); return true;
        });
        var newGuardian = author(false);
        mvc.perform(post("/account/guardian/request").session(newGuardian.session()).with(csrf()).param("other", child.id().toString()).param("actingAsGuardian", "true"))
                .andExpect(status().is3xxRedirection());
        UUID pending = jdbc.queryForObject("select id from identity_guardian_link where guardian_id=? and minor_id=? and status='PENDING'", UUID.class, newGuardian.id(), child.id());
        operatorProof(); String before = financialFingerprint();
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1); var firstPid = new AtomicInteger();
        var intercepted = new AtomicBoolean();
        if (!verificationFirst) {
            doAnswer(invocation -> {
                try { return invocation.callRealMethod(); }
                catch (RuntimeException failure) {
                    var request = invocation.getArgument(0, PermissionRequest.class);
                    if (request.capability() == Capability.SYNTHETIC_FUNDING_ELIGIBILITY
                            && request.subjectId().orElseThrow().value().equals(child.id()) && intercepted.compareAndSet(false, true)) {
                        firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); ready.countDown(); await(release);
                    }
                    throw failure;
                }
            }).when(participants).requireForUpdate(any(), any());
        }
        java.util.function.Supplier<Boolean> verify = () -> as(genuineOperator(), () -> {
            admin.reviewGuardian(genuineOperator().id(), pending, 0, true); return true;
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> first; java.util.concurrent.Future<?> second;
            if (verificationFirst) {
                first = pool.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
                    firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    verify.get(); ready.countDown(); await(release); return true;
                }));
                await(ready); second = pool.submit(() -> mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden()).andReturn());
            } else {
                first = pool.submit(() -> mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden()).andReturn());
                await(ready); second = pool.submit(verify::get);
            }
            awaitKnownBlocker(firstPid.get()); assertThat(financialFingerprint()).isEqualTo(before);
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select status from identity_guardian_link where id=?", String.class, pending)).isEqualTo("VERIFIED");
            assertThat(jdbc.queryForObject("select payer_id from payments_root where job_id=?", UUID.class, journey.job())).isEqualTo(oldGuardian.id());
            assertThat(jdbc.queryForObject("select legal_payer from jobs_job where id=?", UUID.class, journey.job())).isEqualTo(oldGuardian.id());
            assertThat(view(journey, journey.customer()).fundedMinor()).isZero();
            for (var denied : java.util.List.of(child, new AccountFixture(child.id(), newGuardian.session()))) {
                var substituted = new Journey(denied, journey.provider(), journey.job(), journey.quote());
                mvc.perform(fund(substituted, UUID.randomUUID(), 0)).andExpect(status().isBadRequest());
                mvc.perform(get("/payments/jobs/" + journey.job()).session(denied.session()).param("subject", child.id().toString())).andExpect(status().isForbidden());
            }
            mvc.perform(fund(journey, UUID.randomUUID(), 0)).andExpect(status().isForbidden());
            assertThat(financialFingerprint()).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from payments_operation o join payments_root r on r.id=o.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth g join payments_root r on r.id=g.root_id where r.job_id=?", Integer.class, journey.job())).isZero();
            if (!verificationFirst) { assertThat(intercepted).isTrue(); }
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void shouldDenyPostpaidCreditWithoutEnoughActualSeparateCapitalAndPreserveTheFirstAssent() throws Exception {
        var original = negotiate(); var provider = original.provider();
        UUID thread = jdbc.queryForObject("select conversation_id from jobs_job where id=?", UUID.class, original.job());
        mvc.perform(post("/agreements/compose").session(provider.session()).with(csrf()).param("command", UUID.randomUUID().toString()).param("job", original.job().toString())
                .param("subject", provider.id().toString()).param("kind", "CHANGE").param("scope", "Alcance sintético para probar respaldo independiente")
                .param("serviceAmount", "1000000.01").param("deliveryAmount", "0").param("deliverables", "Entrega sintética completa")).andExpect(status().isOk());
        var composition = (ar.changeo.agreements.AgreementController.Composition) provider.session().getAttribute("agreementComposition");
        String deadline = LocalDateTime.ofInstant(clock.instant().plusSeconds(86400), ZoneOffset.UTC).withSecond(0).withNano(0).toString();
        mvc.perform(multipart("/agreements/drafts").session(provider.session()).with(csrf()).param("stage", composition.command().toString()).param("arrangement", "REMOTE")
                .param("arrangementDescription", "Coordinación sintética remota").param("completionDeadline", deadline).param("feedbackRounds", "2")
                .param("completionEvidence", "TEXT").param("evidenceDescription", "Entrega sintética completa").param("reviewWindowMinutes", "60").param("label", "GENERAL")).andExpect(status().is3xxRedirection());
        var replacement = jdbc.queryForObject("select id,current_revision from agreements_quote where job_id=? and kind='CHANGE'", (row, index) ->
                new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), original.job());
        mvc.perform(post("/agreements/submit").session(provider.session()).with(csrf()).param("resource", replacement.resourceId().toString()).param("revision", replacement.revisionId().toString())
                .param("thread", thread.toString()).param("subject", provider.id().toString())).andExpect(status().is3xxRedirection());
        approve(replacement); assentChange(original, replacement, provider); assentChange(original, replacement, original.customer());
        var journey = new Journey(original.customer(), provider, original.job(), replacement);
        queueFund(journey, 0); drain(); complete(journey); release(journey); drain();
        var current = view(journey, journey.customer());
        assertThat(current.paidMinor()).isEqualTo(100000001);
        long available = jdbc.queryForObject("select available_minor from payments_capital where currency='ARS'", Long.class);
        long amount = Math.addExact(available, 1);
        assertThat(amount).isPositive().isLessThanOrEqualTo(current.remainingMinor());
        UUID command = UUID.randomUUID();
        mvc.perform(proposal("/payments/propose", journey, journey.customer(), current, command, null, amount, RefundDestination.CREDIT, true)).andExpect(status().is3xxRedirection());
        UUID settlement = jdbc.queryForObject("select outcome_id from payments_receipt where command_id=?", UUID.class, command);
        mvc.perform(proposal("/payments/assent", journey, journey.customer(), current, UUID.randomUUID(), settlement, amount, RefundDestination.CREDIT, true)).andExpect(status().is3xxRedirection());
        String before = financialFingerprint();
        mvc.perform(proposal("/payments/assent", journey, journey.provider(), current, UUID.randomUUID(), settlement, amount, RefundDestination.CREDIT, true)).andExpect(status().isForbidden());
        assertThat(financialFingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from payments_assent where settlement_id=?", Integer.class, settlement)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments_operation where settlement_id=?", Integer.class, settlement)).isZero();
        assertThat(view(journey, journey.customer()).refundedMinor()).isZero();
        UUID root = jdbc.queryForObject("select id from payments_root where job_id=? and lifecycle='ACTIVE'", UUID.class, journey.job());
        assertThat(jdbc.queryForObject("select sum(credit_minor)::bigint from payments_source where origin_root=?", Long.class, root)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_ledger where root_id=? and account='PLATFORM_RECOVERY_EXPENSE'", Integer.class, root)).isZero();
    }

    @Test
    void shouldKeepActualFundingLockWaitBeyondTwoSecondsBeforeAndAfterReadiness() throws Exception {
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        for (int probe = 0; probe < 2; probe++) {
            var journey = negotiate();
            view(journey, journey.customer());
            UUID root = jdbc.queryForObject("select id from payments_root where job_id=? and lifecycle='ACTIVE'", UUID.class, journey.job());
            UUID command = UUID.randomUUID();
            var request = fund(journey, command, 0);
            String before = financialFingerprint();
            var executor = Executors.newSingleThreadExecutor();
            long elapsed;
            try (var blocker = nativePool.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    int backend;
                    try (var statement = blocker.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
                        assertThat(rows.next()).isTrue(); backend = rows.getInt(1);
                    }
                    try (var statement = blocker.prepareStatement("select id from payments_root where id=? for update")) {
                        statement.setObject(1, root);
                        try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                    }
                    long started = System.nanoTime();
                    var waiting = executor.submit(() -> mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn());
                    awaitKnownBlocker(backend);
                    while (System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(2100)) {
                        assertThat(waiting.isDone()).isFalse();
                        Thread.sleep(20);
                    }
                    assertThat(financialFingerprint()).isEqualTo(before);
                    assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=?", Integer.class, root)).isZero();
                    blocker.commit();
                    waiting.get(5, TimeUnit.SECONDS);
                    elapsed = System.nanoTime() - started;
                } finally {
                    blocker.rollback();
                    executor.shutdownNow();
                    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                }
            }
            assertThat(elapsed).isGreaterThan(TimeUnit.SECONDS.toNanos(2));
            assertThat(jdbc.queryForObject("select count(*) from payments_operation where command_id=?", Integer.class, command)).isEqualTo(1);
            UUID operation = jdbc.queryForObject("select id from payments_operation where command_id=?", UUID.class, command);
            drain(); assertFundingBacking(journey, operation);
            assertThat(jdbc.queryForObject("select count(*) from payments_gateway_truth where root_id=?", Integer.class, root)).isEqualTo(1);
            assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345);
            mvc.perform(get("/readyz")).andExpect(status().isOk()).andExpect(content().string("ready\n"));
            receipt("native-owner-lock-" + probe, "\"actualPostgresBlocker\":true,\"elapsedNanos\":" + elapsed + ",\"exactlyOneFundingEffect\":true");
        }
    }

    @Test
    void shouldBoundNativePoolAcquisitionDenyPartialFundingAndRecoverInTheSameContext() throws Exception {
        var journey = negotiate();
        view(journey, journey.customer());
        UUID command = UUID.randomUUID();
        var request = fund(journey, command, 0);
        String before = financialFingerprint();
        mvc.perform(get("/readyz")).andExpect(status().isOk());
        var leases = new ArrayList<Connection>();
        long started;
        long elapsed;
        try {
            for (int index = 0; index < nativePool.getMaximumPoolSize(); index++) { leases.add(nativePool.getConnection()); }
            assertThat(nativePool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(nativePool.getMaximumPoolSize());
            started = System.nanoTime();
            assertThatThrownBy(() -> mvc.perform(request)).hasRootCauseInstanceOf(SQLTransientConnectionException.class);
            elapsed = System.nanoTime() - started;
            assertThat(elapsed).isBetween(TimeUnit.MILLISECONDS.toNanos(400), TimeUnit.SECONDS.toNanos(2));
            mvc.perform(get("/readyz")).andExpect(status().isServiceUnavailable()).andExpect(content().string("unavailable\n"));
        } finally {
            for (var lease : leases) { lease.close(); }
        }
        assertThat(financialFingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from payments_operation where command_id=?", Integer.class, command)).isZero();
        mvc.perform(get("/readyz")).andExpect(status().isOk());
        mvc.perform(fund(journey, command, 0)).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select count(*) from payments_operation where command_id=?", Integer.class, command)).isEqualTo(1);
        UUID operation = jdbc.queryForObject("select id from payments_operation where command_id=?", UUID.class, command);
        drain(); assertFundingBacking(journey, operation);
        assertThat(view(journey, journey.customer()).fundedMinor()).isEqualTo(12345);
        receipt("native-pool-acquisition", "\"elapsedNanos\":" + elapsed + ",\"readiness503WhileExhausted\":true,\"noPartialFunding\":true,\"sameContextRecovery\":true");
    }

    private void receipt(String name, String facts) throws Exception {
        var fixture = CurrentFinancialTestFixture.supplied();
        Path evidence = fixture.evidence();
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(name + "-" + UUID.randomUUID() + ".json"),
                "{" + facts + ",\"provisionedUnderR0\":true,\"fixtureContract\":\"" + fixture.contract() + "\",\"privateValuesExcluded\":true}\n", StandardOpenOption.CREATE_NEW);
    }
}
