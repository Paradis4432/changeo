package ar.changeo.payments.api;

import java.util.*;

public record BillingTargets(UUID walletId, boolean walletPresent, Set<UUID> roots, Set<UUID> operations, Set<String> policyVersions,
        Optional<Long> walletVersion, Map<UUID, Long> operationVersions) {
    public BillingTargets { roots = Set.copyOf(roots); operations = Set.copyOf(operations); policyVersions = Set.copyOf(policyVersions); operationVersions = Map.copyOf(operationVersions); }
    public boolean sameTargets(BillingTargets current) { return walletId.equals(current.walletId) && walletPresent == current.walletPresent && roots.equals(current.roots) && operations.equals(current.operations) && policyVersions.equals(current.policyVersions); }
}
