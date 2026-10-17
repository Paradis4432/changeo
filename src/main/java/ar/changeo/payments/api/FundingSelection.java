package ar.changeo.payments.api;

import java.util.Objects;
import java.util.UUID;

public record FundingSelection(UUID commandId, long expectedJobVersion, long creditMinor) {
    public FundingSelection {
        Objects.requireNonNull(commandId);
        if (expectedJobVersion < 0 || creditMinor < 0) { throw new IllegalArgumentException("Selección financiera inválida"); }
    }
}
