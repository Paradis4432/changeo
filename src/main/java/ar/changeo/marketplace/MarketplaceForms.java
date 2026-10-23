package ar.changeo.marketplace;

import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.moderation.ModerationFailure;
import java.math.BigDecimal;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.validation.Errors;

public final class MarketplaceForms {
    private MarketplaceForms() {}

    public record Description(String title, String body, String tags, String label) {
        public Description {
            title=value(title); body=value(body); tags=value(tags); label=value(label);
        }
        void validate(Errors errors) {
            text(errors,"title",title,160,true,"Escribí un título o nombre de hasta 160 caracteres");
            text(errors,"body",body,12000,true,"Escribí una descripción de hasta 12000 caracteres");
            if (tags.length()>350 || (!tags.isBlank() && (tagList().stream().anyMatch(tag -> !validText(tag,40,true))
                    || tagList().stream().map(tag -> tag.trim().toLowerCase(Locale.ROOT)).distinct().count()>8))) {
                errors.rejectValue("tags","invalid","Usá hasta ocho etiquetas de hasta 40 caracteres, separadas por comas y sin entradas vacías");
            }
            if (!Set.of("GENERAL","SENSITIVE","ADULT").contains(label)) {
                errors.rejectValue("label","invalid","Elegí una clasificación de la lista");
            }
        }
        List<String> tagList() { return tags.isBlank() ? List.of() : Arrays.asList(tags.split(",",-1)); }
    }

    public record Coverage(String province, String area, List<String> modes, String availability, String amount, String deadline, String badgeOptIn) {
        public Coverage {
            province=value(province); area=value(area); modes=modes==null ? List.of() : List.copyOf(modes);
            availability=value(availability); amount=value(amount); deadline=value(deadline); badgeOptIn=value(badgeOptIn);
        }
        static Coverage initial(Optional<MarketplaceDetails> previous) {
            return previous.map(details -> new Coverage(details.province(),details.area(),details.modes().stream().map(Enum::name).toList(),
                    details.availability(),details.amount().map(BigDecimal::toPlainString).orElse(""),details.deadline().map(LocalDate::toString).orElse(""),Boolean.toString(details.badgeOptIn())))
                    .orElseGet(() -> new Coverage("Buenos Aires","",List.of(),"","","","false"));
        }
        MarketplaceDetails details(Description description, PresenceType type, Errors errors) {
            if (!MarketplaceDetails.PROVINCES.contains(province)) { errors.rejectValue("province","invalid","Elegí una provincia de la lista"); }
            text(errors,"area",area,80,true,"Escribí una zona amplia o localidad de hasta 80 caracteres, sin domicilio exacto");
            text(errors,"availability",availability,300,false,"Usá hasta 300 caracteres para la disponibilidad");
            Set<FulfillmentMode> selected=EnumSet.noneOf(FulfillmentMode.class);
            for (String mode:modes) {
                try { selected.add(FulfillmentMode.valueOf(mode)); }
                catch (IllegalArgumentException failure) { errors.rejectValue("modes","invalid","Elegí solo modalidades de la lista"); }
            }
            if (modes.isEmpty()) { errors.rejectValue("modes","required","Elegí al menos una modalidad"); }
            Optional<BigDecimal> parsedAmount=Optional.empty();
            if (!amount.isEmpty()) {
                try {
                    if (amount.length()>64) { throw new NumberFormatException(); }
                    BigDecimal parsed=new BigDecimal(amount);
                    if (parsed.signum()<0 || parsed.scale()>2 || parsed.precision()>14 || (long)parsed.precision()-parsed.scale()>12) { throw new NumberFormatException(); }
                    parsedAmount=Optional.of(parsed);
                } catch (NumberFormatException failure) { errors.rejectValue("amount","invalid","Ingresá un importe no negativo con hasta 12 dígitos enteros y dos decimales, por ejemplo 123.45, o dejalo en blanco"); }
            }
            Optional<LocalDate> parsedDeadline=Optional.empty();
            if (!deadline.isEmpty()) {
                try {
                    LocalDate parsed=LocalDate.parse(deadline);
                    if (parsed.isBefore(LocalDate.of(1,1,1)) || parsed.isAfter(LocalDate.of(5874897,12,31))) {
                        errors.rejectValue("deadline","invalid","La fecha está fuera del rango permitido; ingresá una fecha orientativa actual o dejala en blanco");
                    } else { parsedDeadline=Optional.of(parsed); }
                }
                catch (DateTimeParseException failure) { errors.rejectValue("deadline","invalid","Ingresá una fecha válida con formato AAAA-MM-DD o dejala en blanco"); }
            }
            if (type.profile() && (!amount.isEmpty() || !deadline.isEmpty())) { errors.reject("incompatible","Los perfiles no admiten precio ni fecha: volvé a la descripción o empezá un formulario nuevo desde Mis publicaciones"); }
            if (!Set.of("","false","true").contains(badgeOptIn) || (!type.profile() && badgeOptIn.equals("true"))) {
                errors.rejectValue("badgeOptIn","invalid","La insignia opcional solo corresponde a perfiles");
            }
            if (errors.hasErrors()) { return null; }
            try {
                var details=new MarketplaceDetails(description.title(),description.body(),description.tagList(),province,area,selected,availability,parsedAmount,parsedDeadline,badgeOptIn.equals("true"));
                details.reviewText(type);
                return details;
            } catch (ModerationFailure failure) {
                errors.reject("length","El contenido completo supera el límite permitido. Volvé a la descripción y acortá el texto o las etiquetas");
                return null;
            }
        }
    }

    private static String value(String value) { return Objects.requireNonNullElse(value,""); }
    private static void text(Errors errors, String field, String value, int maximum, boolean required, String message) {
        if (!validText(value,maximum,required)) { errors.rejectValue(field,"invalid",message); }
    }
    private static boolean validText(String value, int maximum, boolean required) {
        if (value.length()>maximum || (required && value.isBlank()) || value.indexOf('\0')>=0) { return false; }
        try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); return true; }
        catch (CharacterCodingException failure) { return false; }
    }
}
