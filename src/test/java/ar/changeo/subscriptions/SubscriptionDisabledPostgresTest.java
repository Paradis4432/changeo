package ar.changeo.subscriptions;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ChangeoApplication.class, properties = {
        "spring.datasource.url=${subscriptions.disabled.test.url}", "spring.flyway.schemas=subscriptions_disabled_test", "spring.flyway.default-schema=subscriptions_disabled_test",
        "spring.datasource.hikari.schema=subscriptions_disabled_test", "spring.jpa.properties.hibernate.default_schema=subscriptions_disabled_test",
        "changeo.synthetic-payments=false", "changeo.synthetic-subscriptions=false", "changeo.synthetic-evidence=false", "changeo.synthetic-moderation=false",
        "changeo.notification-worker=false", "changeo.moderation-worker=false", "changeo.payments-worker=false", "changeo.subscription-worker=false",
        "changeo.mfa-key=.runtime/subscriptions-disabled-mfa.key", "changeo.files-root=.runtime/subscriptions-disabled-artifacts"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SubscriptionDisabledPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @MockitoBean TokenDelivery delivery;

    @Test void disabledAppShowsTruthfulPendingAndHasNoCommercialMoneyOrWorker() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(System.getProperty("payments.test.database"));
        assertThat(context.getBeansOfType(SubscriptionService.class)).isEmpty(); assertThat(context.getBeansOfType(SubscriptionLoyaltyService.class)).isEmpty();
        assertThat(context.getBeansOfType(ar.changeo.payments.billing.BillingWorker.class)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from subscriptions_policy", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_subscription_budget", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments_subscription_ledger", Integer.class)).isZero();
        Map<String, String> tokens = new ConcurrentHashMap<>();
        when(delivery.deliver(any(), anyString(), anyString())).thenAnswer(invocation -> { tokens.put(invocation.getArgument(0) + ":" + invocation.getArgument(1), invocation.getArgument(2)); return true; });
        String contact = "disabled" + UUID.randomUUID() + "@example.test", password = "Synthetic-password-12345";
        UUID account = accounts.register(contact, password).accountId().value(); accounts.verifyContact(account, tokens.get(account + ":CONTACT"));
        var session = (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", contact).param("password", password)).andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
        try {
            var result = mvc.perform(get("/subscriptions").session(session)).andExpect(status().isOk()).andReturn();
            assertThat(result.getModelAndView().getModel().get("enabled")).isEqualTo(false);
            assertThat(result.getResponse().getContentAsString()).contains("deshabilitadas");
            mvc.perform(post("/subscriptions/enroll").session(session).with(csrf()).param("command", UUID.randomUUID().toString()).param("subject", account.toString())
                    .param("version", "0").param("mode", "FREE").param("policyDigest", "x")).andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("select count(*) from subscriptions_enrollment", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from payments_billing_operation", Integer.class)).isZero();
        } finally { session.invalidate(); tokens.clear(); }
    }
}
