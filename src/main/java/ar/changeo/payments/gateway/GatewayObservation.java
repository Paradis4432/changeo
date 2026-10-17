package ar.changeo.payments.gateway;

import java.time.Instant;
import java.util.*;

public record GatewayObservation(UUID eventId, GatewayRequest request, Outcome outcome, long observedMinor,
        Instant observedAt, String signature) {
    public enum Outcome { SUCCESS, FAILED_NO_EFFECT, ABSENT, PARTIAL, REVERSED }
    public GatewayObservation {
        Objects.requireNonNull(eventId); Objects.requireNonNull(request); Objects.requireNonNull(outcome);
        Objects.requireNonNull(observedAt); Objects.requireNonNull(signature);
        if (observedMinor < 0 || observedMinor > request.amountMinor() || signature.length() > 128
                || (outcome == Outcome.SUCCESS && observedMinor != request.amountMinor())
                || (Set.of(Outcome.ABSENT, Outcome.FAILED_NO_EFFECT).contains(outcome) && observedMinor != 0)) {
            throw new IllegalArgumentException("Observación financiera inválida");
        }
    }
}
