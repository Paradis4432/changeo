package ar.changeo.payments.gateway;

import java.util.*;

public record GatewayRequest(UUID operationId, UUID rootId, UUID stableKey, String kind, long amountMinor,
        String destination, Currency currency, String fingerprint) {
    public GatewayRequest {
        Objects.requireNonNull(operationId); Objects.requireNonNull(rootId); Objects.requireNonNull(stableKey);
        Objects.requireNonNull(kind); Objects.requireNonNull(destination); Objects.requireNonNull(currency); Objects.requireNonNull(fingerprint);
        if (!Set.of("FUND", "RELEASE", "PAYOUT", "REFUND", "RECOVERY", "SUBSCRIPTION").contains(kind) || amountMinor < 0
                || destination.isBlank() || destination.length() > 64 || !currency.equals(Currency.getInstance("ARS"))
                || !fingerprint.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("Solicitud sintética inválida"); }
    }
}
