package ar.changeo.payments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
class PaymentSchedule {
    private final PaymentWorker worker;
    private final boolean enabled;
    PaymentSchedule(PaymentWorker worker, @Value("${changeo.payments-worker:true}") boolean enabled) { this.worker = worker; this.enabled = enabled; }
    @Scheduled(fixedDelay = 1000) void tick() {
        if (!enabled) { return; }
        for (int task = 0; task < 4; task++) { if (!worker.processOne()) { return; } }
    }
}
