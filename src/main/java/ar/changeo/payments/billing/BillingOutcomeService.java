package ar.changeo.payments.billing;

import ar.changeo.payments.gateway.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
public class BillingOutcomeService {
    private final BillingStore store;
    private final FinancialGateway gateway;
    private final TransactionTemplate transaction;

    BillingOutcomeService(BillingStore store, FinancialGateway gateway, PlatformTransactionManager transactions) {
        this.store = store; this.gateway = gateway; this.transaction = new TransactionTemplate(transactions);
    }

    public void record(GatewayObservation incoming) {
        standalone();
        if (!incoming.request().kind().equals("SUBSCRIPTION") || !gateway.authenticate(incoming)) { throw BillingStore.failure("Observación de suscripción no autenticada"); }
        var current = gateway.query(incoming.request());
        if (!gateway.authenticate(current) || !current.request().equals(incoming.request())) { throw BillingStore.failure("Verdad financiera no autenticada"); }
        transaction.executeWithoutResult(status -> apply(incoming, current));
    }

    private void apply(GatewayObservation incoming, GatewayObservation current) {
        var discovered = store.charge(incoming.request().operationId());
        store.protect(discovered.request().binding(), false);
        var charge = store.charge(discovered.id()); store.requireHeld(charge);
        if (!charge.gatewayRequest().equals(incoming.request()) || charge.dispatchedAt().isEmpty()) { throw BillingStore.failure("Observación ajena a un despacho autorizado"); }
        remember(incoming, charge);
        if (!incoming.eventId().equals(current.eventId())) { remember(current, charge); }
        if (Set.of("FAILED_NO_EFFECT", "CANCELLED").contains(charge.state())) {
            if (current.outcome() != GatewayObservation.Outcome.FAILED_NO_EFFECT && current.outcome() != GatewayObservation.Outcome.ABSENT) { freeze(charge, current, "TERMINAL_CONFLICT"); }
            return;
        }
        if (charge.state().equals("REVERSED")) { return; }
        if (current.outcome() == GatewayObservation.Outcome.FAILED_NO_EFFECT && (charge.collected() > 0 || charge.success())) {
            freeze(charge, current, "POSITIVE_NO_EFFECT_CONFLICT"); return;
        }
        if (current.outcome() == GatewayObservation.Outcome.REVERSED && current.observedMinor() < charge.collected()) {
            freeze(charge, current, "DECREASING_RETURN_CONFLICT"); return;
        }
        long observed = current.outcome() == GatewayObservation.Outcome.ABSENT || current.outcome() == GatewayObservation.Outcome.FAILED_NO_EFFECT ? 0 : current.observedMinor();
        long collected = Math.max(charge.collected(), observed);
        long returned = current.outcome() == GatewayObservation.Outcome.REVERSED ? observed : charge.returned();
        boolean success = charge.success() || current.outcome() == GatewayObservation.Outcome.SUCCESS;
        boolean redeem = current.outcome() == GatewayObservation.Outcome.SUCCESS && !charge.success();
        boolean terminalRestore = !success && (current.outcome() == GatewayObservation.Outcome.FAILED_NO_EFFECT || current.outcome() == GatewayObservation.Outcome.REVERSED);
        boolean restore = terminalRestore && !charge.restored();
        boolean spendFee = collected > 0 && !charge.feeSpent() && charge.request().feeMinor() > 0;
        long collectDelta = Math.subtractExact(collected, charge.collected());
        long returnDelta = Math.subtractExact(returned, charge.returned());
        var wallet = store.wallet(BillingStore.walletId(charge.request().binding()), false);
        List<BillingStore.Entry> entries = new ArrayList<>();
        if (collectDelta > 0) { entries.add(new BillingStore.Entry("BILLING_CASH", collectDelta)); entries.add(new BillingStore.Entry("PENDING_BILLING", -collectDelta)); }
        if (spendFee) {
            store.budget("FEE", 0, -charge.request().feeMinor(), charge.request().feeMinor());
            entries.add(new BillingStore.Entry("FEE_RESERVED", -charge.request().feeMinor())); entries.add(new BillingStore.Entry("FEE_EXPENSE", charge.request().feeMinor()));
        }
        if (redeem) {
            entries.add(new BillingStore.Entry("PENDING_BILLING", charge.gatewayRequest().amountMinor()));
            entries.add(new BillingStore.Entry("SUBSCRIPTION_REVENUE", -charge.request().grossMinor()));
            entries.add(new BillingStore.Entry("PROMOTION_COST", charge.promotion()));
            if (charge.promotion() > 0) {
                store.budget("PROMOTION", 0, -charge.promotion(), charge.promotion());
                wallet = wallet.project(Math.subtractExact(wallet.outstanding(), charge.promotion()), Math.subtractExact(wallet.reserved(), charge.promotion()),
                        Math.addExact(wallet.redeemed(), charge.promotion()), wallet.returned(), wallet.invalid() ? 100 : wallet.debt(), wallet.invalid(), wallet.evidence());
                entries.add(new BillingStore.Entry("PROMOTION_LIABILITY", -charge.promotion())); entries.add(new BillingStore.Entry("PROMOTION_CONSUMED", charge.promotion()));
                if (wallet.invalid()) { adjustment(wallet, "DEBT", 100); }
            }
        }
        if (returnDelta > 0) { entries.add(new BillingStore.Entry("BILLING_CASH", -returnDelta)); entries.add(new BillingStore.Entry(charge.success() ? "SUBSCRIPTION_REVENUE" : "PENDING_BILLING", returnDelta)); }
        if (restore) {
            if (collected == 0 && !charge.feeSpent()) {
                store.budget("FEE", charge.request().feeMinor(), -charge.request().feeMinor(), 0);
                entries.add(new BillingStore.Entry("FEE_RESERVED", -charge.request().feeMinor())); entries.add(new BillingStore.Entry("FEE_AVAILABLE", charge.request().feeMinor()));
            }
            if (charge.promotion() > 0) {
                wallet = wallet.project(wallet.outstanding(), Math.subtractExact(wallet.reserved(), charge.promotion()), wallet.redeemed(), wallet.returned(), wallet.debt(), wallet.invalid(), wallet.evidence());
                if (wallet.invalid() && wallet.reserved() == 0 && wallet.outstanding() > 0) {
                    long deallocate = wallet.outstanding(); store.budget("PROMOTION", deallocate, -deallocate, 0);
                    wallet = wallet.project(0, 0, wallet.redeemed(), Math.addExact(wallet.returned(), deallocate), wallet.debt(), true, wallet.evidence());
                    entries.add(new BillingStore.Entry("PROMOTION_LIABILITY", -deallocate)); entries.add(new BillingStore.Entry("PROMOTION_AVAILABLE", deallocate));
                    adjustment(wallet, "INVALIDATED", deallocate);
                }
            }
        }
        String state = switch (current.outcome()) {
            case SUCCESS -> "SUCCESS";
            case FAILED_NO_EFFECT -> "FAILED_NO_EFFECT";
            case REVERSED -> "REVERSED";
            case ABSENT, PARTIAL -> "UNKNOWN";
        };
        boolean monetaryChange = collectDelta != 0 || returnDelta != 0 || redeem || restore || spendFee;
        if (monetaryChange && charge.promotion() > 0 && (redeem || restore)) { store.saveWallet(wallet); }
        if (!entries.isEmpty()) { store.ledger(current.eventId(), wallet.id(), charge.id(), entries.toArray(BillingStore.Entry[]::new)); }
        if (monetaryChange || !charge.state().equals(state)) {
            store.jdbc.update("update payments_billing_operation set state=?,collected_minor=?,returned_minor=?,success_applied=?,promotion_redeemed=?,reservations_restored=?,fee_spent=?,claim_token=null,lease_until=null,available_at=?,reason=?,version=version+1 where id=?",
                    state, collected, returned, success, charge.redeemed() || (redeem && charge.promotion() > 0), charge.restored() || restore,
                    charge.feeSpent() || spendFee, java.sql.Timestamp.from(store.clock.instant().plusSeconds(30)), state, charge.id());
        }
    }

    private void remember(GatewayObservation observation, BillingStore.Charge charge) {
        var rows = store.jdbc.queryForList("select operation_id,stable_key,outcome,amount_minor,fingerprint,observed_at from payments_billing_observation where event_id=?", observation.eventId());
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (!charge.id().equals(row.get("operation_id")) || !charge.stableKey().equals(row.get("stable_key")) || !observation.outcome().name().equals(row.get("outcome"))
                    || observation.observedMinor() != ((Number) row.get("amount_minor")).longValue() || !observation.request().fingerprint().equals(row.get("fingerprint"))
                    || !observation.observedAt().equals(((java.sql.Timestamp) row.get("observed_at")).toInstant())) {
                throw BillingStore.failure("Evento financiero ligado a otra verdad");
            }
            return;
        }
        store.jdbc.update("insert into payments_billing_observation values(?,?,?,?,?,?,?)", observation.eventId(), charge.id(), charge.stableKey(), observation.outcome().name(), observation.observedMinor(), observation.request().fingerprint(), java.sql.Timestamp.from(observation.observedAt()));
    }

    private void freeze(BillingStore.Charge charge, GatewayObservation observation, String reason) {
        store.jdbc.update("insert into payments_billing_exception values(?,?,?,?,?,?) on conflict do nothing", UUID.randomUUID(), charge.id(), observation.eventId(), observation.request().fingerprint(), reason, store.now());
        store.jdbc.update("update payments_billing_operation set state='RECOVERY',reason=?,claim_token=null,lease_until=null,version=version+1 where id=?", reason, charge.id());
    }

    private void adjustment(BillingStore.Wallet wallet, String kind, long amount) {
        store.jdbc.update("insert into payments_promotion_adjustment values(?,?,?,?,?,?) on conflict do nothing", UUID.randomUUID(), wallet.id(), wallet.evidence().orElseThrow(), kind, amount, store.now());
    }

    private static void standalone() { if (TransactionSynchronizationManager.isActualTransactionActive()) { throw BillingStore.failure("La conciliación requiere una transacción independiente"); } }
}
