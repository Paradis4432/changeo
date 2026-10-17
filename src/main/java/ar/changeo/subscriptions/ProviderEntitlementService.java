package ar.changeo.subscriptions;

import ar.changeo.identity.api.*;
import ar.changeo.payments.api.*;
import ar.changeo.subscriptions.api.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class ProviderEntitlementService implements ProviderEntitlementAccess {
    private final SubscriptionStore store;
    private final ParticipantIdentityAccess identity;
    private final SubscriptionBillingAccess billing;

    ProviderEntitlementService(SubscriptionStore store, ParticipantIdentityAccess identity, SubscriptionBillingAccess billing) {
        this.store = store; this.identity = identity; this.billing = billing;
    }

    @Override public EntitlementDiscovery discover(Set<SubjectId> providers) {
        if (providers.isEmpty() || providers.size() > 32) { throw new SubscriptionFailure("Conjunto de proveedores incompatible"); }
        var pointer = store.pointer(false); var currentPolicy = store.policy(pointer.version(), false);
        var controllers = identity.controllers(providers);
        Map<SubjectId, EntitlementDiscovery.ProviderBillingDiscovery> entries = new HashMap<>();
        Set<AccountId> accounts = new HashSet<>();
        for (var subject : providers) {
            accounts.add(new AccountId(subject.value()));
            Optional<ActorId> controller = Optional.ofNullable(controllers.get(subject)); controller.ifPresent(actor -> accounts.add(new AccountId(actor.value())));
            var provider = store.provider(subject, false);
            var enrollment = provider.flatMap(SubscriptionStore.Provider::enrollment).map(id -> store.enrollment(id, false));
            var binding = enrollment.map(SubscriptionStore.Enrollment::binding);
            binding.ifPresent(value -> { accounts.add(new AccountId(value.originalActor().value())); accounts.add(value.originalPayer()); });
            Optional<BillingTargets> financial = binding.map(billing::discoverTargets);
            Set<String> policies = new HashSet<>(Set.of(pointer.version()));
            enrollment.ifPresent(value -> { policies.add(value.policyVersion()); value.benefitPolicyVersion().ifPresent(policies::add); value.consent().ifPresent(id -> policies.add(store.consent(id).policyVersion())); });
            financial.ifPresent(value -> policies.addAll(value.policyVersions()));
            var consent = enrollment.flatMap(SubscriptionStore.Enrollment::consent).map(store::consent);
            entries.put(subject, new EntitlementDiscovery.ProviderBillingDiscovery(subject, controller, binding, consent.map(SubscriptionStore.Consent::id),
                    provider.map(SubscriptionStore.Provider::version).orElse(0L), enrollment.map(SubscriptionStore.Enrollment::version), consent.map(SubscriptionStore.Consent::version), Set.copyOf(policies), financial));
        }
        return new EntitlementDiscovery(true, currentPolicy.version(), currentPolicy.digest(), pointer.revision(), entries, Set.copyOf(accounts));
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void requireNewParticipationForUpdate(EntitlementDiscovery discovery, Set<AccountId> declaredIdentityAccounts) {
        for (var provider : discovery.providers().values()) {
            var actor = provider.binding().map(BillingBinding::originalActor).or(() -> provider.currentController())
                    .orElseThrow(() -> new SubscriptionFailure("Controlador actual de proveedor requerido"));
            if (!provider.currentController().filter(actor::equals).isPresent()) { throw new SubscriptionFailure("La nueva persona controladora necesita su propia inscripción; los derechos originales se conservan"); }
            identity.requireForUpdate(new PermissionRequest(Optional.of(actor), Optional.of(provider.provider()), Capability.NEW_PROVIDER_PARTICIPATION, Optional.empty()), declaredIdentityAccounts);
        }
        var protectedEnrollments = protectCommercial(discovery, declaredIdentityAccounts);
        if (protectedEnrollments.size() != discovery.providers().size()) { throw new SubscriptionFailure("Iniciá tu suscripción sintética en /subscriptions antes de participar como proveedor"); }
        var statuses = billing.requireStatusesForUpdate(protectedEnrollments.values().stream().map(SubscriptionStore.Enrollment::binding).toList());
        requireFinancialTargets(discovery);
        for (var enrollment : protectedEnrollments.values()) {
            var result = effective(enrollment, statuses.get(enrollment.binding()));
            if (!result.allowed()) { throw new SubscriptionFailure("Tu período de proveedor venció o requiere conciliación. Revisá /subscriptions; tus trabajos seleccionados y derechos se conservan"); }
        }
    }

    Map<SubjectId, SubscriptionStore.Enrollment> protectCommercial(EntitlementDiscovery discovery, Set<AccountId> declaredIdentityAccounts) {
        protectPolicies(discovery, declaredIdentityAccounts);
        return protectEnrollments(discovery, Set.of());
    }

    void protectPolicies(EntitlementDiscovery discovery, Set<AccountId> declaredIdentityAccounts) {
        protectPolicies(discovery, declaredIdentityAccounts, Set.of());
    }

    void protectPolicies(EntitlementDiscovery discovery, Set<AccountId> declaredIdentityAccounts, Set<String> retainedPolicies) {
        if (!discovery.enabled() || declaredIdentityAccounts.size() > 64 || !declaredIdentityAccounts.containsAll(discovery.requiredAccountIds())
                || !sameDeclaredScope(discovery, discover(discovery.providers().keySet()))) { throw new SubscriptionFailure("La inscripción o sus participantes cambiaron; volvé a intentar desde el estado actual"); }
        var pointer = store.pointer(true);
        if (!pointer.version().equals(discovery.currentPolicyVersion()) || pointer.revision() != discovery.policyPointerVersion()) { throw new SubscriptionFailure("Los términos cambiaron; revisalos nuevamente"); }
        Set<String> policies = new HashSet<>(Set.of(pointer.version()));
        if (retainedPolicies.size() > 204) { throw new SubscriptionFailure("Demasiadas versiones históricas para una protección completa"); }
        policies.addAll(retainedPolicies);
        discovery.providers().values().forEach(value -> policies.addAll(value.policyVersions()));
        for (String version : policies.stream().sorted().toList()) { store.policy(version, true); }
    }

    Map<SubjectId, SubscriptionStore.Enrollment> protectEnrollments(EntitlementDiscovery discovery, Set<UUID> retainedEnrollments) {
        Map<SubjectId, SubscriptionStore.Provider> providers = new HashMap<>();
        for (var subject : discovery.providers().keySet().stream().sorted(Comparator.comparing(value -> value.value().toString())).toList()) {
            store.provider(subject, true).ifPresent(provider -> providers.put(subject, provider));
        }
        Set<UUID> enrollmentIds = new HashSet<>(retainedEnrollments);
        providers.values().forEach(value -> value.enrollment().ifPresent(enrollmentIds::add));
        if (enrollmentIds.size() > 64) { throw new SubscriptionFailure("Demasiadas inscripciones para una protección completa"); }
        Map<UUID, SubscriptionStore.Enrollment> locked = new HashMap<>();
        for (UUID id : enrollmentIds.stream().sorted(Comparator.comparing(UUID::toString)).toList()) { locked.put(id, store.enrollment(id, true)); }
        Map<SubjectId, SubscriptionStore.Enrollment> enrollments = new HashMap<>();
        for (var provider : providers.values()) {
            if (provider.enrollment().isEmpty()) { continue; }
            var enrollment = locked.get(provider.enrollment().orElseThrow());
            if (!discovery.providers().get(provider.subject()).binding().filter(enrollment.binding()::equals).isPresent()
                    || !enrollment.consent().equals(discovery.providers().get(provider.subject()).consentId())) { throw new SubscriptionFailure("La inscripción original cambió"); }
            enrollments.put(provider.subject(), enrollment);
        }
        return Map.copyOf(enrollments);
    }

    void requireFinancialTargets(EntitlementDiscovery discovery) {
        for (var provider : discovery.providers().values()) {
            if (provider.binding().isPresent() && !provider.financialTargets().orElseThrow().sameTargets(billing.discoverTargets(provider.binding().orElseThrow()))) {
                throw new SubscriptionFailure("Las operaciones de la inscripción cambiaron; volvé a observarlas");
            }
        }
    }

    EffectiveEntitlement effective(SubscriptionStore.Enrollment enrollment, BillingFinancialStatus financial) {
        Instant now = store.clock.instant();
        var benefit = enrollment.benefitEnd().filter(end -> end.isAfter(now));
        Optional<Instant> paidEnd = financial.periods().stream().filter(BillingFinancialStatus.Period::confirmedUnreversed)
                .filter(period -> !now.isBefore(period.start()) && now.isBefore(period.end())).map(BillingFinancialStatus.Period::end).max(Comparator.naturalOrder());
        if (paidEnd.isPresent()) { return new EffectiveEntitlement(true, "PAID_THROUGH", paidEnd); }
        if (benefit.isPresent() && enrollment.benefitKind().isPresent()) { return new EffectiveEntitlement(true, enrollment.benefitKind().orElseThrow(), benefit); }
        Optional<Instant> grace = financial.periods().stream().filter(BillingFinancialStatus.Period::confirmedUnreversed)
                .filter(period -> !now.isBefore(period.end()))
                .map(period -> store.policy(period.policyVersion(), false).graceEnd(period.end())).filter(end -> end.isAfter(now)).max(Comparator.naturalOrder());
        return new EffectiveEntitlement(grace.isPresent(), grace.isPresent() ? "PRIOR_PAID_GRACE" : "EXPIRED", grace);
    }

    private static boolean sameDeclaredScope(EntitlementDiscovery first, EntitlementDiscovery current) {
        if (first.enabled() != current.enabled() || !first.currentPolicyVersion().equals(current.currentPolicyVersion())
                || !first.currentPolicyDigest().equals(current.currentPolicyDigest()) || first.policyPointerVersion() != current.policyPointerVersion()
                || !first.requiredAccountIds().equals(current.requiredAccountIds()) || !first.providers().keySet().equals(current.providers().keySet())) { return false; }
        for (var expected : first.providers().values()) {
            var actual = current.providers().get(expected.provider());
            if (!expected.currentController().equals(actual.currentController()) || !expected.binding().equals(actual.binding())
                    || !expected.consentId().equals(actual.consentId()) || expected.providerVersion() != actual.providerVersion()
                    || !expected.enrollmentVersion().equals(actual.enrollmentVersion()) || !expected.consentVersion().equals(actual.consentVersion())
                    || !expected.policyVersions().equals(actual.policyVersions()) || expected.financialTargets().isPresent() != actual.financialTargets().isPresent()
                    || expected.financialTargets().filter(targets -> !targets.sameTargets(actual.financialTargets().orElseThrow())).isPresent()) { return false; }
        }
        return true;
    }

    record EffectiveEntitlement(boolean allowed, String reason, Optional<Instant> validUntil) {}
}
