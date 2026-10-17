package ar.changeo.payments;

import ar.changeo.identity.api.ActorId;
import ar.changeo.payments.gateway.GatewayRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
class PaymentOperations {
    private final PaymentStore store;
    private final PaymentReservations reservations;
    final long fundingFeeMinor;
    final long payoutFeeMinor;

    PaymentOperations(PaymentStore store, PaymentReservations reservations,
            @Value("${changeo.payments-synthetic-funding-fee-minor:35}") long fundingFeeMinor,
            @Value("${changeo.payments-synthetic-payout-fee-minor:20}") long payoutFeeMinor) {
        if (fundingFeeMinor <= 0 || payoutFeeMinor <= 0) { throw new PaymentFailure("Los costos sintéticos deben ser positivos y pagados por capital independiente"); }
        this.store = store; this.reservations = reservations; this.fundingFeeMinor = fundingFeeMinor; this.payoutFeeMinor = payoutFeeMinor;
    }

    PaymentStore.Operation create(PaymentStore.Root root, Intent intent) {
        if (intent.amountMinor() <= 0) { throw new PaymentFailure("Importe positivo requerido"); }
        store.reserveCapital(intent.feeMinor(), intent.recoveryMinor());
        UUID operation = UUID.randomUUID();
        store.jdbc.update("insert into payments_operation(id,root_id,command_id,actor_id,kind,amount_minor,credit_minor,external_minor,destination,currency,stable_key,predecessor,settlement_id,fee_minor,recovery_minor,state,available_at,created_at) values(?,?,?,?,?,?,?,?,?,'ARS',?,?,?,?,?,'QUEUED',?,?)",
                operation, root.id(), intent.commandId(), intent.actor().value(), intent.kind(), intent.amountMinor(), intent.creditMinor(), intent.externalMinor(),
                intent.destination(), UUID.randomUUID(), intent.predecessor().orElse(null), intent.settlementId().orElse(null), intent.feeMinor(), intent.recoveryMinor(), store.now(), store.now());
        return store.operation(operation, false);
    }

    void share(PaymentStore.Operation operation, PaymentStore.Allocation allocation, long amountMinor, String destination) {
        store.jdbc.update("insert into payments_operation_share values(?,?,?,?)", operation.id(), allocation.id(), amountMinor, destination);
    }

    PaymentStore.Allocation allocation(PaymentStore.Root root, UUID source, String kind, long amount) {
        var existing = store.allocations(root.agreement().legalPayer(), false).stream().filter(value -> value.rootId().equals(root.id()) && value.sourceId().equals(source)).findFirst();
        if (existing.isPresent()) {
            var allocation = existing.orElseThrow();
            if (!allocation.kind().equals(kind) || allocation.originalMinor() != amount || allocation.confirmedMinor() != 0) { throw new PaymentFailure("La fuente ya tiene otro plan o un efecto confirmado"); }
            return allocation;
        }
        UUID id = UUID.randomUUID();
        store.jdbc.update("insert into payments_allocation(id,root_id,source_id,kind,original_minor) values(?,?,?,?,?)", id, root.id(), source, kind, amount);
        return store.allocations(root.agreement().legalPayer(), false).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    Optional<UUID> failedPredecessor(PaymentStore.Root root, String kind) {
        return store.operations(root.id(), false).stream().filter(value -> value.kind().equals(kind) && Set.of("FAILED_NO_EFFECT", "CANCELLED").contains(value.state()))
                .max(Comparator.comparing(value -> value.availableAt())).map(PaymentStore.Operation::id);
    }

    GatewayRequest request(PaymentStore.Operation operation) {
        var shares = reservations.shares(operation.id());
        String fingerprint = PaymentStore.digest(operation.id() + ":" + operation.rootId() + ":" + operation.kind() + ":" + operation.amountMinor()
                + ":" + operation.creditMinor() + ":" + operation.externalMinor() + ":" + operation.destination() + ":" + operation.feeMinor()
                + ":" + operation.recoveryMinor() + ":" + operation.settlementId() + ":" + operation.predecessor() + ":" + shares);
        long gatewayMinor = Set.of("FUND", "REFUND").contains(operation.kind()) ? operation.externalMinor() : operation.amountMinor();
        return new GatewayRequest(operation.id(), operation.rootId(), operation.stableKey(), operation.kind(), gatewayMinor,
                operation.destination(), Currency.getInstance("ARS"), fingerprint);
    }

    record Intent(UUID commandId, ActorId actor, String kind, long amountMinor, long creditMinor, long externalMinor,
            String destination, long feeMinor, long recoveryMinor, Optional<UUID> settlementId, Optional<UUID> predecessor) {}
}
