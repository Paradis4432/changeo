package ar.changeo.payments.api;

import ar.changeo.moderation.api.ContentReference;
import java.util.Objects;

public record JobSettlementStatus(boolean successful, boolean bilateralContinuation, boolean unresolved,
        ContentReference exactAgreement) {
    public JobSettlementStatus { Objects.requireNonNull(exactAgreement); }
}
