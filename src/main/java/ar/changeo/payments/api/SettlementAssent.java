package ar.changeo.payments.api;

import java.util.Objects;
import java.util.UUID;

public record SettlementAssent(UUID settlementId, SettlementDraft expectedProposal) {
    public SettlementAssent { Objects.requireNonNull(settlementId); Objects.requireNonNull(expectedProposal); }
}
