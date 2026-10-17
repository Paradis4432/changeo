package ar.changeo.payments.api;

import java.util.Objects;
import java.util.UUID;

public record SettlementPreparation(UUID commandId, long expectedJobVersion, long expectedMoneyVersion) {
    public SettlementPreparation {
        Objects.requireNonNull(commandId);
        if (expectedJobVersion < 0 || expectedMoneyVersion < 0) { throw new IllegalArgumentException("Versión de liquidación inválida"); }
    }
}
