package ar.changeo.payments.api;

import java.util.Objects;
import java.util.UUID;

public record FinalReleaseRequest(UUID commandId, long expectedJobVersion, long expectedMoneyVersion) {
    public FinalReleaseRequest {
        Objects.requireNonNull(commandId);
        if (expectedJobVersion < 0 || expectedMoneyVersion < 0) { throw new IllegalArgumentException("Versión financiera inválida"); }
    }
}
