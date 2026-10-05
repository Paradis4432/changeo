package ar.changeo.identity;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.api.*;
import ar.changeo.security.AccountPrincipal;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {ChangeoApplication.class, IdentityPostgresTest.TestConfig.class, WebsiteSecurityTest.F3Consumer.class}, properties = {"spring.flyway.schemas=foundation_web_test", "spring.flyway.default-schema=foundation_web_test", "spring.jpa.properties.hibernate.default_schema=foundation_web_test", "spring.datasource.hikari.schema=foundation_web_test", "changeo.mfa-key=.runtime/web-mfa.key", "changeo.mailbox=.runtime/web-mailbox", "changeo.synthetic-evidence=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class WebsiteSecurityTest {
    private static final AtomicInteger SOURCE_SEQUENCE = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operator;
    @Autowired IdentityStore store;
    @Autowired FactorCipher cipher;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private UUID administrator;
    private String password;
    private byte[] secret;
    private String contact;

    @BeforeEach void setup() throws Exception {
        clock.reset(); contact = "webadmin" + UUID.randomUUID() + "@example.test";
        Path enrollment = Path.of(".runtime", "web-enrollment-" + UUID.randomUUID());
        operator.provision(contact, Set.of("IDENTITY_ADMIN", "AUDIT_READER"), enrollment);
        password = Files.readAllLines(enrollment).stream().filter(line -> line.startsWith("password=")).map(line -> line.substring(9)).findFirst().orElseThrow();
        Files.delete(enrollment); administrator = accounts.credentials(contact).orElseThrow().id();
        secret = new TransactionTemplate(transactions).execute(status -> cipher.decrypt(store.find(AdminMfa.class, administrator).encryptedSecret));
    }

    @Test void shouldLoginLogoutAndRequireRealSessionMfaFreshPasswordCurrentEpochAndRoles() throws Exception {
        var login = mvc.perform(post("/login").with(csrf()).param("username", contact).param("password", password))
                .andExpect(redirectedUrl("/account")).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();
        mvc.perform(get("/admin/accounts").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/mfa").session(session)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(Base64.getEncoder().encodeToString(secret)))));
        String oldId = session.getId(); String code = Totp.code(secret, clock.instant().getEpochSecond() / 30, 6);
        mvc.perform(post("/admin/mfa").session(session).with(csrf()).param("code", code)).andExpect(redirectedUrl("/admin/reauth"));
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/admin/audit").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/accounts").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/reauth").session(session).with(csrf()).param("password", password)).andExpect(redirectedUrl("/admin/accounts"));
        mvc.perform(get("/admin/accounts").session(session)).andExpect(status().isOk());
        UUID target = accounts.register("webtarget" + UUID.randomUUID() + "@example.test", "Test-password-12345").accountId().value();
        mvc.perform(post("/admin/accounts/" + target + "/restriction").session(session).with(csrf()).param("active", "true")).andExpect(status().isForbidden());
        clock.advance(601);
        mvc.perform(get("/admin/audit").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void shouldDenyStepupReplayAndClearOrdinaryAndMfaSessionsAfterReprovision() throws Exception {
        AccountService.Credentials credentials = accounts.credentials(contact).orElseThrow();
        AccountPrincipal principal = new AccountPrincipal(administrator, contact, credentials.passwordHash(), credentials.epoch());
        MockHttpSession first = session(principal); MockHttpSession second = session(principal);
        String code = Totp.code(secret, clock.instant().getEpochSecond() / 30, 6);
        mvc.perform(post("/admin/mfa").session(first).with(csrf()).param("code", code)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/mfa").session(second).with(csrf()).param("code", code)).andExpect(status().isForbidden());
        Path enrollment = Path.of(".runtime", "reprovision-" + UUID.randomUUID());
        operator.provision(contact, Set.of("IDENTITY_ADMIN"), enrollment); Files.delete(enrollment);
        mvc.perform(get("/admin/audit").session(first)).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(get("/account").session(second)).andExpect(redirectedUrl("/login?expired"));
    }

    @Test void shouldKeepRecoveryResponseGenericEvenWhenPrivateDeliveryFails() throws Exception {
        Path mailbox = Path.of(".runtime/web-mailbox");
        Path saved = Path.of(".runtime/web-mailbox-saved-" + UUID.randomUUID());
        boolean exists = Files.exists(mailbox);
        if (exists) { Files.move(mailbox, saved); }
        Files.createFile(mailbox);
        try {
            var known = mvc.perform(post("/password/forgot").with(csrf()).param("contact", contact)).andExpect(status().is3xxRedirection()).andReturn();
            var unknown = mvc.perform(post("/password/forgot").with(csrf()).param("contact", "absent@example.test")).andExpect(status().is3xxRedirection()).andReturn();
            assertThat(known.getFlashMap().get("notice")).isEqualTo(unknown.getFlashMap().get("notice"));
        } finally {
            Files.delete(mailbox);
            if (exists) { Files.move(saved, mailbox); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldLimitCanonicalLoginAccountAcrossContactAliasesAndSources(boolean known) throws Exception {
        String target = known ? contact : "absent" + UUID.randomUUID() + "@example.test";
        String sources = sourcePrefix();
        for (int attempt = 1; attempt <= 10; attempt++) {
            mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(sources + attempt))
                            .param("username", alias(target, attempt)).param("password", "Incorrect-password-12345"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(sources + 11))
                        .param("username", alias(target, 11)).param("password", password))
                .andExpect(redirectedUrl("/login?limited"));
        clock.advance(61);
        mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(sources + 11))
                        .param("username", alias(target, 12)).param("password", password))
                .andExpect(redirectedUrl(known ? "/account" : "/login?error"));
    }

    @Test void shouldLimitLoginSourceAcrossDistinctAccounts() throws Exception {
        String source = sourcePrefix() + "1";
        for (int attempt = 0; attempt < 10; attempt++) {
            String target = "login" + UUID.randomUUID() + "@example.test";
            accounts.register(target, "Test-password-12345");
            mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(source))
                            .param("username", target).param("password", "Incorrect-password-12345"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(source))
                        .param("username", contact).param("password", password))
                .andExpect(redirectedUrl("/login?limited"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"known", "unknown", "malformed"})
    void shouldLimitCanonicalRecoveryContactAcrossAliasesAndSourcesWithGenericAcknowledgment(String kind) throws Exception {
        String target = switch (kind) {
            case "known" -> contact;
            case "unknown" -> "absent" + UUID.randomUUID() + "@example.test";
            default -> "malformed-" + UUID.randomUUID();
        };
        String sources = sourcePrefix();
        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(post("/password/forgot").with(csrf()).with(source(sources + attempt)).param("contact", alias(target, attempt)))
                    .andExpect(redirectedUrl("/password/forgot"))
                    .andExpect(flash().attribute("notice", "Si existe una cuenta de prueba, se intentó entregar un código al buzón local privado."));
        }
        mvc.perform(post("/password/forgot").with(csrf()).with(source(sources + 6)).param("contact", alias(target, 6)))
                .andExpect(status().isForbidden()).andExpect(model().attribute("message", "Demasiados intentos; reintentar en un minuto"));
        clock.advance(61);
        mvc.perform(post("/password/forgot").with(csrf()).with(source(sources + 6)).param("contact", alias(target, 7)))
                .andExpect(redirectedUrl("/password/forgot"));
    }

    @Test void shouldLimitRecoverySourceAcrossDistinctAccounts() throws Exception {
        String source = sourcePrefix() + "1";
        for (int attempt = 0; attempt < 5; attempt++) {
            String target = "recovery" + UUID.randomUUID() + "@example.test";
            accounts.register(target, "Test-password-12345");
            mvc.perform(post("/password/forgot").with(csrf()).with(source(source)).param("contact", target))
                    .andExpect(redirectedUrl("/password/forgot"));
        }
        mvc.perform(post("/password/forgot").with(csrf()).with(source(source)).param("contact", contact))
                .andExpect(status().isForbidden()).andExpect(model().attribute("message", "Demasiados intentos; reintentar en un minuto"));
    }

    @Test void shouldKeepMissingAndMalformedLoginGenericAndBounded() throws Exception {
        String sources = sourcePrefix();
        for (int attempt = 1; attempt <= 10; attempt++) {
            var request = post("/login").servletPath("/login").with(csrf()).with(source(sources + attempt))
                    .param("password", "Incorrect-password-12345");
            if (attempt > 1) { request.param("username", " ".repeat(attempt)); }
            mvc.perform(request).andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(sources + 11)).param("username", " ".repeat(11)))
                .andExpect(redirectedUrl("/login?limited"));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(sources + 12))
                        .param("username", "malformed-" + UUID.randomUUID()).param("password", "Incorrect-password-12345"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test void shouldLimitReauthenticationActorAcrossSourcesWithoutGrantingFreshAuthentication() throws Exception {
        MockHttpSession session = authenticatedSession(contact);
        String sources = sourcePrefix();
        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(post("/admin/reauth").session(session).with(csrf()).with(source(sources + attempt))
                            .param("password", "Incorrect-password-12345"))
                    .andExpect(status().isForbidden()).andExpect(model().attribute("message", "No se pudo autenticar"));
        }
        mvc.perform(post("/admin/reauth").session(session).with(csrf()).with(source(sources + 6)).param("password", password))
                .andExpect(status().isForbidden()).andExpect(model().attribute("message", "Demasiados intentos"));
        assertThat(session.getAttribute("passwordUntil")).isNull();
        clock.advance(61);
        mvc.perform(post("/admin/reauth").session(session).with(csrf()).with(source(sources + 6)).param("password", password))
                .andExpect(redirectedUrl("/admin/accounts"));
        assertThat(session.getAttribute("passwordEpoch")).isEqualTo(accounts.epoch(administrator));
        assertThat(session.getAttribute("passwordUntil")).isEqualTo(clock.instant().plusSeconds(600));
    }

    @Test void shouldLimitReauthenticationSourceAcrossDistinctActors() throws Exception {
        String source = sourcePrefix() + "1";
        for (int attempt = 0; attempt < 5; attempt++) {
            String target = "reauth" + UUID.randomUUID() + "@example.test";
            accounts.register(target, "Test-password-12345");
            mvc.perform(post("/admin/reauth").session(authenticatedSession(target)).with(csrf()).with(source(source))
                            .param("password", "Incorrect-password-12345"))
                    .andExpect(status().isForbidden()).andExpect(model().attribute("message", "No se pudo autenticar"));
        }
        MockHttpSession session = authenticatedSession(contact);
        mvc.perform(post("/admin/reauth").session(session).with(csrf()).with(source(source)).param("password", password))
                .andExpect(status().isForbidden()).andExpect(model().attribute("message", "Demasiados intentos"));
        assertThat(session.getAttribute("passwordUntil")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODERATION_REVIEWER", "MODERATION_AUDITOR", "MODERATION_REVIEWER,MODERATION_AUDITOR"})
    void shouldEnforceFocusedModerationRolesThroughActualLoginMfaFreshnessAndOwnerTransaction(String roles) throws Exception {
        Moderator moderator = moderator(Set.of(roles.split(",")));
        MockHttpSession session = login(moderator);
        String source = sourcePrefix() + "1";
        UUID intent = UUID.randomUUID();
        jdbc.execute("create table if not exists foundation_f3_http_intent (id uuid primary key)");
        mvc.perform(get("/test/f3/audit").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/test/f3/review").session(session).with(csrf()).param("intent", intent.toString())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/mfa").session(session).with(csrf()).with(source(source))
                        .param("code", Totp.code(moderator.secret(), clock.instant().getEpochSecond() / 30, 6)))
                .andExpect(redirectedUrl("/admin/reauth"));
        mvc.perform(get("/test/f3/audit").session(session)).andExpect(status().is(roles.contains("MODERATION_AUDITOR") ? 200 : 403));
        mvc.perform(post("/test/f3/review").session(session).with(csrf()).param("intent", intent.toString())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/reauth").session(session).with(csrf()).with(source(source)).param("password", moderator.password()))
                .andExpect(redirectedUrl("/admin/accounts"));
        mvc.perform(get("/admin/accounts").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/accounts/" + administrator + "/restriction").session(session).with(csrf()).param("active", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/test/f3/review").session(session).param("intent", intent.toString())).andExpect(status().isForbidden());
        mvc.perform(post("/test/f3/without-transaction").session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/test/f3/review").session(session).with(csrf()).param("intent", intent.toString()).param("job", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/test/f3/review").session(session).with(csrf()).param("intent", intent.toString()))
                .andExpect(status().is(roles.contains("MODERATION_REVIEWER") ? 200 : 403));
        assertThat(jdbc.queryForObject("select count(*) from foundation_f3_http_intent where id=?", Integer.class, intent))
                .isEqualTo(roles.contains("MODERATION_REVIEWER") ? 1 : 0);
        clock.advance(601);
        mvc.perform(get("/test/f3/audit").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/test/f3/review").session(session).with(csrf()).param("intent", UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }

    @Test void shouldRejectWrongActorEpochRestrictionAndRemovedModerationRoleWithRealSessionProof() throws Exception {
        Moderator moderator = moderator(Set.of("MODERATION_REVIEWER", "MODERATION_AUDITOR"));
        MockHttpSession session = login(moderator);
        String source = sourcePrefix() + "1";
        mvc.perform(post("/admin/mfa").session(session).with(csrf()).with(source(source))
                        .param("code", Totp.code(moderator.secret(), clock.instant().getEpochSecond() / 30, 6)))
                .andExpect(redirectedUrl("/admin/reauth"));
        mvc.perform(post("/admin/reauth").session(session).with(csrf()).with(source(source)).param("password", moderator.password()))
                .andExpect(redirectedUrl("/admin/accounts"));
        mvc.perform(get("/test/f3/audit/" + administrator).session(session)).andExpect(status().isForbidden());
        Moderator other = moderator(Set.of("MODERATION_AUDITOR"));
        mvc.perform(get("/test/f3/audit/" + other.id()).session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/test/f3/audit/" + UUID.randomUUID()).session(session)).andExpect(status().isForbidden());
        session.setAttribute("mfaEpoch", accounts.epoch(moderator.id()) - 1);
        mvc.perform(get("/test/f3/audit").session(session)).andExpect(status().isForbidden());
        session.setAttribute("mfaEpoch", accounts.epoch(moderator.id()));
        session.setAttribute("passwordEpoch", accounts.epoch(moderator.id()) - 1);
        mvc.perform(post("/test/f3/review").session(session).with(csrf()).param("intent", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/test/f3/audit").session(session)).andExpect(status().isOk());
        session.setAttribute("passwordEpoch", accounts.epoch(moderator.id()));
        UUID restriction = UUID.randomUUID();
        jdbc.update("insert into identity_restriction values (?, ?, true, 'SYNTHETIC_REVIEW', now())", restriction, moderator.id());
        mvc.perform(get("/test/f3/audit").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/test/f3/review").session(session).with(csrf()).param("intent", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        jdbc.update("update identity_restriction set active=false where id=?", restriction);
        MockHttpSession identitySession = login(new Moderator(administrator, contact, password, secret));
        mvc.perform(post("/admin/mfa").session(identitySession).with(csrf()).with(source(sourcePrefix() + "1"))
                        .param("code", Totp.code(secret, clock.instant().getEpochSecond() / 30, 6))).andExpect(redirectedUrl("/admin/reauth"));
        mvc.perform(post("/admin/reauth").session(identitySession).with(csrf()).with(source(sourcePrefix() + "1")).param("password", password))
                .andExpect(redirectedUrl("/admin/accounts"));
        long epoch = accounts.epoch(moderator.id());
        mvc.perform(post("/admin/accounts/" + moderator.id() + "/role/remove").session(identitySession).with(csrf()).param("role", "MODERATION_REVIEWER"))
                .andExpect(redirectedUrl("/admin/accounts"));
        assertThat(accounts.epoch(moderator.id())).isEqualTo(epoch + 1);
        assertThat(jdbc.queryForObject("select count(*) from identity_audit_event where subject_id=? and action='ROLE_REMOVED_MODERATION_REVIEWER'", Integer.class, moderator.id())).isEqualTo(1);
        mvc.perform(get("/test/f3/audit").session(session)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
        clock.advance(31);
        MockHttpSession current = login(moderator);
        mvc.perform(post("/admin/mfa").session(current).with(csrf()).with(source(sourcePrefix() + "1"))
                        .param("code", Totp.code(moderator.secret(), clock.instant().getEpochSecond() / 30, 6))).andExpect(redirectedUrl("/admin/reauth"));
        mvc.perform(get("/test/f3/audit").session(current)).andExpect(status().isOk());
        mvc.perform(post("/test/f3/review").session(current).with(csrf()).param("intent", UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }

    private Moderator moderator(Set<String> roles) throws Exception {
        String contact = "f3web" + UUID.randomUUID() + "@example.test";
        Path enrollment = Path.of(".runtime", "f3-web-" + UUID.randomUUID());
        try {
            operator.provision(contact, roles, enrollment);
            String password = Files.readAllLines(enrollment).stream().filter(line -> line.startsWith("password=")).map(line -> line.substring(9)).findFirst().orElseThrow();
            UUID id = accounts.credentials(contact).orElseThrow().id();
            byte[] factor = new TransactionTemplate(transactions).execute(status -> cipher.decrypt(store.find(AdminMfa.class, id).encryptedSecret));
            return new Moderator(id, contact, password, factor);
        } finally { Files.deleteIfExists(enrollment); }
    }

    private MockHttpSession login(Moderator moderator) throws Exception {
        var result = mvc.perform(post("/login").servletPath("/login").with(csrf()).with(source(sourcePrefix() + "1"))
                        .param("username", moderator.contact()).param("password", moderator.password()))
                .andExpect(redirectedUrl("/account")).andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    private record Moderator(UUID id, String contact, String password, byte[] secret) {}

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class F3Consumer {
        private final IdentityAccess identity;
        private final TransactionTemplate transaction;
        private final JdbcTemplate jdbc;

        F3Consumer(IdentityAccess identity, PlatformTransactionManager manager, JdbcTemplate jdbc) {
            this.identity = identity; this.transaction = new TransactionTemplate(manager); this.jdbc = jdbc;
        }

        @PostMapping("/test/f3/review")
        String review(@AuthenticationPrincipal AccountPrincipal principal, @RequestParam UUID intent, @RequestParam(required = false) UUID job) {
            return transaction.execute(status -> {
                identity.requireForUpdate(new PermissionRequest(Optional.of(new ActorId(principal.id())), Optional.of(new SubjectId(principal.id())),
                        Capability.valueOf("ADMIN_MODERATION_REVIEW"), Optional.ofNullable(job).map(id -> new JobContext(id, JobContext.FulfillmentMode.REMOTE))));
                jdbc.update("insert into foundation_f3_http_intent values (?)", intent);
                return "review-eligibility";
            });
        }

        @PostMapping("/test/f3/without-transaction")
        String withoutTransaction(@AuthenticationPrincipal AccountPrincipal principal) {
            identity.requireForUpdate(PermissionRequest.self(new AccountId(principal.id()), Capability.valueOf("ADMIN_MODERATION_REVIEW")));
            return "unreachable";
        }

        @GetMapping({"/test/f3/audit", "/test/f3/audit/{actor}"})
        String audit(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable(required = false) UUID actor) {
            UUID target = actor == null ? principal.id() : actor;
            if (!identity.decide(PermissionRequest.self(new AccountId(target), Capability.valueOf("ADMIN_MODERATION_AUDIT"))).allowed()) {
                throw new IdentityFailure("Elegibilidad de metadatos requerida");
            }
            return "audit-metadata-eligibility";
        }
    }

    private MockHttpSession authenticatedSession(String contact) {
        AccountService.Credentials credentials = accounts.credentials(contact).orElseThrow();
        return session(new AccountPrincipal(credentials.id(), credentials.contact(), credentials.passwordHash(), credentials.epoch()));
    }

    private static String alias(String contact, int attempt) {
        return " ".repeat(attempt) + (attempt % 2 == 0 ? contact : contact.toUpperCase(Locale.ROOT)) + " ";
    }

    private static String sourcePrefix() {
        return "127.99." + SOURCE_SEQUENCE.incrementAndGet() + ".";
    }

    private static RequestPostProcessor source(String address) {
        return request -> { request.setRemoteAddr(address); return request; };
    }

    private MockHttpSession session(AccountPrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())));
        return session;
    }
}
