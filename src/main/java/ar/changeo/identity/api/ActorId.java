package ar.changeo.identity.api;

import java.util.Objects;
import java.util.UUID;

public record ActorId(UUID value) {
    public ActorId { Objects.requireNonNull(value); }
}
