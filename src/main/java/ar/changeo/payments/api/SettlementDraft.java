package ar.changeo.payments.api;

import ar.changeo.identity.api.SubjectId;
import java.util.*;

public record SettlementDraft(UUID commandId, UUID jobId, UUID agreementRevisionId, SubjectId side,
        long expectedJobVersion, long expectedMoneyVersion, long refundMinor, RefundDestination election,
        boolean continueJob, long remainingMinor) {
    public SettlementDraft {
        Objects.requireNonNull(commandId); Objects.requireNonNull(jobId); Objects.requireNonNull(agreementRevisionId);
        Objects.requireNonNull(side); Objects.requireNonNull(election);
        if (refundMinor <= 0 || remainingMinor < 0 || (continueJob && remainingMinor == 0)) { throw new IllegalArgumentException("Propuesta de reembolso inválida"); }
        new SettlementPreparation(commandId, expectedJobVersion, expectedMoneyVersion);
    }
}
