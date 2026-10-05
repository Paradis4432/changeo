package ar.changeo.moderation.api;

import java.util.Objects;
import java.util.UUID;

public record ContentReference(SurfaceKind ownerKind, UUID resourceId, UUID revisionId) {
    public ContentReference { Objects.requireNonNull(ownerKind); Objects.requireNonNull(resourceId); Objects.requireNonNull(revisionId); }
}
