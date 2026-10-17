package ar.changeo.payments;

import ar.changeo.identity.api.AccountId;
import ar.changeo.jobs.api.FinancialAgreement;
import ar.changeo.payments.api.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class PaymentLoyaltyMoneyAdapter implements JobLoyaltyMoneyAccess {
    private final PaymentStore store;

    PaymentLoyaltyMoneyAdapter(PaymentStore store) { this.store = store; }

    @Override public LoyaltyMoneyDiscovery discover(List<FinancialAgreement> agreements) {
        validate(agreements);
        boolean capital = !store.jdbc.queryForList("select currency from payments_capital where currency='ARS'").isEmpty();
        Set<AccountId> payers = new HashSet<>(); Set<UUID> sources = new HashSet<>(), allocations = new HashSet<>(), roots = new HashSet<>(), operations = new HashSet<>();
        Map<UUID, Long> payerVersions = new HashMap<>(), sourceVersions = new HashMap<>(), rootVersions = new HashMap<>(), generations = new HashMap<>();
        for (AccountId payer : payers(agreements)) {
            var balance = store.jdbc.query("select version from payments_balance where payer_id=?", (row, index) -> row.getLong(1), payer.value());
            if (!balance.isEmpty()) { payers.add(payer); payerVersions.put(payer.value(), balance.getFirst()); }
            var payerSources = store.sources(payer, false); payerSources.forEach(source -> sources.add(source.id()));
            store.jdbc.query("select id,version from payments_source where payer_id=? order by id::text limit 201", (row, index) -> { sourceVersions.put(row.getObject(1, UUID.class), row.getLong(2)); return 0; }, payer.value());
            store.allocations(payer, false).forEach(allocation -> allocations.add(allocation.id()));
            store.roots(payer, false).forEach(root -> { roots.add(root.id()); rootVersions.put(root.id(), root.version()); });
            store.loyaltyOperations(payer, selectedRoots(agreements, payer)).forEach(operation -> { operations.add(operation.id()); generations.put(operation.id(), operation.generation()); });
        }
        return new LoyaltyMoneyDiscovery(ordered(agreements), capital, payers, sources, allocations, roots, operations, payerVersions, sourceVersions, rootVersions, generations);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, JobLoyaltyMoneyFacts> requireForUpdate(List<FinancialAgreement> agreements, LoyaltyMoneyDiscovery discovery) {
        validate(agreements);
        if (!ordered(agreements).equals(discovery.agreements())) { throw new PaymentFailure("Lote de términos financieros cambiado"); }
        store.jdbc.queryForList("select currency from payments_capital where currency='ARS' for update");
        for (AccountId payer : payers(agreements)) { store.jdbc.queryForList("select payer_id from payments_balance where payer_id=? for update", payer.value()); }
        lock("payments_source", discovery.sources()); lock("payments_allocation", discovery.allocations()); lock("payments_root", discovery.roots()); lock("payments_operation", discovery.operations());
        if (!discovery.equals(discover(agreements))) { throw new PaymentFailure("La exposición monetaria cambió; volvé a descubrir el lote completo"); }
        Map<UUID, JobLoyaltyMoneyFacts> result = new HashMap<>();
        for (var agreement : agreements) {
            var root = store.find(agreement.jobId(), agreement.agreement(), false);
            if (!discovery.capitalPresent() || !discovery.payersPresent().contains(agreement.legalPayer()) || root.isEmpty()) {
                result.put(agreement.jobId(), new JobLoyaltyMoneyFacts(agreement, false, "FINANCIAL_ROOT_PENDING", 0, 0, 0, 0, 0, 0)); continue;
            }
            var selected = root.orElseThrow(); selected.requireBinding(agreement);
            var payerSources = store.sources(agreement.legalPayer(), false);
            var allocations = store.allocations(agreement.legalPayer(), false);
            var sourceById = new HashMap<UUID, PaymentStore.Source>(); payerSources.forEach(source -> sourceById.put(source.id(), source));
            boolean allBacked = true; long backedFunding = 0;
            for (var allocation : allocations) {
                if (!allocation.rootId().equals(selected.id())) { continue; }
                backedFunding = Math.addExact(backedFunding, allocation.confirmedMinor());
                var source = sourceById.get(allocation.sourceId());
                if (source == null || source.frozen() || source.reversedMinor() > 0 || source.backingMinor() < source.creditMinor() || source.reservedCreditMinor() > source.creditMinor()
                        || allocation.confirmedMinor() != allocation.originalMinor() || allocation.refundedMinor() > 0 || allocation.reservedRefundMinor() > 0) { allBacked = false; }
                if (source != null) {
                    long exposure = source.creditMinor();
                    for (var exposed : allocations) {
                        if (exposed.sourceId().equals(source.id())) {
                            long remaining = Math.max(0, Math.subtractExact(Math.subtractExact(exposed.confirmedMinor(), exposed.refundedMinor()), exposed.paidMinor()));
                            exposure = Math.addExact(exposure, remaining);
                        }
                    }
                    if (exposure > source.backingMinor()) { allBacked = false; }
                }
            }
            var operations = store.loyaltyOperations(agreement.legalPayer(), selectedRoots(agreements, agreement.legalPayer()));
            boolean uncertain = operations.stream().anyMatch(operation -> operation.pending() || operation.uncertain());
            var selectedOperations = operations.stream().filter(operation -> operation.rootId().equals(selected.id())).toList();
            var releases = selectedOperations.stream().filter(operation -> operation.kind().equals("RELEASE") && operation.state().equals("SUCCESS") && operation.applied()).toList();
            boolean payout = selectedOperations.stream().filter(operation -> operation.kind().equals("PAYOUT") && operation.state().equals("SUCCESS") && operation.applied()).anyMatch(operation ->
                    store.jdbc.queryForObject("select count(*) from payments_payout_origin p join payments_operation r on r.id=p.release_id where p.payout_id=? and p.release_id<>p.payout_id and r.root_id=? and r.kind='RELEASE' and r.state='SUCCESS' and r.applied", Integer.class, operation.id(), selected.id()) == 1);
            boolean qualified = selected.lifecycle().equals("ACTIVE") && selected.fundedMinor() == agreement.totalMinor() && backedFunding == agreement.totalMinor()
                    && selected.refundedMinor() == 0 && selected.releasedMinor() == agreement.totalMinor() && selected.paidMinor() == agreement.totalMinor()
                    && selected.deficitMinor() == 0 && !selected.paused() && !selected.disputed() && allBacked && !uncertain && !releases.isEmpty() && payout;
            result.put(agreement.jobId(), new JobLoyaltyMoneyFacts(agreement, qualified, qualified ? "FULLY_FUNDED_RELEASED_BANK_PAID" : "FINANCIAL_QUALIFICATION_PENDING",
                    selected.fundedMinor(), selected.releasedMinor(), selected.paidMinor(), selected.refundedMinor(), selected.deficitMinor(), selected.version()));
        }
        return Map.copyOf(result);
    }

    private void lock(String table, Set<UUID> ids) {
        if (ids.isEmpty()) { return; }
        String values = ids.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",", "{", "}"));
        store.jdbc.queryForList("select id from " + table + " where id=any(?::uuid[]) order by id::text for update", values);
    }
    private static List<AccountId> payers(List<FinancialAgreement> agreements) { return agreements.stream().map(FinancialAgreement::legalPayer).distinct().sorted(Comparator.comparing(payer -> payer.value().toString())).toList(); }
    private static Set<UUID> selectedRoots(List<FinancialAgreement> agreements, AccountId payer) { return agreements.stream().filter(agreement -> agreement.legalPayer().equals(payer)).map(PaymentStore::rootId).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private static List<FinancialAgreement> ordered(List<FinancialAgreement> agreements) { return agreements.stream().sorted(Comparator.comparing(agreement -> agreement.jobId().toString())).toList(); }
    private static void validate(List<FinancialAgreement> agreements) {
        if (agreements.size() > 3 || new HashSet<>(agreements.stream().map(FinancialAgreement::jobId).toList()).size() != agreements.size()) { throw new PaymentFailure("Máximo tres trabajos propios distintos por lote de calificación"); }
    }
}
