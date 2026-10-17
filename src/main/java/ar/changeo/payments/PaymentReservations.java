package ar.changeo.payments;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
class PaymentReservations {
    private final PaymentStore store;
    PaymentReservations(PaymentStore store) { this.store = store; }

    void release(PaymentStore.Operation operation) {
        var root = store.root(operation.rootId(), false);
        var allocations = store.allocations(root.agreement().legalPayer(), false).stream().filter(value -> value.rootId().equals(root.id())).toList();
        if (operation.kind().equals("FUND")) {
            for (var allocation : allocations) {
                if (allocation.kind().equals("CREDIT") && allocation.confirmedMinor() == 0) {
                    store.jdbc.update("update payments_source set reserved_credit_minor=reserved_credit_minor-?,version=version+1 where id=?",
                            allocation.originalMinor(), allocation.sourceId());
                }
            }
        } else if (operation.kind().equals("REFUND")) {
            for (var share : shares(operation.id())) {
                store.jdbc.update("update payments_allocation set reserved_refund_minor=reserved_refund_minor-? where id=?", share.amountMinor(), share.allocationId());
            }
        }
        store.releaseCapital(operation);
    }

    List<RefundShare> shares(UUID operation) {
        return store.jdbc.query("select s.*,a.source_id,a.kind from payments_operation_share s join payments_allocation a on a.id=s.allocation_id where operation_id=? order by a.source_id::text",
                (row, index) -> new RefundShare(row.getObject("allocation_id", UUID.class), row.getObject("source_id", UUID.class), row.getString("kind"),
                        row.getLong("amount_minor"), row.getString("destination")), operation);
    }

    record RefundShare(UUID allocationId, UUID sourceId, String fundingKind, long amountMinor, String destination) {}
}
