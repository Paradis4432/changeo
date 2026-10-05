package ar.changeo.identity;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.api.*;
import ar.changeo.security.AccountPrincipal;
import ar.changeo.security.SessionProof;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {ChangeoApplication.class, IdentityPostgresTest.TestConfig.class}, properties = {"spring.flyway.schemas=foundation_test", "spring.flyway.default-schema=foundation_test", "spring.jpa.properties.hibernate.default_schema=foundation_test", "spring.datasource.hikari.schema=foundation_test", "changeo.synthetic-evidence=true", "changeo.mailbox=.runtime/test-mailbox", "changeo.mfa-key=.runtime/test-mfa.key"})
@AutoConfigureMockMvc
class IdentityPostgresTest {
    @Autowired AccountService accounts;
    @Autowired PermissionService access;
    @Autowired GuardianService guardians;
    @Autowired AdminService admin;
    @Autowired AdminSecurityService mfa;
    @Autowired OperatorService operator;
    @Autowired FactorCipher cipher;
    @Autowired IdentityStore store;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @MockitoBean SessionProof proof;
    @MockitoBean TokenDelivery delivery;
    private final Map<String, String> delivered = new ConcurrentHashMap<>();
    private UUID administrator;
    private byte[] administratorSecret;

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() throws Exception {
        clock.reset(); delivered.clear(); reset(proof, delivery);
        when(proof.mfa(any(), anyLong())).thenReturn(true);
        when(proof.freshPassword(any(), anyLong())).thenReturn(true);
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> {
            delivered.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true;
        });
        Path enrollment = Path.of(".runtime", "test-enrollment-" + UUID.randomUUID());
        String contact = "admin" + UUID.randomUUID() + "@example.test";
        operator.provision(contact, Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "AUDIT_READER"), enrollment);
        administrator = accounts.credentials(contact).orElseThrow().id();
        administratorSecret = tx(() -> cipher.decrypt(store.find(AdminMfa.class, administrator).encryptedSecret));
        Files.delete(enrollment);
    }

    private UUID register() {
        return accounts.register("test" + UUID.randomUUID() + "@example.test", "Test-password-12345").accountId().value();
    }
    private String token(UUID id, String purpose) { return delivered.get(id + ":" + purpose); }
    private UUID verified(int age) {
        UUID id = register(); accounts.verifyContact(id, token(id, "CONTACT"));
        admin.evidence(administrator, id, "AGE", age); clock.advance(1); return id;
    }
    private UUID link(UUID guardian, UUID minor) {
        admin.evidence(administrator, minor, "MINOR_DRAFT", null);
        UUID link = guardians.request(minor, guardian, minor);
        admin.reviewGuardian(administrator, link, 0, true); return link;
    }
    private PermissionRequest request(UUID actor, UUID subject, Capability capability, JobContext job) {
        return new PermissionRequest(Optional.of(new ActorId(actor)), Optional.of(new SubjectId(subject)), capability, Optional.ofNullable(job));
    }
    private <T> T tx(java.util.function.Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private void txRun(Runnable action) { tx(() -> { action.run(); return null; }); }

    @Test void shouldVerifyOnlyOwnCurrentContactTokenAndPreservePendingFailure() {
        UUID first = register(); UUID second = register(); String old = token(first, "CONTACT");
        assertThatThrownBy(() -> accounts.verifyContact(second, old)).isInstanceOf(IdentityFailure.class);
        accounts.resend(first); String current = token(first, "CONTACT");
        assertThatThrownBy(() -> accounts.verifyContact(first, old)).isInstanceOf(IdentityFailure.class);
        accounts.verifyContact(first, current);
        assertThatThrownBy(() -> accounts.verifyContact(first, current)).isInstanceOf(IdentityFailure.class);
        assertThat(tx(() -> store.account(first).contactVerified)).isTrue();
        UUID expired = register(); clock.advance(1801);
        assertThatThrownBy(() -> accounts.verifyContact(expired, token(expired, "CONTACT"))).isInstanceOf(IdentityFailure.class);
        when(delivery.deliver(any(), anyString(), anyString())).thenReturn(false);
        UUID failed = register(); assertThat(accounts.resend(failed)).isFalse();
        assertThat(tx(() -> store.account(failed).contactVerified)).isFalse();
    }

    @Test void shouldValidateSyntheticContactsDuplicatesAndPasswords() {
        assertThatThrownBy(() -> accounts.register("real@example.com", "Test-password-12345")).isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> accounts.register("test@example.test", "short")).isInstanceOf(IdentityFailure.class);
        String contact = "duplicate" + UUID.randomUUID() + "@example.test";
        accounts.register(contact.toUpperCase(Locale.ROOT), "Test-password-12345");
        assertThatThrownBy(() -> accounts.register(contact, "Test-password-12345")).isInstanceOf(IdentityFailure.class);
        assertThat(accounts.credentials(contact).orElseThrow().passwordHash()).doesNotContain("Test-password");
    }

    @Test void shouldConsumeRecoveryOnceAndInvalidateAllPreviousGenerationsPreservingMfa() {
        String adminContact = tx(() -> store.account(administrator).contact);
        long epoch = accounts.epoch(administrator);
        accounts.forgot(adminContact); String first = token(administrator, "RECOVERY");
        accounts.forgot(adminContact); String second = token(administrator, "RECOVERY");
        assertThatThrownBy(() -> accounts.reset(first, "New-password-12345")).isInstanceOf(IdentityFailure.class);
        accounts.reset(second, "New-password-12345");
        assertThat(accounts.epoch(administrator)).isEqualTo(epoch + 1);
        assertThatThrownBy(() -> accounts.reset(second, "New-password-12345")).isInstanceOf(IdentityFailure.class);
        assertThat(tx(() -> store.find(AdminMfa.class, administrator).encryptedSecret)).isNotBlank();
        UUID expired = register(); String contact = tx(() -> store.account(expired).contact);
        accounts.forgot(contact); clock.advance(901);
        assertThatThrownBy(() -> accounts.reset(token(expired, "RECOVERY"), "New-password-12345")).isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> accounts.reset(token(expired, "CONTACT"), "New-password-12345")).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldAllowOnlyOneConcurrentTokenConsumption() throws Exception {
        UUID account = register(); String contactToken = token(account, "CONTACT");
        assertThat(parallelSuccess(() -> accounts.verifyContact(account, contactToken))).isEqualTo(1);
        String contact = tx(() -> store.account(account).contact); accounts.forgot(contact);
        assertThat(parallelSuccess(() -> accounts.reset(token(account, "RECOVERY"), "New-password-12345"))).isEqualTo(1);
    }

    @Test void shouldEnforceAdultMinorProviderGuardianAndExactPayerMatrix() {
        UUID unknown = register(); accounts.verifyContact(unknown, token(unknown, "CONTACT"));
        admin.evidence(administrator, unknown, "OPTIONAL_BADGE", null);
        assertThat(access.decide(request(unknown, unknown, Capability.DRAFT_REQUEST, null)).allowed()).isFalse();
        UUID adult = verified(30); UUID minor = verified(17); UUID under16 = verified(15);
        assertThat(access.decide(request(adult, adult, Capability.VIEW_ADULT, null)).allowed()).isFalse();
        accounts.preferences(adult, true, true);
        assertThat(access.decide(request(adult, adult, Capability.VIEW_ADULT, null)).allowed()).isTrue();
        UUID link = link(adult, minor); link(adult, under16);
        admin.evidence(administrator, minor, "PROVIDER_ELIGIBILITY", null);
        admin.evidence(administrator, under16, "PROVIDER_ELIGIBILITY", null);
        assertThat(access.decide(request(minor, minor, Capability.DRAFT_REQUEST, null)).allowed()).isTrue();
        assertThat(access.decide(request(minor, minor, Capability.SEND_MARKETPLACE_MESSAGE, null)).allowed()).isFalse();
        assertThat(access.decide(request(adult, minor, Capability.NEW_PROVIDER_PARTICIPATION, null)).allowed()).isTrue();
        assertThat(access.decide(request(adult, under16, Capability.NEW_PROVIDER_PARTICIPATION, null)).allowed()).isFalse();
        assertThat(access.decide(request(adult, minor, Capability.VIEW_ADULT, null)).allowed()).isFalse();
        JobContext local = new JobContext(UUID.randomUUID(), JobContext.FulfillmentMode.LOCAL);
        assertThat(access.decide(request(adult, minor, Capability.ACCEPT_AGREEMENT, local)).allowed()).isFalse();
        guardians.grant(adult, link, local.jobId());
        PermissionDecision decision = access.decide(request(adult, minor, Capability.ACCEPT_AGREEMENT, local));
        assertThat(decision.allowed()).isTrue(); assertThat(decision.legalPayerId()).contains(new AccountId(adult));
        assertThat(access.decide(request(adult, minor, Capability.NEW_REQUEST, null)).legalPayerId()).isEmpty();
        assertThat(access.decide(request(adult, minor, Capability.ACCEPT_AGREEMENT, new JobContext(UUID.randomUUID(), JobContext.FulfillmentMode.LOCAL))).allowed()).isFalse();
        assertThat(access.decide(request(adult, minor, Capability.GRANT_IN_PERSON_CONSENT, new JobContext(local.jobId(), JobContext.FulfillmentMode.REMOTE))).allowed()).isFalse();
        assertThat(access.decide(request(adult, adult, Capability.ACCESS_SUPPORT, local)).allowed()).isFalse();
        assertThat(access.decide(new PermissionRequest(Optional.empty(), Optional.of(new SubjectId(adult)), Capability.BROWSE_GENERAL, Optional.empty())).allowed()).isFalse();
        assertThat(access.decide(new PermissionRequest(Optional.empty(), Optional.empty(), Capability.BROWSE_GENERAL, Optional.empty())).allowed()).isTrue();
        assertThatThrownBy(() -> access.requireForUpdate(request(adult, adult, Capability.NEW_REQUEST, null))).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldKeepControlledOwnRightsAfterRestrictionRevocationExpiryAndRelink() {
        UUID first = verified(35); UUID second = verified(32); UUID minor = verified(17);
        UUID link = link(first, minor); JobContext job = new JobContext(UUID.randomUUID(), JobContext.FulfillmentMode.LOCAL);
        guardians.grant(first, link, job.jobId()); guardians.revoke(minor, link, 1);
        admin.restriction(administrator, minor, true);
        assertThat(access.decide(request(minor, minor, Capability.ACCESS_SUPPORT, null)).allowed()).isTrue();
        assertThat(access.decide(request(minor, minor, Capability.ACCESS_FINANCIAL_RIGHTS, job)).allowed()).isTrue();
        assertThat(access.decide(request(first, minor, Capability.ACCESS_FINANCIAL_RIGHTS, job)).allowed()).isFalse();
        assertThat(access.decide(request(minor, minor, Capability.ACCESS_EXISTING_OBLIGATION, job)).allowed()).isTrue();
        assertThat(access.decide(request(minor, minor, Capability.DRAFT_REQUEST, null)).allowed()).isFalse();
        admin.restriction(administrator, minor, false); UUID replacement = link(second, minor);
        assertThat(replacement).isNotEqualTo(link);
        assertThat(access.decide(request(second, minor, Capability.ACCEPT_AGREEMENT, job)).allowed()).isFalse();
        assertThat(access.decide(request(first, first, Capability.ACCESS_EXISTING_OBLIGATION, job)).legalPayerId()).isEmpty();
        clock.advance(86401);
        assertThat(access.decide(request(second, minor, Capability.NEW_REQUEST, null)).allowed()).isFalse();
        assertThat(access.decide(request(first, first, Capability.ACCESS_FINANCIAL_RIGHTS, job)).allowed()).isTrue();
    }

    @Test void shouldKeepGuardianTerminalStatesAndRequireCurrentVersionIndependentEvidence() {
        UUID adult = verified(30); UUID minor = verified(17);
        assertThatThrownBy(() -> guardians.request(adult, adult, adult)).isInstanceOf(IdentityFailure.class);
        UUID pending = guardians.request(minor, adult, minor);
        assertThatThrownBy(() -> guardians.request(minor, adult, minor)).isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> guardians.grant(adult, pending, UUID.randomUUID())).isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> admin.reviewGuardian(administrator, pending, 0, true)).isInstanceOf(IdentityFailure.class);
        guardians.revoke(minor, pending, 0);
        assertThatThrownBy(() -> admin.reviewGuardian(administrator, pending, 0, true)).isInstanceOf(IdentityFailure.class);
        UUID replacement = link(adult, minor);
        assertThatThrownBy(() -> guardians.revoke(adult, replacement, 0)).isInstanceOf(IdentityFailure.class);
        UUID consent = guardians.grant(adult, replacement, UUID.randomUUID());
        guardians.revokeConsent(adult, replacement, consent);
        assertThatThrownBy(() -> guardians.revokeConsent(adult, replacement, consent)).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldSerializePendingGuardianReviewAgainstRevocationWithoutRevivingTerminalAuthority() throws Exception {
        UUID adult = verified(30); UUID minor = verified(17);
        admin.evidence(administrator, minor, "MINOR_DRAFT", null);
        UUID pending = guardians.request(minor, adult, minor);
        CountDownLatch start = new CountDownLatch(1); AtomicInteger successful = new AtomicInteger();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> approve = workers.submit(() -> { await(start); try { admin.reviewGuardian(administrator, pending, 0, true); successful.incrementAndGet(); } catch (IdentityFailure expected) { } });
            Future<?> revoke = workers.submit(() -> { await(start); try { guardians.revoke(minor, pending, 0); successful.incrementAndGet(); } catch (IdentityFailure expected) { } });
            start.countDown(); approve.get(10, TimeUnit.SECONDS); revoke.get(10, TimeUnit.SECONDS);
        }
        assertThat(successful.get()).isEqualTo(1);
        GuardianService.LinkView current = guardians.own(minor).stream().filter(l -> l.id().equals(pending)).findFirst().orElseThrow();
        assertThat(current.status()).isIn("VERIFIED", "REVOKED");
        if (current.status().equals("VERIFIED")) { guardians.revoke(minor, pending, current.version()); }
        assertThatThrownBy(() -> admin.reviewGuardian(administrator, pending, current.version(), true)).isInstanceOf(IdentityFailure.class);
        assertThat(guardians.own(minor).stream().filter(l -> l.id().equals(pending)).findFirst().orElseThrow().status()).isEqualTo("REVOKED");
    }

    @Test void shouldConsumeMfaCounterAtomicallyAndKeepReplayProtectionAcrossServiceInstances() throws Exception {
        String code = Totp.code(administratorSecret, clock.instant().getEpochSecond() / 30, 6);
        assertThat(parallelSuccess(() -> mfa.verify(administrator, code, "test-source"))).isEqualTo(1);
        AdminSecurityService restarted = new AdminSecurityService(store, cipher, org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder(), new AbuseLimits(clock), clock);
        assertThatThrownBy(() -> tx(() -> restarted.verify(administrator, code, "other-source"))).isInstanceOf(IdentityFailure.class);
        clock.advance(120);
        assertThatThrownBy(() -> mfa.verify(administrator, code, "test-source")).isInstanceOf(IdentityFailure.class);
        assertThatThrownBy(() -> mfa.verify(administrator, "000000", "test-source")).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldRequireCurrentRoleMfaFreshAuthAndPreventSelfElevation() {
        UUID target = register();
        when(proof.mfa(any(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> admin.evidence(administrator, target, "AGE", 30)).isInstanceOf(IdentityFailure.class);
        when(proof.mfa(any(), anyLong())).thenReturn(true); when(proof.freshPassword(any(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> admin.restriction(administrator, target, true)).isInstanceOf(IdentityFailure.class);
        when(proof.freshPassword(any(), anyLong())).thenReturn(true);
        assertThatThrownBy(() -> admin.evidence(administrator, administrator, "AGE", 30)).isInstanceOf(IdentityFailure.class);
        txRun(() -> store.account(administrator).roles.remove(AdminRole.RESTRICTION_ADMIN));
        assertThatThrownBy(() -> admin.restriction(administrator, target, true)).isInstanceOf(IdentityFailure.class);
        assertThat(admin.audit(administrator)).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from identity_audit_event where actor_id=? and action='LOCAL_OPERATOR_PROVISIONED'", Integer.class, administrator)).isEqualTo(1);
        assertThatThrownBy(() -> txRun(() -> jdbc.update("update identity_audit_event set action='rewritten' where actor_id=?", administrator))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void shouldKeepMailboxAndFactorMaterialPrivateAndFailClosedOnBadKey() throws Exception {
        Path directory = Files.createTempDirectory("changeo-mailbox-");
        LocalMailbox mailbox = new LocalMailbox(directory.toString());
        assertThat(mailbox.deliver(administrator, "CONTACT", "secret-material")).isTrue();
        Path file;
        try (var files = Files.list(directory)) { file = files.findFirst().orElseThrow(); }
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path blocked = Files.createTempFile("changeo-blocked-mailbox-", "");
        assertThat(new LocalMailbox(blocked.toString()).deliver(administrator, "CONTACT", "hidden")).isFalse();
        String factor = tx(() -> store.find(AdminMfa.class, administrator).encryptedSecret);
        assertThat(factor).doesNotContain(Base64.getEncoder().encodeToString(administratorSecret));
        assertThatThrownBy(() -> new FactorCipher(directory.resolve("missing.key").toString()).decrypt(factor)).isInstanceOf(IdentityFailure.class);
        byte[] wrong = new byte[32]; new java.security.SecureRandom().nextBytes(wrong); Path wrongKey = directory.resolve("wrong.key"); PrivateFiles.writeNew(wrongKey, wrong);
        assertThatThrownBy(() -> new FactorCipher(wrongKey.toString()).decrypt(factor)).isInstanceOf(IdentityFailure.class);
        assertThat(admin.audit(administrator).toString()).doesNotContain("secret-material", "passwordHash", factor);
    }

    @Test void shouldEnforceCsrfHttpOwnershipAdminAndEpochOnEveryOldSession() throws Exception {
        UUID account = register(); String contact = tx(() -> store.account(account).contact);
        var credentials = accounts.credentials(contact).orElseThrow();
        AccountPrincipal principal = new AccountPrincipal(account, contact, credentials.passwordHash(), credentials.epoch());
        mvc.perform(post("/account/preferences").with(user(principal)).param("sensitive", "true")).andExpect(status().isForbidden());
        when(proof.actor()).thenReturn(Optional.of(account));
        mvc.perform(get("/account").with(user(principal)).param("actor", administrator.toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(account.toString())));
        when(proof.mfa(any(), anyLong())).thenReturn(false);
        mvc.perform(get("/admin/accounts").with(user(principal))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/mfa/enroll").with(user(principal)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/.runtime/mailbox").with(user(principal))).andExpect(status().isNotFound());
        MockHttpSession first = session(principal); MockHttpSession second = session(principal);
        accounts.forgot(contact); accounts.reset(token(account, "RECOVERY"), "New-password-12345");
        mvc.perform(get("/account").session(first)).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(get("/account").session(second)).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(post("/password/forgot").with(csrf()).param("contact", "missing@example.test")).andExpect(status().is3xxRedirection());
    }

    @Test void shouldSerializeProtectedCommitsAgainstRevocationRestrictionsAndRoleRemoval() throws Exception {
        jdbc.execute("create table if not exists foundation_test_intent (id uuid primary key)");
        for (String transition : List.of("revoke", "guardian-restriction", "minor-restriction", "role-removal")) {
            for (boolean guardWins : List.of(true, false)) {
                UUID adult = verified(30); UUID minor = verified(17); UUID link = link(adult, minor);
                PermissionRequest permission = request(minor, minor, Capability.DRAFT_REQUEST, null);
                Runnable mutation = switch (transition) {
                    case "revoke" -> () -> guardians.revoke(adult, link, 1);
                    case "guardian-restriction" -> () -> admin.restriction(administrator, adult, true);
                    case "minor-restriction" -> () -> admin.restriction(administrator, minor, true);
                    default -> () -> admin.removeRole(administrator, administrator, "IDENTITY_ADMIN");
                };
                if (transition.equals("role-removal")) { permission = request(administrator, administrator, Capability.ADMIN_IDENTITY, null); }
                PermissionRequest guarded = permission;
                UUID intent = UUID.randomUUID(); CountDownLatch held = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
                CountDownLatch contenderStarted = new CountDownLatch(1);
                try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                    Future<?> first = workers.submit(() -> txRun(() -> {
                        if (guardWins) { access.requireForUpdate(guarded); jdbc.update("insert into foundation_test_intent(id) values (?)", intent); }
                        else { mutation.run(); }
                        held.countDown(); await(release);
                    }));
                    await(held);
                    Future<?> second = workers.submit(() -> {
                        contenderStarted.countDown();
                        if (guardWins) { mutation.run(); }
                        else {
                            assertThatThrownBy(() -> txRun(() -> {
                                jdbc.update("insert into foundation_test_intent(id) values (?)", intent);
                                access.requireForUpdate(guarded);
                            })).as(transition + " revocation committed first").isInstanceOf(IdentityFailure.class);
                        }
                    });
                    await(contenderStarted); release.countDown();
                    first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
                }
                assertThat(jdbc.queryForObject("select count(*) from foundation_test_intent where id=?", Integer.class, intent)).isEqualTo(guardWins ? 1 : 0);
                assertThat(access.decide(guarded).allowed()).isFalse();
                if (transition.equals("role-removal")) {
                    txRun(() -> { store.lockAccounts(List.of(administrator)); store.account(administrator).roles.add(AdminRole.IDENTITY_ADMIN); });
                }
            }
        }
    }

    @Test void shouldRollbackDeniedProtectedIntentEvenIfCallerCatchesFailure() {
        jdbc.execute("create table if not exists foundation_test_intent (id uuid primary key)");
        UUID account = register(); UUID intent = UUID.randomUUID();
        assertThatThrownBy(() -> txRun(() -> {
            jdbc.update("insert into foundation_test_intent(id) values (?)", intent);
            try { access.requireForUpdate(request(account, account, Capability.NEW_REQUEST, null)); }
            catch (IdentityFailure expected) { }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from foundation_test_intent where id=?", Integer.class, intent)).isZero();
    }

    @Test void shouldGrantOnlyExactFocusedModerationRolesForKnownSelfWithoutJobContext() throws Exception {
        Capability review = Capability.valueOf("ADMIN_MODERATION_REVIEW");
        Capability audit = Capability.valueOf("ADMIN_MODERATION_AUDIT");
        UUID reviewer = provision(Set.of("MODERATION_REVIEWER"));
        UUID auditor = provision(Set.of("MODERATION_AUDITOR"));
        UUID combined = provision(Set.of("MODERATION_REVIEWER", "MODERATION_AUDITOR", "AUDIT_READER"));
        UUID other = register();
        for (Capability capability : List.of(review, audit)) {
            UUID focused = capability == review ? reviewer : auditor;
            UUID wrongRole = capability == review ? auditor : reviewer;
            assertThat(access.decide(request(focused, focused, capability, null)).allowed()).isTrue();
            assertThat(access.decide(request(combined, combined, capability, null)).allowed()).isTrue();
            assertThat(access.decide(request(wrongRole, wrongRole, capability, null)).allowed()).isFalse();
            assertThat(access.decide(request(administrator, administrator, capability, null)).allowed()).isFalse();
            assertThat(access.decide(request(other, other, capability, null)).allowed()).isFalse();
            UUID unknown = UUID.randomUUID();
            assertThat(access.decide(request(unknown, unknown, capability, null)).allowed()).isFalse();
            assertThat(access.decide(request(focused, other, capability, null)).allowed()).isFalse();
            assertThat(access.decide(request(focused, focused, capability, new JobContext(UUID.randomUUID(), JobContext.FulfillmentMode.REMOTE))).allowed()).isFalse();
            assertThat(access.decide(new PermissionRequest(Optional.empty(), Optional.empty(), capability, Optional.empty())).allowed()).isFalse();
            assertThat(access.decide(new PermissionRequest(Optional.of(new ActorId(focused)), Optional.empty(), capability, Optional.empty())).allowed()).isFalse();
            assertThat(access.decide(new PermissionRequest(Optional.empty(), Optional.of(new SubjectId(focused)), capability, Optional.empty())).allowed()).isFalse();
        }
        for (UUID focused : List.of(reviewer, auditor)) {
            for (Capability old : List.of(Capability.ADMIN_IDENTITY, Capability.ADMIN_RESTRICTIONS, Capability.ADMIN_AUDIT)) {
                assertThat(access.decide(request(focused, focused, old, null)).allowed()).isFalse();
            }
            assertThat(access.decide(request(focused, focused, Capability.VIEW_ADULT, null)).allowed()).isFalse();
            assertThat(access.decide(request(focused, focused, Capability.SYNTHETIC_FUNDING_ELIGIBILITY,
                    new JobContext(UUID.randomUUID(), JobContext.FulfillmentMode.REMOTE))).allowed()).isFalse();
        }
        assertThat(access.decide(request(combined, combined, Capability.ADMIN_AUDIT, null)).allowed()).isTrue();
        assertThat(access.decide(request(combined, combined, Capability.ADMIN_IDENTITY, null)).allowed()).isFalse();
    }

    @Test void shouldRequireMfaForBothModerationRolesAndFreshPasswordOnlyForReview() throws Exception {
        UUID focused = provision(Set.of("MODERATION_REVIEWER", "MODERATION_AUDITOR"));
        PermissionRequest review = request(focused, focused, Capability.valueOf("ADMIN_MODERATION_REVIEW"), null);
        PermissionRequest audit = request(focused, focused, Capability.valueOf("ADMIN_MODERATION_AUDIT"), null);
        when(proof.mfa(any(), anyLong())).thenReturn(false);
        assertThat(access.decide(review).allowed()).isFalse();
        assertThat(access.decide(audit).allowed()).isFalse();
        when(proof.mfa(any(), anyLong())).thenReturn(true);
        when(proof.freshPassword(any(), anyLong())).thenReturn(false);
        assertThat(access.decide(review).allowed()).isFalse();
        assertThat(access.decide(audit).allowed()).isTrue();
        when(proof.freshPassword(any(), anyLong())).thenReturn(true);
        admin.restriction(administrator, focused, true);
        assertThat(access.decide(review).allowed()).isFalse();
        assertThat(access.decide(audit).allowed()).isFalse();
        admin.restriction(administrator, focused, false);
        assertThat(tx(() -> access.requireForUpdate(review)).allowed()).isTrue();
        assertThat(tx(() -> access.requireForUpdate(audit)).allowed()).isTrue();
        assertThatThrownBy(() -> access.requireForUpdate(review)).isInstanceOf(IdentityFailure.class);
    }

    @Test void shouldPreserveIndependentlyEstablishedOrdinaryEligibilityForModerationOperators() throws Exception {
        UUID reviewer = provision(Set.of("MODERATION_REVIEWER"));
        assertThat(access.decide(request(reviewer, reviewer, Capability.NEW_REQUEST, null)).allowed()).isFalse();
        accounts.resend(reviewer); accounts.verifyContact(reviewer, token(reviewer, "CONTACT"));
        admin.evidence(administrator, reviewer, "AGE", 30);
        assertThat(access.decide(request(reviewer, reviewer, Capability.NEW_REQUEST, null)).allowed()).isTrue();
        assertThat(access.decide(request(reviewer, reviewer, Capability.SYNTHETIC_FUNDING_ELIGIBILITY,
                new JobContext(UUID.randomUUID(), JobContext.FulfillmentMode.REMOTE))).allowed()).isTrue();
        assertThat(access.decide(request(reviewer, reviewer, Capability.VIEW_ADULT, null)).allowed()).isFalse();
        accounts.preferences(reviewer, false, true);
        assertThat(access.decide(request(reviewer, reviewer, Capability.VIEW_ADULT, null)).allowed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODERATION_REVIEWER", "MODERATION_AUDITOR"})
    void shouldProvisionFocusedRolePrivatelyAndRemoveItWithEpochAndAudit(String role) throws Exception {
        UUID focused = provision(Set.of(role));
        UUID another = provision(Set.of(role));
        assertThat(tx(() -> store.account(focused).roles.stream().map(Enum::name).toList())).containsExactly(role);
        assertThat(tx(() -> store.find(AdminMfa.class, focused).encryptedSecret))
                .isNotEqualTo(tx(() -> store.find(AdminMfa.class, another).encryptedSecret));
        Path invalid = Path.of(".runtime", "f3-invalid-" + UUID.randomUUID());
        assertThatThrownBy(() -> operator.provision("invalid" + UUID.randomUUID() + "@example.test", Set.of("MODERATION_ADMIN"), invalid))
                .isInstanceOf(IdentityFailure.class);
        assertThat(Files.exists(invalid)).isFalse();
        long epoch = accounts.epoch(focused);
        admin.removeRole(administrator, focused, role);
        assertThat(accounts.epoch(focused)).isEqualTo(epoch + 1);
        assertThat(tx(() -> store.account(focused).roles)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from identity_audit_event where actor_id=? and subject_id=? and action=?",
                Integer.class, administrator, focused, "ROLE_REMOVED_" + role)).isEqualTo(1);
        Capability capability = Capability.valueOf(role.equals("MODERATION_REVIEWER") ? "ADMIN_MODERATION_REVIEW" : "ADMIN_MODERATION_AUDIT");
        assertThat(access.decide(request(focused, focused, capability, null)).allowed()).isFalse();
    }

    @Test void shouldSerializeModerationReviewIntentAgainstRoleRemovalAndRollbackCaughtDenial() throws Exception {
        jdbc.execute("create table if not exists foundation_f3_intent (id uuid primary key)");
        for (boolean guardWins : List.of(true, false)) {
            UUID reviewer = provision(Set.of("MODERATION_REVIEWER"));
            PermissionRequest guarded = request(reviewer, reviewer, Capability.valueOf("ADMIN_MODERATION_REVIEW"), null);
            UUID intent = UUID.randomUUID();
            CountDownLatch held = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
            CountDownLatch contenderStarted = new CountDownLatch(1);
            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                Future<?> first = workers.submit(() -> txRun(() -> {
                    if (guardWins) { access.requireForUpdate(guarded); jdbc.update("insert into foundation_f3_intent values (?)", intent); }
                    else { admin.removeRole(administrator, reviewer, "MODERATION_REVIEWER"); }
                    held.countDown(); await(release);
                }));
                await(held);
                Future<?> second = workers.submit(() -> {
                    contenderStarted.countDown();
                    if (guardWins) { admin.removeRole(administrator, reviewer, "MODERATION_REVIEWER"); }
                    else {
                        assertThatThrownBy(() -> txRun(() -> {
                            jdbc.update("insert into foundation_f3_intent values (?)", intent); access.requireForUpdate(guarded);
                        })).isInstanceOf(IdentityFailure.class);
                    }
                });
                await(contenderStarted); release.countDown();
                first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            }
            assertThat(jdbc.queryForObject("select count(*) from foundation_f3_intent where id=?", Integer.class, intent)).isEqualTo(guardWins ? 1 : 0);
            assertThat(access.decide(guarded).allowed()).isFalse();
            UUID caught = UUID.randomUUID();
            assertThatThrownBy(() -> txRun(() -> {
                jdbc.update("insert into foundation_f3_intent values (?)", caught);
                try { access.requireForUpdate(guarded); } catch (IdentityFailure expected) { }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(jdbc.queryForObject("select count(*) from foundation_f3_intent where id=?", Integer.class, caught)).isZero();
        }
    }

    private UUID provision(Set<String> roles) throws Exception {
        String contact = "f3operator" + UUID.randomUUID() + "@example.test";
        Path enrollment = Path.of(".runtime", "f3-enrollment-" + UUID.randomUUID());
        try {
            operator.provision(contact, roles, enrollment);
            assertThat(Files.getPosixFilePermissions(enrollment)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            return accounts.credentials(contact).orElseThrow().id();
        } finally { Files.deleteIfExists(enrollment); }
    }

    @Test void shouldUpgradePopulatedIdentityRolesWithoutChangingOriginalRowsKeysOrChecksums() throws Exception {
        IdentityMigrationAssertions.assertPopulatedUpgrade(jdbc.getDataSource());
    }

    private MockHttpSession session(AccountPrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())));
        return session;
    }

    private int parallelSuccess(Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1); AtomicInteger successes = new AtomicInteger();
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) { tasks.add(workers.submit(() -> {
                await(start); try { action.run(); successes.incrementAndGet(); } catch (IdentityFailure expected) { }
            })); }
            start.countDown(); for (Future<?> task : tasks) { task.get(10, TimeUnit.SECONDS); }
        }
        return successes.get();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Barrier timed out"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
