package ar.changeo.subscriptions;

import ar.changeo.identity.IdentityFailure;
import ar.changeo.payments.billing.BillingWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class SubscriptionSchedule {
    private final SubscriptionService subscriptions;
    private final BillingWorker worker;
    private final boolean enabled;

    SubscriptionSchedule(SubscriptionService subscriptions, BillingWorker worker, @Value("${changeo.subscription-worker:false}") boolean enabled) {
        this.subscriptions = subscriptions; this.worker = worker; this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
        if (!enabled) { return; }
        try { subscriptions.renewOne(); } catch (IdentityFailure denied) { return; }
        for (int count = 0; count < 4; count++) { if (!worker.processOne()) { break; } }
    }
}
