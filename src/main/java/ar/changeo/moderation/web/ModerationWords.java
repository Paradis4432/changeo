package ar.changeo.moderation.web;

import org.springframework.stereotype.Component;

@Component("moderationWords")
public class ModerationWords {
    public String state(String value) {
        return switch(value) {
            case "DRAFT_PENDING_REVIEW" -> "Borrador guardado; falta enviar a revisión";
            case "PENDING" -> "Revisión pendiente";
            case "MANUAL_REVIEW" -> "Esperando revisión humana";
            case "APPROVED" -> "Revisión aprobada; publicación sujeta a permisos actuales";
            case "AWAITING_ELIGIBILITY" -> "Aprobado; falta elegibilidad actual. Reintentá cuando se resuelva";
            case "FAILED" -> "Reintentos agotados; podés solicitar otro intento o apelar";
            case "REJECTED" -> "Rechazado; podés apelar";
            case "RECALLED" -> "Retirado; ya no se entrega a la audiencia";
            case "OPEN" -> "Caso abierto";
            case "OWNED" -> "Caso asignado";
            case "RESOLVED" -> "Caso resuelto";
            case "WARNING" -> "Advertencia";
            case "STRIKE" -> "Señal";
            default -> "Estado pendiente";
        };
    }
    public String reason(String value) {
        return switch(value) {
            case "CLEAR" -> "Ejemplo sintético claro";
            case "MISLABEL" -> "Etiqueta incorrecta";
            case "SAFETY" -> "Seguridad";
            case "FALSE_POSITIVE" -> "Revisión incorrecta";
            case "OTHER" -> "Otro motivo";
            case "DOUBT" -> "Contenido incierto; requiere revisión humana";
            case "PROHIBITED" -> "Ejemplo sintético de contenido prohibido";
            case "UNINSPECTABLE" -> "Adjunto en cuarentena; no se pueden revisar sus bytes";
            case "INVALID" -> "Respuesta de revisión inválida; retenida sin publicar";
            case "OUTAGE" -> "Revisión no disponible; reintento técnico pendiente";
            case "ELIGIBILITY" -> "La elegibilidad actual impide publicar";
            case "SUBMIT_REQUIRED" -> "Enviá el borrador a revisión";
            case "EXHAUSTED" -> "Se alcanzó el límite de tres intentos técnicos";
            case "MANUAL" -> "Decisión humana registrada";
            case "APPEAL" -> "Apelación revisada";
            case "LABEL_CORRECTION" -> "Etiqueta corregida por revisión humana";
            default -> "Estado registrado; consultá ayuda si necesitás revisión";
        };
    }
}
