package ar.changeo.jobs;

import java.util.Objects;
import java.util.Set;
import org.springframework.validation.Errors;

public final class JobForms {
    private JobForms() {}

    public record Content(String kind, String body, String score, String label) {
        public Content { score = Objects.requireNonNullElse(score, ""); }
        void validate(Errors errors) {
            if (!Set.of("PROGRESS", "FEEDBACK", "FINAL_DELIVERY", "SUPPORT_NOTE", "REVIEW").contains(kind)) {
                errors.rejectValue("kind", "invalid", "Elegí una etapa de la lista");
            }
            if (body.isBlank() || body.length() > 12000 || body.indexOf('\0') >= 0) {
                errors.rejectValue("body", "invalid", "Escribí una descripción de hasta 12000 caracteres sin caracteres nulos");
            }
            if (!Set.of("GENERAL", "SENSITIVE", "ADULT").contains(label)) {
                errors.rejectValue("label", "invalid", "Elegí una clasificación de la lista");
            }
            if (kind.equals("REVIEW")) {
                try {
                    var parsed = parsedScore();
                    if (parsed == null || parsed < 1 || parsed > 5) { throw new NumberFormatException(); }
                } catch (NumberFormatException failure) { errors.rejectValue("score", "invalid", "La reseña requiere una valoración entera de 1 a 5"); }
            } else if (!score.isBlank()) { errors.rejectValue("score", "invalid", "La valoración solo corresponde a una reseña; dejala vacía para esta etapa"); }
        }
        Integer parsedScore() { return score.isBlank() ? null : Integer.valueOf(score.trim()); }
        Content bounded() { return new Content(limit(kind, 40), limit(body, 12000), limit(score, 40), limit(label, 40)); }
        private static String limit(String value, int maximum) { return value.substring(0, Math.min(value.length(), maximum)).replace("\0", ""); }
    }
}
