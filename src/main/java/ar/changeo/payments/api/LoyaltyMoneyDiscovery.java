package ar.changeo.payments.api;

import ar.changeo.identity.api.AccountId;
import ar.changeo.jobs.api.FinancialAgreement;
import java.util.*;

public record LoyaltyMoneyDiscovery(List<FinancialAgreement> agreements, boolean capitalPresent, Set<AccountId> payersPresent,
        Set<UUID> sources, Set<UUID> allocations, Set<UUID> roots, Set<UUID> operations, Map<UUID, Long> payerVersions,
        Map<UUID, Long> sourceVersions, Map<UUID, Long> rootVersions, Map<UUID, Long> operationGenerations) {
    public LoyaltyMoneyDiscovery {
        agreements = List.copyOf(agreements); payersPresent = Set.copyOf(payersPresent); sources = Set.copyOf(sources);
        allocations = Set.copyOf(allocations); roots = Set.copyOf(roots); operations = Set.copyOf(operations);
        payerVersions = Map.copyOf(payerVersions); sourceVersions = Map.copyOf(sourceVersions); rootVersions = Map.copyOf(rootVersions); operationGenerations = Map.copyOf(operationGenerations);
        if (agreements.size() > 3 || new HashSet<>(agreements.stream().map(FinancialAgreement::jobId).toList()).size() != agreements.size()) { throw new IllegalArgumentException("Lote financiero de lealtad incompatible"); }
    }
}
