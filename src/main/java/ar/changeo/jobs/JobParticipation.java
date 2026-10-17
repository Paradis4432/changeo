package ar.changeo.jobs;

import ar.changeo.conversations.api.ConversationContext;
import ar.changeo.identity.api.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class JobParticipation {
    private final IdentityAccess identity;
    JobParticipation(IdentityAccess identity) { this.identity = identity; }

    void requireContract(ConversationContext context) {
        for (var side : List.of(context.customer(), context.provider())) {
            if (!identity.decide(new PermissionRequest(Optional.of(context.historicalActor(side)), Optional.of(side), Capability.ACCEPT_AGREEMENT, Optional.of(context.jobContext()))).allowed()) {
                throw new ar.changeo.identity.IdentityFailure("Autoridad actual para este trabajo y modalidad requerida");
            }
        }
    }

    void requireAudience(ConversationContext context, String label) {
        Capability audience = switch (label) {
            case "GENERAL" -> Capability.BROWSE_GENERAL;
            case "SENSITIVE" -> Capability.VIEW_SENSITIVE;
            case "ADULT" -> Capability.VIEW_ADULT;
            default -> throw new IllegalStateException("Clasificación desconocida");
        };
        for (var side : List.of(context.customer(), context.provider())) {
            var actor = context.historicalActor(side);
            if ((label.equals("ADULT") && !actor.value().equals(side.value()))
                    || !identity.decide(PermissionRequest.self(new AccountId(actor.value()), audience)).allowed()) {
                throw new ar.changeo.identity.IdentityFailure("Las partes deben poder consultar los términos actuales");
            }
        }
    }
}
