package ar.changeo.payments;

import ar.changeo.ChangeoApplication;
import ar.changeo.agreements.api.AgreementTerms;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.api.*;
import ar.changeo.payments.gateway.SyntheticGateway;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ChangeoApplication.class, properties = {
        "spring.flyway.schemas=sandbox", "spring.flyway.default-schema=sandbox", "spring.datasource.hikari.schema=sandbox",
        "spring.jpa.properties.hibernate.default_schema=sandbox", "changeo.synthetic-payments=false",
        "changeo.mfa-key=/tmp/changeo-payments-01a11313/mfa.key", "changeo.files-root=/tmp/changeo-payments-01a11313/artifacts"})
class PaymentDisabledPostgresTest {
    @Autowired JobFinancialPreparationAccess preparation;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void shouldKeepExactReadPreparationPendingWithoutOwnerGatewayWorkerOrAnyFinancialWrite() {
        assertThat(context.getBeansOfType(PaymentStore.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(SyntheticGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentFinancialAdapter.class)).isEmpty();
        var facts = facts(); String before = fingerprint();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            for (int count = 0; count < 3; count++) {
                var money = preparation.prepareViewForUpdate(facts);
                money.requireExact(facts.agreement().jobId(), facts.agreement().agreement());
                assertThat(money.funding()).isEqualTo(JobMoneyStatus.Funding.INTEGRATION_PENDING);
                assertThat(money.release()).isEqualTo(JobMoneyStatus.Release.INTEGRATION_PENDING);
                assertThat(money.bankPayout()).isEqualTo(JobMoneyStatus.BankPayout.INTEGRATION_PENDING);
            }
        });
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void shouldDenyDefaultViewPreparationWithoutItsCallerTransaction() {
        assertThatThrownBy(() -> preparation.prepareViewForUpdate(facts())).isInstanceOf(IllegalStateException.class);
    }

    private JobFinancialFacts facts() {
        UUID customer = UUID.randomUUID(); UUID provider = UUID.randomUUID();
        var agreement = new FinancialAgreement(UUID.randomUUID(), new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, UUID.randomUUID(), UUID.randomUUID()),
                new SubjectId(customer), new SubjectId(provider), new ActorId(customer), new ActorId(provider), new AccountId(customer), 12345, 0, 12345,
                Currency.getInstance("ARS"), AgreementTerms.PolicyScope.PRIVATE_SYNTHETIC, AgreementTerms.PolicyVersion.S1_EXPLICIT_ACCEPTANCE_V1,
                AgreementTerms.FinalRule.EXPLICIT_CUSTOMER_ACCEPTANCE, 0);
        return new JobFinancialFacts(agreement, JobSnapshot.State.AGREED_AWAITING_FUNDING, Optional.empty(), Optional.empty());
    }

    private String fingerprint() {
        StringBuilder state = new StringBuilder();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and tablename like 'payments_%' order by tablename", String.class)) {
            assertThat(table).matches("[a-z0-9_]+");
            state.append(table).append(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text,'[]') from \"" + table + "\" t", String.class));
        }
        return PaymentStore.digest(state.toString());
    }
}
