package ar.changeo.jobs.api;

import ar.changeo.identity.api.ActorId;
import ar.changeo.moderation.api.ContentReference;
import java.util.Objects;
import java.util.Optional;

public record JobFinancialFacts(FinancialAgreement agreement, JobSnapshot.State state,
        Optional<ContentReference> finalDelivery, Optional<ActorId> finalAcceptedBy) {
    public JobFinancialFacts {
        Objects.requireNonNull(agreement); Objects.requireNonNull(state); Objects.requireNonNull(finalDelivery);
        Objects.requireNonNull(finalAcceptedBy);
        if (finalAcceptedBy.isPresent() && (finalDelivery.isEmpty()
                || !finalAcceptedBy.orElseThrow().equals(agreement.customerActor()))) { throw new IllegalArgumentException("Aceptación final incompatible"); }
    }
}
