package ar.changeo.payments.api;

import java.time.Instant;
import java.util.*;

public interface PromotionBudgetAccess {
    void protectForUpdate(BillingBinding binding);
    AwardResult awardForUpdate(BillingBinding binding, PromotionClaim claim);
    AllocationStatus allocationStatusForUpdate(BillingBinding binding);
    void adjustQualificationForUpdate(BillingBinding binding, String evidenceDigest, boolean valid);
    PromotionHistory requireHistoryForUpdate(BillingBinding binding, Optional<HistoryCursor> cursor);
    enum AwardResult { AWARDED, EXISTING, CAP_USED, BACKING_PENDING }
    enum AllocationStatus { FUNDED, READY, CAP_USED, BACKING_PENDING }
    enum CorrectionKind { INVALIDATED, REINSTATED, DEBT, DEBT_CLEARED, HELD }
    record HistoryCursor(UUID walletId, long walletVersion, Instant afterTime, UUID afterId) {
        public HistoryCursor {
            Objects.requireNonNull(walletId); Objects.requireNonNull(afterTime); Objects.requireNonNull(afterId);
            if (walletVersion < 0) { throw new IllegalArgumentException("Versión de historial incompatible"); }
        }
    }
    record Award(long faceMinor, Instant awardedAt) {
        public Award { Objects.requireNonNull(awardedAt); if (faceMinor != 100) { throw new IllegalArgumentException("Premio incompatible"); } }
    }
    record Correction(UUID eventId, CorrectionKind kind, long amountMinor, Instant occurredAt) {
        public Correction {
            Objects.requireNonNull(eventId); Objects.requireNonNull(kind); Objects.requireNonNull(occurredAt);
            if (amountMinor != 0 && amountMinor != 100) { throw new IllegalArgumentException("Corrección incompatible"); }
        }
        public String message() {
            return switch (kind) {
                case INVALIDATED -> "Crédito sin usar retirado por evidencia inválida";
                case REINSTATED -> "Validez del mismo premio restablecida con respaldo disponible";
                case DEBT -> "Beneficio consumido corregido: deuda promocional no monetaria";
                case DEBT_CLEARED -> "Deuda del mismo premio resuelta sin crédito nuevo";
                case HELD -> "Reserva conservada mientras el efecto permanece incierto";
            };
        }
    }
    record PromotionHistory(long outstandingMinor, long reservedMinor, long redeemedMinor, long returnedMinor,
            long debtMinor, long availableMinor, boolean invalid, Optional<Award> award,
            List<Correction> corrections, Optional<HistoryCursor> next) {
        public PromotionHistory {
            Objects.requireNonNull(award); Objects.requireNonNull(next); corrections = List.copyOf(corrections);
            long face = Math.addExact(Math.addExact(outstandingMinor, redeemedMinor), returnedMinor);
            if (outstandingMinor < 0 || reservedMinor < 0 || redeemedMinor < 0 || returnedMinor < 0 || debtMinor < 0
                    || availableMinor < 0 || reservedMinor > outstandingMinor || availableMinor > outstandingMinor - reservedMinor
                    || (face != 0 && face != 100) || corrections.size() > 20) { throw new IllegalArgumentException("Historial promocional incompatible"); }
        }
    }
}
