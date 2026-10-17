package ar.changeo.jobs.api;

import ar.changeo.conversations.api.ConversationContext;
import ar.changeo.identity.api.AccountId;
import ar.changeo.moderation.api.ContentReference;
import java.time.Instant;
import java.util.*;

public record JobSnapshot(UUID id, ConversationContext conversation, State state, Optional<ContentReference> agreement,
        Optional<AccountId> legalPayer, Optional<ContentReference> finalDelivery, Optional<Instant> finalApprovedAt,
        Optional<Instant> reviewDueAt, int feedbackCount, long version) {
    public enum State { NEGOTIATING, AGREED_AWAITING_FUNDING, IN_PROGRESS, FINAL_DELIVERY_PENDING_REVIEW,
        COMPLETED, CANCELLATION_REQUESTED, CANCELLED, SUPPORT_HOLD;
        public String label() {
            return switch (this) {
                case NEGOTIATING -> "En negociación";
                case AGREED_AWAITING_FUNDING -> "Acuerdo bilateral: financiación pendiente";
                case IN_PROGRESS -> "Trabajo en curso";
                case FINAL_DELIVERY_PENDING_REVIEW -> "Entrega final pendiente de aceptación expresa";
                case COMPLETED -> "Trabajo completado por aceptación expresa";
                case CANCELLATION_REQUESTED -> "Cancelación solicitada";
                case CANCELLED -> "Cancelación no financiera registrada";
                case SUPPORT_HOLD -> "Ayuda o disputa registrada";
            };
        }
    }
    public boolean accepted() { return agreement.isPresent(); }
    public String reviewObservation(Instant now) {
        return state == State.FINAL_DELIVERY_PENDING_REVIEW && reviewDueAt.filter(due -> !now.isBefore(due)).isPresent()
                ? "REVISION_SIN_RESPUESTA" : state.name();
    }
}
