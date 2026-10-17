package ar.changeo.payments.api;

import ar.changeo.identity.api.*;
import java.util.Objects;
import java.util.UUID;

public record BillingBinding(UUID enrollmentId, long generation, SubjectId provider, ActorId originalActor, AccountId originalPayer) {
    public BillingBinding {
        Objects.requireNonNull(enrollmentId); Objects.requireNonNull(provider); Objects.requireNonNull(originalActor); Objects.requireNonNull(originalPayer);
        if (generation < 1 || !originalActor.value().equals(originalPayer.value())) { throw new IllegalArgumentException("Inscripción de facturación incompatible"); }
    }
}
