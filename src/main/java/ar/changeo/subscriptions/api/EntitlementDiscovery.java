package ar.changeo.subscriptions.api;

import ar.changeo.identity.api.*;
import ar.changeo.payments.api.*;
import java.util.*;

public record EntitlementDiscovery(boolean enabled, String currentPolicyVersion, String currentPolicyDigest,
        long policyPointerVersion, Map<SubjectId, ProviderBillingDiscovery> providers, Set<AccountId> requiredAccountIds) {
    public EntitlementDiscovery {
        providers = Map.copyOf(providers); requiredAccountIds = Set.copyOf(requiredAccountIds);
        if (providers.size() > 32 || requiredAccountIds.size() > 64) { throw new IllegalArgumentException("Descubrimiento de suscripción demasiado grande"); }
    }
    public record ProviderBillingDiscovery(SubjectId provider, Optional<ActorId> currentController, Optional<BillingBinding> binding,
            Optional<UUID> consentId, long providerVersion, Optional<Long> enrollmentVersion, Optional<Long> consentVersion,
            Set<String> policyVersions, Optional<BillingTargets> financialTargets) {
        public ProviderBillingDiscovery { policyVersions = Set.copyOf(policyVersions); }
    }
}
