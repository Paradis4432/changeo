package ar.changeo.subscriptions;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class SubscriptionPublication implements InitializingBean {
    private final SubscriptionStore store;
    private final SubscriptionPolicy policy;
    private final TransactionTemplate transaction;

    SubscriptionPublication(SubscriptionStore store, SubscriptionPolicy policy, PlatformTransactionManager transactions) {
        this.store = store; this.policy = policy; this.transaction = new TransactionTemplate(transactions);
    }

    @Override public void afterPropertiesSet() {
        transaction.executeWithoutResult(status -> {
            store.jdbc.update("insert into subscriptions_policy values(?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing", policy.version(), policy.digest(), policy.grossMinor(),
                    policy.foundingDays(), policy.trialDays(), policy.graceDays(), policy.creditMinor(), policy.feeMinor(), policy.zone().getId(), policy.disclosure(), store.now());
            store.jdbc.update("insert into subscriptions_policy_current(slot,policy_version) values(1,?) on conflict do nothing", policy.version());
            store.policy(store.pointer(true).version(), true);
            store.jdbc.update("insert into subscriptions_campaign(code,rule_version,credit_minor,minimum_service_minor,counterparties,reverse_days) values('SL1B-LOYALTY-2','SL1B-LOYALTY-2',100,5000,2,30) on conflict do nothing");
        });
    }

    void publish(SubscriptionPolicy replacement) {
        transaction.executeWithoutResult(status -> {
            store.pointer(true);
            store.jdbc.update("insert into subscriptions_policy values(?,?,?,?,?,?,?,?,?,?,?)", replacement.version(), replacement.digest(), replacement.grossMinor(),
                    replacement.foundingDays(), replacement.trialDays(), replacement.graceDays(), replacement.creditMinor(), replacement.feeMinor(), replacement.zone().getId(), replacement.disclosure(), store.now());
            store.jdbc.update("update subscriptions_policy_current set policy_version=?,version=version+1 where slot=1", replacement.version());
        });
    }
}
