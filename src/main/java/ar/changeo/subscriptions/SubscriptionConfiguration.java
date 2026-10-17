package ar.changeo.subscriptions;

import ar.changeo.config.SandboxSafety;
import ar.changeo.identity.api.*;
import ar.changeo.subscriptions.api.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Configuration
class SubscriptionConfiguration {
    @Bean @ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
    SubscriptionPolicy syntheticSubscriptionPolicy(Environment environment) {
        SandboxSafety.validate(environment);
        if (!environment.getProperty("changeo.synthetic-payments", Boolean.class, false)) { throw new SubscriptionFailure("Suscripciones sintéticas requieren pagos sintéticos explícitos"); }
        for (String property : List.of("changeo.subscription-fee-capital-minor", "changeo.subscription-promotion-budget-minor")) {
            if (environment.getProperty(property, Long.class, 10000L) != 10000L) { throw new SubscriptionFailure("El respaldo inicial requiere exactamente 10000 centavos independientes por fondo"); }
        }
        return SubscriptionPolicy.example();
    }

    @Bean @ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "false", matchIfMissing = true)
    ProviderEntitlementAccess pendingProviderEntitlement() {
        return new ProviderEntitlementAccess() {
            @Override public EntitlementDiscovery discover(Set<SubjectId> providers) {
                if (providers.size() > 32) { throw new SubscriptionFailure("Demasiados proveedores en la solicitud"); }
                Map<SubjectId, EntitlementDiscovery.ProviderBillingDiscovery> entries = new HashMap<>();
                for (var provider : providers) { entries.put(provider, new EntitlementDiscovery.ProviderBillingDiscovery(provider, Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty(), Optional.empty(), Set.of(), Optional.empty())); }
                return new EntitlementDiscovery(false, "", "", 0, entries, Set.of());
            }
            @Override public void requireNewParticipationForUpdate(EntitlementDiscovery discovery, Set<AccountId> declaredIdentityAccounts) {
                if (discovery.enabled() || !TransactionSynchronizationManager.isActualTransactionActive()) { throw new SubscriptionFailure("Protección de suscripción incompatible"); }
            }
        };
    }

    @Bean @ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "false", matchIfMissing = true)
    LoyaltyPublicAccess pendingPublicLoyalty() {
        return provider -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new SubscriptionFailure("Los logros requieren una observación independiente"); }
            return Optional.empty();
        };
    }
}
