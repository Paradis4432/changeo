package ar.changeo.payments;

import ar.changeo.payments.gateway.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static ar.changeo.payments.PaymentStore.LedgerAccount.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
public class PaymentOutcomeService {
    private final PaymentStore store;
    private final PaymentOperations operations;
    private final PaymentReservations reservations;
    private final FinancialGateway gateway;
    private final TransactionTemplate transaction;

    PaymentOutcomeService(PaymentStore store, PaymentOperations operations, PaymentReservations reservations,
            FinancialGateway gateway, PlatformTransactionManager transactions) {
        this.store = store; this.operations = operations; this.reservations = reservations; this.gateway = gateway;
        this.transaction = new TransactionTemplate(transactions);
    }

    public boolean record(GatewayObservation observation) {
        PaymentWorker.standalone();
        if (!gateway.authenticate(observation)) { throw new PaymentFailure("Observación financiera sin autenticación válida"); }
        return transaction.execute(status -> recordAuthenticated(observation));
    }

    private boolean recordAuthenticated(GatewayObservation observation) {
        var discovered = store.operation(observation.request().operationId(), false);
        store.protectExisting(discovered.rootId());
        var operation = store.operation(discovered.id(), false); var root = store.root(operation.rootId(), false);
        if (!operations.request(operation).equals(observation.request()) || operation.dispatchedAt().isEmpty()) {
            throw new PaymentFailure("La observación no corresponde al intento exacto despachado y comprometido");
        }
        String fingerprint = PaymentStore.digest(observation.eventId() + ":" + observation.request() + ":" + observation.outcome()
                + ":" + observation.observedMinor() + ":" + observation.observedAt());
        var previous = store.jdbc.queryForList("select fingerprint,operation_id from payments_observation where event_id=?", observation.eventId());
        if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.getFirst().get("fingerprint")) || !operation.id().equals(previous.getFirst().get("operation_id"))) {
                store.saveOutcome(root.agreement().legalPayer(), List.of(exception(root, operation, observation, fingerprint, "EVENT_BINDING_CONFLICT"))); return false;
            }
            if (observation.outcome() != GatewayObservation.Outcome.REVERSED) { return false; }
        } else {
            store.jdbc.update("insert into payments_observation values(?,?,?,?,?,?,?)", observation.eventId(), operation.id(), operation.stableKey(),
                    observation.outcome().name(), observation.observedMinor(), fingerprint, Timestamp.from(observation.observedAt()));
        }
        if (observation.outcome() == GatewayObservation.Outcome.REVERSED) { return reverse(root, operation, observation, fingerprint); }
        if (operation.applied()) {
            if (observation.outcome() == GatewayObservation.Outcome.FAILED_NO_EFFECT) {
                store.saveOutcome(root.agreement().legalPayer(), List.of(exception(root, operation, observation, fingerprint, "TERMINAL_RESULT_CONFLICT")));
            }
            return false;
        }
        if (Set.of("FAILED_NO_EFFECT", "CANCELLED").contains(operation.state())) {
            if (observation.outcome() != GatewayObservation.Outcome.FAILED_NO_EFFECT) {
                store.saveOutcome(root.agreement().legalPayer(), List.of(exception(root, operation, observation, fingerprint, "TERMINAL_RESULT_CONFLICT")));
            }
            return false;
        }
        if (observation.outcome() == GatewayObservation.Outcome.ABSENT) {
            unknown(operation, "ABSENCE_IS_NOT_TERMINAL"); store.saveOutcome(root.agreement().legalPayer(), List.of(root)); return true;
        }
        if (observation.outcome() == GatewayObservation.Outcome.FAILED_NO_EFFECT) {
            if (operation.observedMinor() != 0 || operation.appliedMinor() != 0) {
                store.saveOutcome(root.agreement().legalPayer(), List.of(exception(root, operation, observation, fingerprint, "PARTIAL_FAILURE_CONFLICT"))); return false;
            }
            reservations.release(operation);
            store.jdbc.update("update payments_operation set state='FAILED_NO_EFFECT',claim_token=null,lease_until=null,reason='PERMANENT_NO_EFFECT' where id=?", operation.id());
            store.saveOutcome(root.agreement().legalPayer(), List.of(root)); return true;
        }
        if (observation.observedMinor() < operation.observedMinor()) { return false; }
        boolean complete = observation.outcome() == GatewayObservation.Outcome.SUCCESS;
        long delta = Math.subtractExact(observation.observedMinor(), operation.observedMinor());
        List<PaymentStore.Entry> entries = new ArrayList<>();
        var application = switch (operation.kind()) {
            case "FUND" -> fund(root, operation, observation, delta, complete, entries);
            case "RELEASE" -> release(root, operation, observation, delta, complete, entries);
            case "PAYOUT" -> payout(root, operation, observation, delta, complete, entries);
            case "REFUND" -> refund(root, operation, observation, delta, complete, entries);
            default -> throw new PaymentFailure("Operación sin política sintética autorizada");
        };
        long appliedDelta = application.amountMinor();
        boolean recovered = !store.operation(operation.id(), false).state().equals("RECOVERY");
        if (complete && recovered && operation.feeMinor() > 0) {
            store.consumeCapital(operation.feeMinor(), 0);
            entries.add(new PaymentStore.Entry(PLATFORM_EXPENSE, operation.feeMinor()));
            entries.add(new PaymentStore.Entry(PLATFORM_CASH, Math.negateExact(operation.feeMinor())));
        }
        if (!entries.isEmpty()) { store.ledger(observation.eventId(), root, entries); }
        if (complete && recovered) {
            if (Math.addExact(operation.appliedMinor(), appliedDelta) != operation.amountMinor()) { throw new PaymentFailure("El resultado no confirma el plan financiero completo exacto"); }
            store.jdbc.update("update payments_operation set state='SUCCESS',applied=true,applied_minor=applied_minor+?,observed_minor=?,claim_token=null,lease_until=null,reason='CONFIRMED' where id=?",
                    appliedDelta, observation.observedMinor(), operation.id());
        } else {
            store.jdbc.update("update payments_operation set applied_minor=applied_minor+?,observed_minor=?,claim_token=null,lease_until=null where id=?", appliedDelta, observation.observedMinor(), operation.id());
            if (recovered) { unknown(operation, "PARTIAL_REQUIRES_RECONCILIATION"); }
        }
        store.saveOutcome(root.agreement().legalPayer(), List.of(application.root())); return true;
    }

    private Application fund(PaymentStore.Root root, PaymentStore.Operation operation, GatewayObservation observation, long delta,
            boolean complete, List<PaymentStore.Entry> entries) {
        var shares = reservations.shares(operation.id());
        for (var share : shares) {
            if (share.fundingKind().equals("EXTERNAL") && delta > 0) {
                store.jdbc.update("update payments_source set confirmed_minor=confirmed_minor+?,backing_minor=backing_minor+?,version=version+1 where id=?", delta, delta, share.sourceId());
                store.jdbc.update("update payments_allocation set confirmed_minor=confirmed_minor+? where id=?", delta, share.allocationId());
                entries.add(entry(CUSTODY_CASH, delta, share.sourceId())); entries.add(entry(JOB_LIABILITY, Math.negateExact(delta), share.sourceId()));
                appliedShare(observation, operation, share, delta);
            }
        }
        long applied = delta; boolean lostBacking = false;
        if (complete) {
            var sources = store.sources(root.agreement().legalPayer(), false);
            boolean backed = shares.stream().filter(value -> value.fundingKind().equals("CREDIT")).allMatch(share ->
                    sources.stream().filter(value -> value.id().equals(share.sourceId())).anyMatch(source -> !source.frozen()
                            && source.creditMinor() >= share.amountMinor() && source.reservedCreditMinor() >= share.amountMinor() && source.backingMinor() >= source.creditMinor()));
            if (!backed) {
                lostBacking = true;
                store.jdbc.update("update payments_operation set state='RECOVERY',reason='LOST_CREDIT_BACKING' where id=?", operation.id());
            } else {
                for (var share : shares) {
                    if (!share.fundingKind().equals("CREDIT")) { continue; }
                    store.jdbc.update("update payments_source set credit_minor=credit_minor-?,reserved_credit_minor=reserved_credit_minor-?,version=version+1 where id=?",
                            share.amountMinor(), share.amountMinor(), share.sourceId());
                    store.jdbc.update("update payments_allocation set confirmed_minor=confirmed_minor+? where id=?", share.amountMinor(), share.allocationId());
                    entries.add(entry(CREDIT_LIABILITY, share.amountMinor(), share.sourceId())); entries.add(entry(JOB_LIABILITY, Math.negateExact(share.amountMinor()), share.sourceId()));
                    appliedShare(observation, operation, share, share.amountMinor()); applied = Math.addExact(applied, share.amountMinor());
                }
            }
        }
        return new Application(root.withFunding(applied, lostBacking), applied);
    }

    private Application release(PaymentStore.Root root, PaymentStore.Operation operation, GatewayObservation observation, long delta,
            boolean complete, List<PaymentStore.Entry> entries) {
        if (delta > 0) {
            entries.add(new PaymentStore.Entry(JOB_LIABILITY, delta)); entries.add(new PaymentStore.Entry(PROVIDER_LIABILITY, Math.negateExact(delta)));
        }
        var projected = root.withRelease(delta);
        if (complete) { queuePayout(projected, operation); }
        return new Application(projected, delta);
    }

    private Application payout(PaymentStore.Root root, PaymentStore.Operation operation, GatewayObservation observation, long delta,
            boolean complete, List<PaymentStore.Entry> entries) {
        for (var allocated : portion(operation, "ORIGINAL_SOURCE", delta)) {
            var share = allocated.share(); long amount = allocated.amountMinor();
            store.jdbc.update("update payments_source set backing_minor=backing_minor-?,version=version+1 where id=?", amount, share.sourceId());
            store.jdbc.update("update payments_allocation set paid_minor=paid_minor+? where id=?", amount, share.allocationId());
            entries.add(entry(CUSTODY_CASH, Math.negateExact(amount), share.sourceId())); entries.add(entry(PROVIDER_LIABILITY, amount, share.sourceId()));
            appliedShare(observation, operation, share, amount);
        }
        return new Application(root.withPayout(delta), delta);
    }

    private Application refund(PaymentStore.Root root, PaymentStore.Operation operation, GatewayObservation observation, long delta,
            boolean complete, List<PaymentStore.Entry> entries) {
        List<Portion> portions = new ArrayList<>(portion(operation, "ORIGINAL_SOURCE", delta));
        if (complete) { portions.addAll(portion(operation, "CREDIT", operation.creditMinor())); }
        long applied = 0;
        for (var portion : portions) {
            var share = portion.share(); long amount = portion.amountMinor();
            var allocation = store.allocations(root.agreement().legalPayer(), false).stream().filter(value -> value.id().equals(share.allocationId())).findFirst().orElseThrow();
            long recovery = allocation.recoveryMinor(amount);
            if (recovery > 0) {
                store.consumeCapital(0, recovery);
                entries.add(new PaymentStore.Entry(PLATFORM_RECOVERY_EXPENSE, recovery)); entries.add(new PaymentStore.Entry(PLATFORM_CASH, Math.negateExact(recovery)));
                entries.add(entry(CUSTODY_CASH, recovery, share.sourceId()));
                store.jdbc.update("update payments_source set backing_minor=backing_minor+?,version=version+1 where id=?", recovery, share.sourceId());
            }
            long held = Math.subtractExact(amount, recovery);
            if (held > 0) { entries.add(entry(root.releasedMinor() > 0 ? PROVIDER_LIABILITY : JOB_LIABILITY, held, share.sourceId())); }
            if (share.destination().equals("CREDIT")) {
                store.jdbc.update("update payments_source set credit_minor=credit_minor+?,version=version+1 where id=?", amount, share.sourceId());
                store.jdbc.update("update payments_allocation set credit_refunded_minor=credit_refunded_minor+?,refunded_minor=refunded_minor+?,reserved_refund_minor=reserved_refund_minor-? where id=?", amount, amount, amount, share.allocationId());
                entries.add(entry(CREDIT_LIABILITY, Math.negateExact(amount), share.sourceId()));
            } else {
                store.jdbc.update("update payments_source set backing_minor=backing_minor-?,cash_refunded_minor=cash_refunded_minor+?,version=version+1 where id=?", amount, amount, share.sourceId());
                store.jdbc.update("update payments_allocation set cash_refunded_minor=cash_refunded_minor+?,refunded_minor=refunded_minor+?,reserved_refund_minor=reserved_refund_minor-? where id=?", amount, amount, amount, share.allocationId());
                entries.add(entry(CUSTODY_CASH, Math.negateExact(amount), share.sourceId()));
            }
            appliedShare(observation, operation, share, amount); applied = Math.addExact(applied, amount);
        }
        boolean continuation = root.bilateralContinuation(); boolean blocked = root.paused() || root.disputed();
        if (complete) {
            var proposals = store.jdbc.queryForList("select continue_job from payments_settlement where id=? and root_id=?", operation.settlementId().orElseThrow(), root.id());
            continuation = Boolean.TRUE.equals(proposals.getFirst().get("continue_job"));
            blocked = store.hasFrozenSource(root) || root.deficitMinor() > 0;
        }
        return new Application(root.withRefund(applied, complete, continuation, blocked), applied);
    }

    private List<Portion> portion(PaymentStore.Operation operation, String destination, long amount) {
        if (amount == 0) { return List.of(); }
        Map<UUID, PaymentReservations.RefundShare> bySource = new HashMap<>();
        List<RefundAllocator.Weight> weights = new ArrayList<>();
        for (var share : reservations.shares(operation.id())) {
            if (!share.destination().equals(destination)) { continue; }
            long already = store.jdbc.queryForObject("select coalesce(sum(amount_minor),0)::bigint from payments_applied_share where operation_id=? and allocation_id=?", Long.class, operation.id(), share.allocationId());
            long remaining = Math.subtractExact(share.amountMinor(), already);
            if (remaining > 0) { weights.add(new RefundAllocator.Weight(share.sourceId(), remaining)); bySource.put(share.sourceId(), share); }
        }
        return RefundAllocator.allocate(amount, weights).stream().map(value -> new Portion(bySource.get(value.sourceId()), value.amountMinor())).toList();
    }

    private void appliedShare(GatewayObservation observation, PaymentStore.Operation operation, PaymentReservations.RefundShare share, long amount) {
        if (amount > 0) { store.jdbc.update("insert into payments_applied_share values(?,?,?,?,?)", observation.eventId(), operation.id(), share.allocationId(), amount, share.destination()); }
    }

    private void queuePayout(PaymentStore.Root root, PaymentStore.Operation release) {
        UUID command = PaymentStore.id("payout:" + release.id());
        var payout = operations.create(root, new PaymentOperations.Intent(command, root.agreement().providerActor(), "PAYOUT", release.amountMinor(), 0, 0,
                "PROVIDER:" + root.agreement().providerActor().value(), operations.payoutFeeMinor, 0, Optional.empty(), Optional.empty()));
        store.jdbc.update("insert into payments_payout_origin values(?,?)", payout.id(), release.id());
        for (var share : reservations.shares(release.id())) {
            store.jdbc.update("insert into payments_operation_share values(?,?,?,?)", payout.id(), share.allocationId(), share.amountMinor(), "ORIGINAL_SOURCE");
        }
        if (root.paused() || root.disputed() || root.deficitMinor() > 0) {
            reservations.release(payout);
            store.jdbc.update("update payments_operation set state='CANCELLED',reason='PAUSED_BEFORE_DISPATCH' where id=?", payout.id());
        }
    }

    private boolean reverse(PaymentStore.Root root, PaymentStore.Operation operation, GatewayObservation observation, String fingerprint) {
        if (!operation.kind().equals("FUND") || observation.observedMinor() == 0) {
            store.saveOutcome(root.agreement().legalPayer(), List.of(exception(root, operation, observation, fingerprint, "REVERSAL_BINDING_CONFLICT"))); return false;
        }
        var external = reservations.shares(operation.id()).stream().filter(value -> value.fundingKind().equals("EXTERNAL")).findFirst().orElseThrow();
        var source = store.sources(root.agreement().legalPayer(), false).stream().filter(value -> value.id().equals(external.sourceId())).findFirst().orElseThrow();
        if (source.reversedMinor() >= observation.observedMinor()) { return false; }
        var exposed = store.allocations(root.agreement().legalPayer(), false).stream().filter(value -> value.sourceId().equals(source.id())).toList();
        Set<UUID> affected = new HashSet<>(); exposed.forEach(value -> affected.add(value.rootId())); affected.add(source.originRoot());
        Map<UUID, PaymentStore.Root> projections = new TreeMap<>(Comparator.comparing(UUID::toString));
        for (UUID id : affected.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var money = store.root(id, false);
            for (var pending : store.operations(id, false)) {
                if (!pending.pending() || pending.dispatchedAt().isPresent()) { continue; }
                reservations.release(pending);
                store.jdbc.update("update payments_operation set state='CANCELLED',generation=generation+1,claim_token=null,lease_until=null,reason='SOURCE_REVERSED' where id=?", pending.id());
            }
            projections.put(id, money.withPause(false));
        }
        store.jdbc.update("update payments_source set frozen=true,version=version+1 where id=?", source.id());
        boolean unresolved = affected.stream().flatMap(id -> store.operations(id, false).stream()).anyMatch(value -> value.uncertain()
                && !value.id().equals(operation.id()));
        long outstanding = Math.subtractExact(source.confirmedMinor(), source.cashRefundedMinor());
        if (unresolved || observation.observedMinor() > outstanding) {
            projections.put(root.id(), exception(projections.get(root.id()), operation, observation, fingerprint, "REVERSAL_RECONCILIATION"));
            store.saveOutcome(root.agreement().legalPayer(), List.copyOf(projections.values())); return false;
        }
        long reversed = Math.subtractExact(observation.observedMinor(), source.reversedMinor());
        Map<UUID, Long> exposures = new HashMap<>();
        for (var allocation : exposed) {
            long liability = Math.subtractExact(allocation.confirmedMinor(), allocation.refundedMinor());
            if (liability > 0) { exposures.merge(allocation.rootId(), liability, Math::addExact); }
        }
        if (source.creditMinor() > 0) { exposures.merge(source.originRoot(), source.creditMinor(), Math::addExact); }
        var weights = exposures.entrySet().stream().map(value -> new RefundAllocator.Weight(value.getKey(), value.getValue())).toList();
        if (weights.isEmpty()) {
            projections.put(root.id(), exception(projections.get(root.id()), operation, observation, fingerprint, "REVERSAL_RECONCILIATION"));
            store.saveOutcome(root.agreement().legalPayer(), List.copyOf(projections.values())); return false;
        }
        for (var loss : RefundAllocator.allocate(reversed, weights)) {
            var affectedRoot = projections.get(loss.sourceId());
            projections.put(affectedRoot.id(), affectedRoot.withDeficit(loss.amountMinor()));
            store.ledger(PaymentStore.id(observation.eventId() + ":" + affectedRoot.id()), affectedRoot,
                    List.of(entry(RECOVERY_RECEIVABLE, loss.amountMinor(), source.id()), entry(CUSTODY_CASH, Math.negateExact(loss.amountMinor()), source.id())));
        }
        store.jdbc.update("update payments_source set reversed_minor=reversed_minor+?,backing_minor=backing_minor-?,version=version+1 where id=?",
                reversed, Math.min(source.backingMinor(), reversed), source.id());
        store.saveOutcome(root.agreement().legalPayer(), List.copyOf(projections.values()));
        return true;
    }

    private PaymentStore.Root exception(PaymentStore.Root root, PaymentStore.Operation operation, GatewayObservation observation, String fingerprint, String reason) {
        store.jdbc.update("insert into payments_exception values(?,?,?,?,?,?,?) on conflict do nothing", UUID.randomUUID(), root.id(), operation.id(), observation.eventId(), fingerprint, reason, store.now());
        store.jdbc.update("update payments_operation set reason=? where id=?", reason, operation.id());
        return root.withPause(false);
    }
    private void unknown(PaymentStore.Operation operation, String reason) {
        store.jdbc.update("update payments_operation set state='UNKNOWN',claim_token=null,lease_until=null,reason=?,available_at=? where id=?", reason,
                Timestamp.from(store.clock.instant().plusSeconds(Math.min(300, 5L * Math.max(1, operation.attempts())))), operation.id());
    }
    private static PaymentStore.Entry entry(PaymentStore.LedgerAccount account, long amount, UUID source) { return new PaymentStore.Entry(account, amount, Optional.of(source)); }
    private record Application(PaymentStore.Root root, long amountMinor) {}
    private record Portion(PaymentReservations.RefundShare share, long amountMinor) {}
}
