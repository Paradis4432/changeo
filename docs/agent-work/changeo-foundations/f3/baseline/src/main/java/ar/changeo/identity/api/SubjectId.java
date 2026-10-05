package ar.changeo.identity.api;

import java.util.Objects;
import java.util.UUID;

public record SubjectId(UUID value) {
    public SubjectId { Objects.requireNonNull(value); }
}
