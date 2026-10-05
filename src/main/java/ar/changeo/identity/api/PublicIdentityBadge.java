package ar.changeo.identity.api;

import java.time.Instant;
import java.util.Objects;

public record PublicIdentityBadge(String scope, Instant checkedAt, Instant validUntil, String provenance) {
    public PublicIdentityBadge {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(checkedAt);
        Objects.requireNonNull(validUntil);
        Objects.requireNonNull(provenance);
    }
}
