package ar.changeo.identity;

import ar.changeo.ChangeoApplication;
import ar.changeo.security.AccountPrincipal;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {ChangeoApplication.class, IdentityPostgresTest.TestConfig.class}, properties = {"spring.flyway.schemas=foundation_web_test", "spring.flyway.default-schema=foundation_web_test", "spring.jpa.properties.hibernate.default_schema=foundation_web_test", "spring.datasource.hikari.schema=foundation_web_test", "changeo.mfa-key=.runtime/web-mfa.key", "changeo.mailbox=.runtime/web-mailbox", "changeo.synthetic-evidence=true"})
@AutoConfigureMockMvc
class WebsiteSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operator;
    @Autowired IdentityStore store;
    @Autowired FactorCipher cipher;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
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

    private MockHttpSession session(AccountPrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())));
        return session;
    }
}
