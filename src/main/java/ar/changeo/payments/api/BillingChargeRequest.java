package ar.changeo.payments.api;

import java.time.Instant;
import java.util.*;

public record BillingChargeRequest(UUID command, BillingBinding binding, UUID consentId, String policyVersion, String policyDigest,
        Instant periodStart, Instant periodEnd, long grossMinor, Currency currency, long permittedPromotionMinor, long feeMinor,
        Optional<UUID> predecessor) {
    public BillingChargeRequest {
        Objects.requireNonNull(command); Objects.requireNonNull(binding); Objects.requireNonNull(consentId); Objects.requireNonNull(policyVersion);
        Objects.requireNonNull(policyDigest); Objects.requireNonNull(periodStart); Objects.requireNonNull(periodEnd); Objects.requireNonNull(currency);
        Objects.requireNonNull(predecessor);
        if (!periodEnd.isAfter(periodStart) || grossMinor <= 100 || !currency.equals(Currency.getInstance("ARS"))
                || !Set.of(0L, 100L).contains(permittedPromotionMinor) || feeMinor < 0 || !policyDigest.matches("[0-9a-f]{64}")
                || Math.subtractExact(grossMinor, permittedPromotionMinor) <= 0) {
            throw new IllegalArgumentException("Período o importe de facturación incompatible");
        }
    }
}
