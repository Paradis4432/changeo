package ar.changeo.payments.billing;

import ar.changeo.identity.IdentityFailure;
import ar.changeo.payments.api.PreparedBillingCharge;
import ar.changeo.payments.gateway.*;
import ar.changeo.subscriptions.api.BillingDispatchAccess;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
public class BillingWorker {
    private final BillingStore store;
    private final SubscriptionBillingAdapter billing;
    private final BillingDispatchAccess dispatch;
    private final FinancialGateway gateway;
    private final BillingOutcomeService outcomes;
    private final TransactionTemplate transaction;

    BillingWorker(BillingStore store, SubscriptionBillingAdapter billing, BillingDispatchAccess dispatch, FinancialGateway gateway,
            BillingOutcomeService outcomes, PlatformTransactionManager transactions) {
        this.store = store; this.billing = billing; this.dispatch = dispatch; this.gateway = gateway;
        this.outcomes = outcomes; this.transaction = new TransactionTemplate(transactions);
    }

    public boolean processOne() {
        standalone();
        var claim = claim();
        if (claim.isEmpty()) { return false; }
        process(claim.orElseThrow()); return true;
    }

    public Optional<PreparedBillingCharge> claim() {
        standalone();
        var ids = store.jdbc.query("select id from payments_billing_operation where available_at<=? and (state in ('QUEUED','UNKNOWN') or (state in ('CLAIMED','DISPATCHING') and lease_until<=?)) order by available_at,id limit 20",
                (row, index) -> row.getObject(1, UUID.class), store.now(), store.now());
        for (UUID id : ids) {
            var result = transaction.execute(status -> {
                var advisory = store.charge(id); store.protect(advisory.request().binding(), false);
                var charge = store.charge(id); store.requireHeld(charge);
                if (!Set.of("QUEUED", "UNKNOWN", "CLAIMED", "DISPATCHING").contains(charge.state())
                        || charge.leaseUntil().filter(instant -> instant.isAfter(store.clock.instant())).isPresent()) { return Optional.<PreparedBillingCharge>empty(); }
                UUID token = UUID.randomUUID();
                store.jdbc.update("update payments_billing_operation set state='CLAIMED',generation=generation+1,claim_token=?,lease_until=?,reason='LEASED',version=version+1 where id=?",
                        token, Timestamp.from(store.clock.instant().plusSeconds(30)), id);
                return Optional.of(store.charge(id).prepared());
            });
            if (result.isPresent()) { return result; }
        }
        return Optional.empty();
    }

    public void process(PreparedBillingCharge prepared) {
        standalone();
        var advisory = store.charge(prepared.operationId());
        if (advisory.dispatchedAt().isEmpty()) {
            try { dispatch.authorize(prepared); }
            catch (IdentityFailure denied) {
                transaction.executeWithoutResult(status -> {
                    store.protect(prepared.request().binding(), false);
                    var charge = store.charge(prepared.operationId());
                    if (liveClaim(charge, prepared) && charge.dispatchedAt().isEmpty()) { billing.cancelHeld(charge, "CURRENT_AUTHORITY_DENIED"); }
                });
                return;
            }
        }
        var request = transaction.execute(status -> {
            store.protect(prepared.request().binding(), false);
            var charge = store.charge(prepared.operationId());
            if (!liveClaim(charge, prepared) || charge.dispatchedAt().isEmpty() || !Set.of("DISPATCHING", "CLAIMED").contains(charge.state())) { return Optional.<GatewayRequest>empty(); }
            return Optional.of(charge.gatewayRequest());
        });
        if (request.isEmpty()) { return; }
        try {
            var observation = advisory.dispatchedAt().isPresent() ? gateway.query(request.orElseThrow()) : gateway.execute(request.orElseThrow());
            outcomes.record(observation);
        } catch (RuntimeException unavailable) {
            transaction.executeWithoutResult(status -> {
                store.protect(prepared.request().binding(), false);
                var charge = store.charge(prepared.operationId());
                if (liveClaim(charge, prepared) && charge.pending()) {
                    store.jdbc.update("update payments_billing_operation set state='UNKNOWN',claim_token=null,lease_until=null,available_at=?,reason='RECONCILIATION_REQUIRED',version=version+1 where id=?",
                            Timestamp.from(store.clock.instant().plusSeconds(30)), charge.id());
                }
            });
        }
    }

    private boolean liveClaim(BillingStore.Charge charge, PreparedBillingCharge prepared) {
        return charge.generation() == prepared.claimGeneration() && charge.token().equals(prepared.claimToken())
                && charge.leaseUntil().filter(instant -> instant.isAfter(store.clock.instant())).isPresent()
                && charge.request().equals(prepared.request()) && charge.stableKey().equals(prepared.stableKey());
    }

    private static void standalone() { if (TransactionSynchronizationManager.isActualTransactionActive()) { throw BillingStore.failure("El proceso de cobro requiere una transacción independiente"); } }
}
