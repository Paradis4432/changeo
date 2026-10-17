package ar.changeo.jobs.api;

import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.payments.api.LoyaltyMoneyDiscovery;
import java.time.Instant;
import java.util.*;

public record LoyaltyEvidenceBatch(SubjectId provider, Map<UUID, Evidence> jobs, Set<AccountId> requiredAccountIds,
        Map<UUID, Long> cohortVersions, Set<ContentReference> approvalReferences, LoyaltyMoneyDiscovery financialDiscovery) {
    public LoyaltyEvidenceBatch {
        jobs = Map.copyOf(jobs); requiredAccountIds = Set.copyOf(requiredAccountIds); cohortVersions = Map.copyOf(cohortVersions); approvalReferences = Set.copyOf(approvalReferences);
        if (jobs.size() > 3 || requiredAccountIds.size() > 64 || approvalReferences.size() > 32) { throw new IllegalArgumentException("Evidencia propia excede el límite completo"); }
    }
    public record Evidence(UUID jobId, UUID threadId, long threadVersion, long jobVersion, JobSnapshot.State state,
            FinancialAgreement agreement, Optional<ContentReference> finalDelivery, Optional<UUID> completedEvent,
            Optional<Instant> completedAt, Set<AccountId> originalProviderAccounts, Set<AccountId> originalCustomerAccounts) {
        public Evidence { originalProviderAccounts = Set.copyOf(originalProviderAccounts); originalCustomerAccounts = Set.copyOf(originalCustomerAccounts); }
    }
}
