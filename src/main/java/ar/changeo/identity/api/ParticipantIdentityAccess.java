package ar.changeo.identity.api;

import java.util.Map;
import java.util.Set;

public interface ParticipantIdentityAccess {
    Map<SubjectId, ActorId> controllers(Set<SubjectId> subjects);
    PermissionDecision requireForUpdate(PermissionRequest request, Set<AccountId> participants);
}
