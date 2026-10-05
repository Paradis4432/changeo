package ar.changeo.identity.api;

import java.util.Map;
import java.util.Set;

public interface PublicIdentityBadgeAccess {
    Map<AccountId, PublicIdentityBadge> findCurrent(Set<AccountId> profileSubjects);
}
