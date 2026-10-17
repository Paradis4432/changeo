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
class PromotionBudgetAdapter implements PromotionBudgetAccess {
    private final BillingStore store;

    PromotionBudgetAdapter(BillingStore store) { this.store = store; }

    @Override public void protectForUpdate(BillingBinding binding) { store.protect(binding, true); }

    @Override @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public PromotionHistory requireHistoryForUpdate(BillingBinding binding, Optional<HistoryCursor> cursor) {
        store.protect(binding, false);
        var wallet = store.wallet(BillingStore.walletId(binding), false);
        if (!wallet.provider().equals(binding.provider().value()) || !wallet.payer().equals(binding.originalPayer().value())) { throw BillingStore.failure("Historial ajeno al pagador original"); }
        long version = store.effectiveVersion(wallet);
        cursor.ifPresent(value -> {
            if (!value.walletId().equals(wallet.id()) || value.walletVersion() != version
                    || store.jdbc.queryForObject("select count(*) from payments_promotion_adjustment where wallet_id=? and id=? and occurred_at=?", Integer.class,
                    wallet.id(), value.afterId(), Timestamp.from(value.afterTime())) != 1) { throw BillingStore.failure("El historial cambió; volvé a su primera página"); }
        });
        var award = store.jdbc.query("select face_minor,created_at from payments_promotion_award where wallet_id=?",
                (row, index) -> new Award(row.getLong(1), row.getTimestamp(2).toInstant()), wallet.id()).stream().findFirst();
        List<Object> arguments = new ArrayList<>(List.of(wallet.id()));
        String continuation = cursor.map(value -> {
            arguments.add(Timestamp.from(value.afterTime())); arguments.add(Timestamp.from(value.afterTime())); arguments.add(value.afterId().toString());
            return " and (occurred_at<? or (occurred_at=? and id::text<?))";
        }).orElse("");
        var rows = store.jdbc.query("select id,kind,amount_minor,occurred_at from payments_promotion_adjustment where wallet_id=?" + continuation + " order by occurred_at desc,id::text desc limit 21",
                (row, index) -> new Correction(row.getObject(1, UUID.class), CorrectionKind.valueOf(row.getString(2)), row.getLong(3), row.getTimestamp(4).toInstant()), arguments.toArray());
        var page = rows.stream().limit(20).toList();
        var next = rows.size() > 20 ? Optional.of(new HistoryCursor(wallet.id(), version, page.getLast().occurredAt(), page.getLast().eventId())) : Optional.<HistoryCursor>empty();
        return new PromotionHistory(wallet.outstanding(), wallet.reserved(), wallet.redeemed(), wallet.returned(), wallet.debt(), wallet.available(), wallet.invalid(), award, page, next);
    }

    @Override public AllocationStatus allocationStatusForUpdate(BillingBinding binding) {
        store.protect(binding, false);
        var wallet = store.wallet(BillingStore.walletId(binding), false);
        if (store.jdbc.queryForObject("select count(*) from payments_promotion_award where wallet_id=?", Integer.class, wallet.id()) == 1) {
            if (wallet.returned() == 100 && store.jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class) < 100) { return AllocationStatus.BACKING_PENDING; }
            return AllocationStatus.FUNDED;
        }
        if (store.jdbc.queryForObject("select count(*) from payments_promotion_award where campaign='SL1B-LOYALTY-2' and (provider_id=? or payer_id=?)", Integer.class, binding.provider().value(), binding.originalPayer().value()) > 0) { return AllocationStatus.CAP_USED; }
        return store.jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class) >= 100 ? AllocationStatus.READY : AllocationStatus.BACKING_PENDING;
    }

    @Override public AwardResult awardForUpdate(BillingBinding binding, PromotionClaim claim) {
        if (!claim.provider().equals(binding.provider()) || !claim.originalBillingPayer().equals(binding.originalPayer())) { throw BillingStore.failure("Premio ajeno al pagador original"); }
        var wallet = store.wallet(BillingStore.walletId(binding), false);
        if (store.jdbc.queryForObject("select count(*) from payments_promotion_award where campaign=? and (provider_id=? or payer_id=?)", Integer.class,
                claim.campaign(), binding.provider().value(), binding.originalPayer().value()) > 0) {
            return store.jdbc.queryForObject("select count(*) from payments_promotion_award where wallet_id=?", Integer.class, wallet.id()) == 1 ? AwardResult.EXISTING : AwardResult.CAP_USED;
        }
        if (wallet.outstanding() + wallet.redeemed() + wallet.returned() != 0) { throw BillingStore.failure("El premio ya tiene una asignación histórica"); }
        if (store.jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class) < 100) { return AwardResult.BACKING_PENDING; }
        store.budget("PROMOTION", -100, 100, 0);
        UUID award = BillingStore.id("award:" + wallet.id());
        store.jdbc.update("insert into payments_promotion_award values(?,?,?,?,?,?,100,?)", award, wallet.id(), claim.campaign(), binding.provider().value(), binding.originalPayer().value(), claim.evidenceDigest(), store.now());
        store.saveWallet(wallet.project(100, 0, 0, 0, 0, false, Optional.of(claim.evidenceDigest())));
        store.ledger(award, wallet.id(), null, new BillingStore.Entry("PROMOTION_AVAILABLE", -100), new BillingStore.Entry("PROMOTION_LIABILITY", 100));
        return AwardResult.AWARDED;
    }

    @Override public void adjustQualificationForUpdate(BillingBinding binding, String evidenceDigest, boolean valid) {
        if (!evidenceDigest.matches("[0-9a-f]{64}")) { throw BillingStore.failure("Evidencia incompatible"); }
        var wallet = store.wallet(BillingStore.walletId(binding), false);
        if (wallet.evidence().filter(evidenceDigest::equals).isPresent() && wallet.invalid() == !valid) { return; }
        if (wallet.outstanding() + wallet.redeemed() + wallet.returned() == 0) { return; }
        if (valid && wallet.returned() == 100 && store.jdbc.queryForObject("select available_minor from payments_subscription_budget where kind='PROMOTION'", Long.class) < 100) { return; }
        String kind; long amount = 0;
        var next = wallet.project(wallet.outstanding(), wallet.reserved(), wallet.redeemed(), wallet.returned(), wallet.debt(), !valid, Optional.of(evidenceDigest));
        if (!valid && wallet.reserved() > 0) { kind = "HELD"; }
        else if (!valid && wallet.redeemed() == 100) { kind = "DEBT"; amount = 100; next = next.project(0, 0, 100, 0, 100, true, next.evidence()); }
        else if (!valid && wallet.outstanding() == 100) {
            kind = "INVALIDATED"; amount = 100; store.budget("PROMOTION", 100, -100, 0);
            next = next.project(0, 0, 0, 100, 0, true, next.evidence());
        } else if (valid && wallet.redeemed() == 100) { kind = "DEBT_CLEARED"; amount = wallet.debt(); next = next.project(0, 0, 100, 0, 0, false, next.evidence()); }
        else if (valid && wallet.returned() == 100) {
            kind = "REINSTATED"; amount = 100; store.budget("PROMOTION", -100, 100, 0);
            next = next.project(100, 0, 0, 0, 0, false, next.evidence());
        } else { kind = valid ? "REINSTATED" : "INVALIDATED"; }
        UUID adjustment = UUID.randomUUID();
        store.jdbc.update("insert into payments_promotion_adjustment values(?,?,?,?,?,?)", adjustment, wallet.id(), evidenceDigest, kind, amount, store.now());
        store.saveWallet(next);
        if (kind.equals("INVALIDATED") && amount > 0) { store.ledger(adjustment, wallet.id(), null, new BillingStore.Entry("PROMOTION_LIABILITY", -100), new BillingStore.Entry("PROMOTION_AVAILABLE", 100)); }
        if (kind.equals("REINSTATED") && wallet.returned() == 100) { store.ledger(adjustment, wallet.id(), null, new BillingStore.Entry("PROMOTION_AVAILABLE", -100), new BillingStore.Entry("PROMOTION_LIABILITY", 100)); }
    }
}
