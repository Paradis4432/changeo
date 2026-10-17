package ar.changeo.subscriptions.api;

import ar.changeo.identity.api.*;
import java.util.Set;
import org.springframework.transaction.annotation.*;

public interface ProviderEntitlementAccess {
    EntitlementDiscovery discover(Set<SubjectId> providers);
    @Transactional(propagation = Propagation.MANDATORY)
    void requireNewParticipationForUpdate(EntitlementDiscovery discovery, Set<AccountId> declaredIdentityAccounts);
}
