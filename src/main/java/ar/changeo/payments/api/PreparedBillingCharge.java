package ar.changeo.payments.api;

import java.util.UUID;
import java.util.Optional;
import java.time.Instant;

public record PreparedBillingCharge(UUID rootId, UUID operationId, UUID stableKey, BillingChargeRequest request, long reservedPromotionMinor,
        long claimGeneration, Optional<UUID> claimToken, Optional<Instant> leaseUntil) {}
