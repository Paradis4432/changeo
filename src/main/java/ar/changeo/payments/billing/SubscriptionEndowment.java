package ar.changeo.payments.billing;

import ar.changeo.subscriptions.SubscriptionPolicy;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class SubscriptionEndowment implements InitializingBean {
    private final BillingStore store;
    private final TransactionTemplate transaction;
    private final long fee;
    private final long promotion;

    SubscriptionEndowment(BillingStore store, SubscriptionPolicy policy, PlatformTransactionManager transactions,
            @Value("${changeo.subscription-fee-capital-minor:10000}") long fee, @Value("${changeo.subscription-promotion-budget-minor:10000}") long promotion) {
        if (fee != 10000 || promotion != 10000) { throw BillingStore.failure("Respaldo inicial incompatible con los dos fondos independientes de 10000 centavos"); }
        this.store = store; this.transaction = new TransactionTemplate(transactions); this.fee = fee; this.promotion = promotion;
    }

    @Override public void afterPropertiesSet() {
        transaction.executeWithoutResult(status -> {
            endow("FEE", fee); endow("PROMOTION", promotion);
        });
    }

    private void endow(String kind, long amount) {
        int inserted = store.jdbc.update("insert into payments_subscription_budget(kind,endowed_minor,available_minor) values(?,?,?) on conflict do nothing", kind, amount, amount);
        long actual = store.jdbc.queryForObject("select endowed_minor from payments_subscription_budget where kind=? for update", Long.class, kind);
        if (actual != amount) { throw BillingStore.failure("El respaldo configurado difiere del respaldo durable"); }
        if (inserted == 1 && amount > 0) { store.ledger(BillingStore.id("subscription-endowment:" + kind), null, null, new BillingStore.Entry(kind + "_CASH", amount), new BillingStore.Entry(kind + "_ENDOWMENT", -amount)); }
    }
}
