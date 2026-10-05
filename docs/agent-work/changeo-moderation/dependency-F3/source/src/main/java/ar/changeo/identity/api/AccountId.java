package ar.changeo.identity.api;

import java.util.Objects;
import java.util.UUID;

public record AccountId(UUID value) {
    public AccountId { Objects.requireNonNull(value); }
}
