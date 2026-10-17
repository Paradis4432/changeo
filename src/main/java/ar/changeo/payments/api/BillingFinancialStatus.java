package ar.changeo.payments.api;

import java.time.Instant;
import java.util.*;

public record BillingFinancialStatus(BillingBinding binding, List<Period> periods, Optional<PreparedBillingCharge> pending,
        boolean frozen, long availablePromotionMinor, long promotionalDebtMinor) {
    public BillingFinancialStatus { periods = List.copyOf(periods); }
    public record Period(UUID operationId, Instant start, Instant end, String policyVersion, String state,
            long grossMinor, long promotionMinor, long collectedMinor, long returnedMinor, long feeSpentMinor,
            boolean confirmedUnreversed, long version, UUID consentId, String policyDigest, UUID stableKey,
            long feeMinor, Optional<UUID> predecessor) {
        public long externalMinor() { return Math.subtractExact(grossMinor, promotionMinor); }
    }
}
