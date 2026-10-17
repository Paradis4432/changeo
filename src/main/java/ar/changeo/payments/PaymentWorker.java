package ar.changeo.payments;

import ar.changeo.payments.gateway.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
public class PaymentWorker {
    private final PaymentStore store;
    private final PaymentOperations operations;
    private final PaymentReservations reservations;
    private final FinancialGateway gateway;
    private final PaymentOutcomeService outcomes;
    private final TransactionTemplate transaction;

    PaymentWorker(PaymentStore store, PaymentOperations operations, PaymentReservations reservations,
            FinancialGateway gateway, PaymentOutcomeService outcomes, PlatformTransactionManager transactions) {
        this.store = store; this.operations = operations; this.reservations = reservations; this.gateway = gateway;
        this.outcomes = outcomes; this.transaction = new TransactionTemplate(transactions);
    }

    public Optional<Claim> claim() {
        standalone();
        var candidates = store.jdbc.query("select id from payments_operation where (state in ('QUEUED','UNKNOWN','RETRY_WAIT') and available_at<=?) or (state in ('CLAIMED','DISPATCHING') and lease_until<=?) order by available_at,id::text limit 20",
                (row, index) -> row.getObject(1, UUID.class), store.now(), store.now());
        for (UUID candidate : candidates) {
            var claim = transaction.execute(status -> claim(candidate));
            if (claim.isPresent()) { return claim; }
        }
        return Optional.empty();
    }

    private Optional<Claim> claim(UUID id) {
        var discovered = store.operation(id, false);
        store.protectExisting(discovered.rootId());
        var operation = store.operation(id, false); var root = store.root(operation.rootId(), false); var now = store.clock.instant();
        if ((!operation.pending() && !operation.uncertain()) || operation.state().equals("RECOVERY")
                || operation.availableAt().isAfter(now) || operation.leaseUntil().filter(value -> value.isAfter(now)).isPresent()) { return Optional.empty(); }
        boolean inquiry = operation.dispatchedAt().isPresent() || operation.uncertain();
        if (!inquiry && !dispatchAllowed(root, operation)) {
            reservations.release(operation);
            store.jdbc.update("update payments_operation set state='CANCELLED',generation=generation+1,claim_token=null,lease_until=null,reason='PAUSED_BEFORE_DISPATCH' where id=?", id);
            store.saveOutcome(root.agreement().legalPayer(), List.of(root)); return Optional.empty();
        }
        if (operation.attempts() >= 6) {
            store.jdbc.update("update payments_operation set reason='SUPPORT_RECONCILIATION',available_at=? where id=?", Timestamp.from(now.plusSeconds(300)), id);
            return Optional.empty();
        }
        UUID token = UUID.randomUUID(); long generation = Math.addExact(operation.generation(), 1);
        store.jdbc.update("update payments_operation set state='CLAIMED',generation=?,attempts=attempts+1,claim_token=?,lease_until=?,reason=? where id=?",
                generation, token, Timestamp.from(now.plusSeconds(30)), inquiry ? "QUERY_CLAIMED" : "DISPATCH_CLAIMED", id);
        return Optional.of(new Claim(id, generation, token, inquiry));
    }

    public boolean processOne() {
        var claim = claim(); if (claim.isEmpty()) { return false; }
        process(claim.orElseThrow()); return true;
    }

    public void process(Claim claim) {
        standalone();
        var request = claim.inquiry() ? inquiry(claim) : dispatch(claim);
        if (request.isEmpty()) { return; }
        try {
            var observation = claim.inquiry() ? gateway.query(request.orElseThrow()) : gateway.execute(request.orElseThrow());
            outcomes.record(observation);
        } catch (RuntimeException unavailable) { unknown(claim, "GATEWAY_RECONCILIATION"); }
    }

    public Optional<GatewayRequest> dispatch(Claim claim) {
        standalone();
        return transaction.execute(status -> {
            var discovered = store.operation(claim.operationId(), false); store.protectExisting(discovered.rootId());
            var operation = store.operation(claim.operationId(), false); var root = store.root(operation.rootId(), false);
            if (!live(operation, claim)) { return Optional.empty(); }
            if (claim.inquiry() || operation.dispatchedAt().isPresent()) { throw new PaymentFailure("Una clave ya despachada solo puede conciliarse"); }
            if (!dispatchAllowed(root, operation)) {
                reservations.release(operation);
                store.jdbc.update("update payments_operation set state='CANCELLED',generation=generation+1,claim_token=null,lease_until=null,reason='PAUSED_BEFORE_DISPATCH' where id=?", operation.id());
                store.saveOutcome(root.agreement().legalPayer(), List.of(root)); return Optional.empty();
            }
            var now = store.clock.instant();
            store.jdbc.update("update payments_operation set state='DISPATCHING',dispatched_at=?,lease_until=?,reason='DISPATCH_COMMITTED' where id=?",
                    Timestamp.from(now), Timestamp.from(now.plusSeconds(30)), operation.id());
            return Optional.of(operations.request(operation));
        });
    }

    private Optional<GatewayRequest> inquiry(Claim claim) {
        return transaction.execute(status -> {
            var discovered = store.operation(claim.operationId(), false); store.protectExisting(discovered.rootId());
            var operation = store.operation(claim.operationId(), false);
            return live(operation, claim) ? Optional.of(operations.request(operation)) : Optional.empty();
        });
    }

    private void unknown(Claim claim, String reason) {
        transaction.executeWithoutResult(status -> {
            var discovered = store.operation(claim.operationId(), false); store.protectExisting(discovered.rootId());
            var operation = store.operation(claim.operationId(), false);
            if (operation.applied() || Set.of("FAILED_NO_EFFECT", "CANCELLED").contains(operation.state())) { return; }
            if (operation.generation() != claim.generation() || !operation.claimToken().filter(claim.token()::equals).isPresent()) { return; }
            store.jdbc.update("update payments_operation set state='UNKNOWN',claim_token=null,lease_until=null,reason=?,available_at=? where id=?",
                    reason, Timestamp.from(store.clock.instant().plusSeconds(Math.min(300, 5L * Math.max(1, operation.attempts())))), operation.id());
            var root = store.root(operation.rootId(), false);
            store.saveOutcome(root.agreement().legalPayer(), List.of(root));
        });
    }

    private boolean live(PaymentStore.Operation operation, Claim claim) {
        return operation.state().equals("CLAIMED") && operation.generation() == claim.generation()
                && operation.claimToken().filter(claim.token()::equals).isPresent()
                && operation.leaseUntil().filter(value -> value.isAfter(store.clock.instant())).isPresent();
    }
    private boolean dispatchAllowed(PaymentStore.Root root, PaymentStore.Operation operation) {
        if (root.deficitMinor() > 0) { return false; }
        if (!operation.kind().equals("REFUND")) { return !root.paused() && !root.disputed(); }
        return !store.hasFrozenSource(root);
    }
    static void standalone() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new PaymentFailure("El trabajador requiere transacciones independientes y cortas"); }
    }
    public record Claim(UUID operationId, long generation, UUID token, boolean inquiry) {}
}
