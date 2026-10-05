package ar.changeo.identity.api;

import java.util.Objects;
import java.util.Optional;

public record PermissionRequest(Optional<ActorId> actorId, Optional<SubjectId> subjectId,
                                Capability capability, Optional<JobContext> jobContext) {
    public PermissionRequest {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(subjectId);
        Objects.requireNonNull(capability);
        Objects.requireNonNull(jobContext);
    }
    public static PermissionRequest self(AccountId id, Capability capability) {
        return new PermissionRequest(Optional.of(new ActorId(id.value())),
                Optional.of(new SubjectId(id.value())), capability, Optional.empty());
    }
}
