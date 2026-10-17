package ar.changeo.payments;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static ar.changeo.payments.PaymentStore.LedgerAccount.*;

@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
class SyntheticCapital implements InitializingBean {
    private final PaymentStore store;
    private final TransactionTemplate transaction;
    private final long endowment;

    SyntheticCapital(PaymentStore store, PlatformTransactionManager transactions,
            @Value("${changeo.payments-synthetic-capital-minor:100000000}") long endowment,
            @Value("${changeo.real-money:false}") boolean realMoney) {
        if (realMoney || endowment <= 0) { throw new PaymentFailure("Solo está habilitada la integración privada sintética con capital independiente positivo"); }
        this.store = store; this.transaction = new TransactionTemplate(transactions); this.endowment = endowment;
    }

    @Override public void afterPropertiesSet() {
        transaction.executeWithoutResult(status -> {
            int inserted = store.jdbc.update("insert into payments_capital(currency,endowed_minor,available_minor) values('ARS',?,?) on conflict do nothing", endowment, endowment);
            if (inserted == 1) {
                store.ledger(PaymentStore.id("synthetic-capital-endowment"), null, List.of(new PaymentStore.Entry(PLATFORM_CASH, endowment),
                        new PaymentStore.Entry(PLATFORM_CAPITAL, Math.negateExact(endowment))));
            }
        });
    }
}
