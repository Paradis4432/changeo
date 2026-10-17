package ar.changeo.payments.billing;

import ar.changeo.payments.api.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
class SubscriptionBillingAdapter implements SubscriptionBillingAccess {
    private final BillingStore store;

    SubscriptionBillingAdapter(BillingStore store) { this.store = store; }

    @Override @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BillingTargets discoverTargets(BillingBinding binding) {
        UUID wallet = BillingStore.walletId(binding);
        boolean present = store.jdbc.queryForObject("select count(*) from payments_promotion_wallet where id=?", Integer.class, wallet) == 1;
        var roots = store.jdbc.query("select id from payments_billing_root where wallet_id=? order by id::text limit 201", (row, index) -> row.getObject(1, UUID.class), wallet);
        var operations = store.jdbc.query("select o.id from payments_billing_operation o join payments_billing_root r on r.id=o.root_id where r.wallet_id=? order by o.id::text limit 2001", (row, index) -> row.getObject(1, UUID.class), wallet);
        var policies = store.jdbc.query("select distinct policy_version from payments_billing_root where wallet_id=?", (row, index) -> row.getString(1), wallet);
        if (roots.size() > 200 || operations.size() > 2000 || policies.size() > 200) { throw BillingStore.failure("Historial de facturación excede el límite técnico completo"); }
        var walletVersion = store.jdbc.query("select version from payments_promotion_wallet where id=?", (row, index) -> row.getLong(1), wallet).stream().findFirst();
        Map<UUID, Long> versions = new HashMap<>();
        store.jdbc.query("select o.id,o.version from payments_billing_operation o join payments_billing_root r on r.id=o.root_id where r.wallet_id=? order by o.id::text limit 2001", (row, index) -> { versions.put(row.getObject(1, UUID.class), row.getLong(2)); return 0; }, wallet);
        return new BillingTargets(wallet, present, Set.copyOf(roots), Set.copyOf(operations), Set.copyOf(policies), walletVersion, versions);
    }

    @Override public PreparedBillingCharge prepareForUpdate(BillingChargeRequest request) {
        store.protect(request.binding(), true);
        var history = store.charges(request.binding());
        var replay = history.stream().filter(charge -> charge.request().command().equals(request.command())).findFirst();
        if (replay.isPresent()) {
            if (!replay.orElseThrow().request().equals(request)) { throw BillingStore.failure("Comando ligado a otros términos"); }
            return replay.orElseThrow().prepared();
        }
        if (history.stream().anyMatch(BillingStore.Charge::pending)) { throw BillingStore.failure("Conciliá la misma clave incierta antes de otro cobro"); }
        if ((unresolvedReturn(history) || history.stream().anyMatch(charge -> charge.state().equals("RECOVERY"))) && request.predecessor().isEmpty()) {
            throw BillingStore.failure("La devolución requiere una nueva compra explícita vinculada al historial");
        }
        UUID root = BillingStore.id("billing:" + request.binding().enrollmentId() + ":" + request.periodStart());
        var samePeriod = history.stream().filter(charge -> charge.rootId().equals(root)).toList();
        if (request.predecessor().isPresent()) {
            var previous = history.stream().filter(charge -> charge.id().equals(request.predecessor().orElseThrow())).findFirst()
                    .orElseThrow(() -> BillingStore.failure("Predecesor ajeno a la inscripción original"));
            boolean noEffect = previous.state().equals("FAILED_NO_EFFECT") && previous.collected() == 0 && previous.returned() == 0 && previous.restored();
            boolean returnedPurchase = previous.state().equals("REVERSED") && !request.periodStart().equals(previous.request().periodStart());
            if ((!noEffect && !returnedPurchase) || history.stream().anyMatch(charge -> charge.request().predecessor().filter(previous.id()::equals).isPresent())) {
                throw BillingStore.failure("El predecesor no permite otra clave");
            }
            if (noEffect && (!previous.rootId().equals(root) || !sameTerms(previous.request(), request))
                    || returnedPurchase && previous.request().consentId().equals(request.consentId())) { throw BillingStore.failure("El reintento conserva exactamente el consentimiento y los importes originales"); }
        } else if (!samePeriod.isEmpty()) { throw BillingStore.failure("El período conserva su intento original"); }
        if (!samePeriod.isEmpty()) {
            var previous = samePeriod.getFirst().request();
            if (!sameTerms(previous, request)) {
                throw BillingStore.failure("Un reintento conserva su período y consentimiento exactos");
            }
        }
        var wallet = store.wallet(BillingStore.walletId(request.binding()), false);
        long promotion = request.permittedPromotionMinor() == 100 && wallet.available() >= 100 ? 100 : 0;
        if (request.permittedPromotionMinor() != promotion) { throw BillingStore.failure("El crédito mostrado cambió; revisá el importe neto antes de consentir"); }
        if (samePeriod.stream().anyMatch(previous -> previous.promotion() != promotion)) { throw BillingStore.failure("El reintento conserva el crédito y neto originales"); }
        UUID operation = BillingStore.id("billing-operation:" + request.binding().enrollmentId() + ":" + request.command());
        UUID key = BillingStore.id("billing-key:" + operation);
        store.jdbc.update("insert into payments_billing_root(id,wallet_id,enrollment_id,generation,provider_id,original_actor,payer_id,consent_id,policy_version,policy_digest,period_start,period_end,gross_minor,currency,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'ARS',?) on conflict do nothing",
                root, wallet.id(), request.binding().enrollmentId(), request.binding().generation(), request.binding().provider().value(), request.binding().originalActor().value(),
                request.binding().originalPayer().value(), request.consentId(), request.policyVersion(), request.policyDigest(), Timestamp.from(request.periodStart()), Timestamp.from(request.periodEnd()), request.grossMinor(), store.now());
        store.requireRoot(root, request);
        store.budget("FEE", -request.feeMinor(), request.feeMinor(), 0);
        store.jdbc.update("insert into payments_billing_operation(id,root_id,command_id,stable_key,predecessor,permitted_promotion_minor,promotion_minor,external_minor,fee_minor,state,available_at,reason,created_at) values(?,?,?,?,?,?,?,?,?,'QUEUED',?,'QUEUED',?)",
                operation, root, request.command(), key, request.predecessor().orElse(null), request.permittedPromotionMinor(), promotion, Math.subtractExact(request.grossMinor(), promotion), request.feeMinor(), store.now(), store.now());
        store.declareCreated(wallet.id(), root, operation);
        if (promotion > 0) { store.saveWallet(wallet.project(wallet.outstanding(), Math.addExact(wallet.reserved(), promotion), wallet.redeemed(), wallet.returned(), wallet.debt(), wallet.invalid(), wallet.evidence())); }
        store.ledger(BillingStore.id("billing-reserve:" + operation), wallet.id(), operation,
                new BillingStore.Entry("FEE_AVAILABLE", -request.feeMinor()), new BillingStore.Entry("FEE_RESERVED", request.feeMinor()));
        return store.charge(operation).prepared();
    }

    @Override public BillingFinancialStatus requireStatusForUpdate(BillingBinding binding) {
        store.protect(binding, false);
        return protectedStatus(binding);
    }

    @Override public Map<BillingBinding, BillingFinancialStatus> requireStatusesForUpdate(List<BillingBinding> bindings) {
        store.protectAll(bindings, false);
        Map<BillingBinding, BillingFinancialStatus> results = new HashMap<>();
        for (var binding : bindings) { results.put(binding, protectedStatus(binding)); }
        return Map.copyOf(results);
    }

    private BillingFinancialStatus protectedStatus(BillingBinding binding) {
        var charges = store.charges(binding);
        for (var charge : charges) { if (!charge.request().binding().equals(binding)) { throw BillingStore.failure("Inscripción financiera original incompatible"); } }
        var wallet = store.wallet(BillingStore.walletId(binding), false);
        var periods = charges.stream().map(charge -> new BillingFinancialStatus.Period(charge.id(), charge.request().periodStart(), charge.request().periodEnd(),
                charge.request().policyVersion(), charge.state(), charge.request().grossMinor(), charge.promotion(), charge.collected(), charge.returned(),
                charge.feeSpent() ? charge.request().feeMinor() : 0, charge.success() && charge.state().equals("SUCCESS") && charge.returned() == 0, charge.version(),
                charge.request().consentId(), charge.request().policyDigest(), charge.stableKey(), charge.request().feeMinor(), charge.request().predecessor())).toList();
        var pending = charges.stream().filter(BillingStore.Charge::pending).findFirst().map(BillingStore.Charge::prepared);
        boolean frozen = unresolvedReturn(charges) || charges.stream().anyMatch(charge -> Set.of("UNKNOWN", "RECOVERY").contains(charge.state())) || wallet.invalid() || wallet.debt() > 0;
        return new BillingFinancialStatus(binding, periods, pending, frozen, wallet.available(), wallet.debt());
    }

    @Override public void cancelForUpdate(BillingBinding binding, UUID command) {
        store.protect(binding, false);
        for (var charge : store.charges(binding)) {
            if (charge.dispatchedAt().isEmpty() && Set.of("QUEUED", "CLAIMED").contains(charge.state())) { cancelHeld(charge, "CONSENT_WITHDRAWN"); }
        }
    }

    void cancelHeld(BillingStore.Charge charge, String reason) {
        store.requireHeld(charge);
        if (charge.dispatchedAt().isPresent() || charge.success() || charge.collected() > 0 || charge.restored()) { return; }
        var wallet = store.wallet(BillingStore.walletId(charge.request().binding()), false);
        long returnedPromotion = 0;
        if (charge.promotion() > 0) {
            wallet = wallet.project(wallet.outstanding(), Math.subtractExact(wallet.reserved(), charge.promotion()), wallet.redeemed(), wallet.returned(), wallet.debt(), wallet.invalid(), wallet.evidence());
            if (wallet.invalid() && wallet.reserved() == 0 && wallet.outstanding() > 0) {
                returnedPromotion = wallet.outstanding(); store.budget("PROMOTION", returnedPromotion, -returnedPromotion, 0);
                wallet = wallet.project(0, 0, wallet.redeemed(), Math.addExact(wallet.returned(), returnedPromotion), wallet.debt(), true, wallet.evidence());
                store.jdbc.update("insert into payments_promotion_adjustment values(?,?,?,?,?,?) on conflict do nothing", UUID.randomUUID(), wallet.id(), wallet.evidence().orElseThrow(), "INVALIDATED", returnedPromotion, store.now());
            }
            store.saveWallet(wallet);
        }
        store.budget("FEE", charge.request().feeMinor(), -charge.request().feeMinor(), 0);
        store.jdbc.update("update payments_billing_operation set state='CANCELLED',reservations_restored=true,generation=generation+1,claim_token=null,lease_until=null,reason=?,version=version+1 where id=?", reason, charge.id());
        store.ledger(BillingStore.id("billing-cancel:" + charge.id()), wallet.id(), charge.id(), new BillingStore.Entry("FEE_RESERVED", -charge.request().feeMinor()), new BillingStore.Entry("FEE_AVAILABLE", charge.request().feeMinor()),
                new BillingStore.Entry("PROMOTION_LIABILITY", -returnedPromotion), new BillingStore.Entry("PROMOTION_AVAILABLE", returnedPromotion));
    }

    @Override public void authorizePreparedForUpdate(PreparedBillingCharge prepared, QualifiedPromotionUse promotion) {
        var charge = store.charge(prepared.operationId()); store.requireHeld(charge);
        if (!charge.prepared().equals(prepared) || !charge.state().equals("CLAIMED") || charge.dispatchedAt().isPresent()
                || charge.leaseUntil().filter(instant -> instant.isAfter(store.clock.instant())).isEmpty()
                || promotion.minor() != charge.promotion() || !store.clock.instant().isBefore(charge.request().periodEnd())
                || store.clock.instant().isBefore(charge.request().periodStart())) { throw BillingStore.failure("El intento perdió su autorización exacta"); }
        var wallet = store.wallet(BillingStore.walletId(charge.request().binding()), false);
        if (charge.promotion() > 0 && (wallet.invalid() || wallet.debt() > 0 || wallet.reserved() < charge.promotion()
                || !wallet.evidence().filter(promotion.evidenceDigest()::equals).isPresent())) { throw BillingStore.failure("El crédito requiere evidencia vigente"); }
        store.jdbc.update("update payments_billing_operation set state='DISPATCHING',dispatched_at=?,reason='AUTHORIZED',version=version+1 where id=?", store.now(), charge.id());
    }

    private static boolean unresolvedReturn(List<BillingStore.Charge> charges) {
        return charges.stream().anyMatch(charge -> charge.state().equals("REVERSED")
                && charges.stream().noneMatch(next -> next.request().predecessor().filter(charge.id()::equals).isPresent()));
    }
    private static boolean sameTerms(BillingChargeRequest first, BillingChargeRequest second) {
        return first.binding().equals(second.binding()) && first.consentId().equals(second.consentId()) && first.policyVersion().equals(second.policyVersion())
                && first.policyDigest().equals(second.policyDigest()) && first.periodStart().equals(second.periodStart()) && first.periodEnd().equals(second.periodEnd())
                && first.grossMinor() == second.grossMinor() && first.currency().equals(second.currency())
                && first.feeMinor() == second.feeMinor() && first.permittedPromotionMinor() == second.permittedPromotionMinor();
    }
}
