package ar.changeo.conversations;

import ar.changeo.agreements.api.AgreementTerms;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import org.springframework.stereotype.Component;

@Component("conversationWords")
public class ConversationWords {
    public String mode(FulfillmentMode mode) {
        if (mode == null) { return "A convenir"; }
        return switch (mode) { case LOCAL -> "Presencial"; case REMOTE -> "A distancia"; case SHIPPED -> "Con envío"; };
    }
    public String content(String kind) {
        return switch (kind) {
            case "PROGRESS" -> "Avance";
            case "FEEDBACK" -> "Devolución incluida";
            case "FINAL_DELIVERY" -> "Entrega final";
            case "SUPPORT_NOTE" -> "Explicación para ayuda";
            case "REVIEW" -> "Reseña del trabajo completado";
            default -> "Contenido aprobado";
        };
    }
    public String notice(String state) {
        return switch (state) {
            case "PENDING" -> "Aviso pendiente";
            case "CLAIMED" -> "Comprobando el acceso antes de entregar";
            case "RETRY_WAIT" -> "Aviso sin entregar; reintento automático pendiente";
            case "DELIVERED" -> "Aviso entregado en este sitio";
            case "FAILED" -> "Aviso sin entregar; los reintentos automáticos se agotaron. Consultá el contenido y la ayuda disponibles";
            default -> "Estado pendiente de confirmar";
        };
    }
    public String arrangement(AgreementTerms.Arrangement arrangement) {
        if (arrangement == null) { return "Coordinación pendiente"; }
        return switch (arrangement) { case APPOINTMENT -> "Encuentro presencial"; case REMOTE -> "Entrega remota"; case SHIPPING -> "Envío"; case PICKUP -> "Retiro"; };
    }
}
