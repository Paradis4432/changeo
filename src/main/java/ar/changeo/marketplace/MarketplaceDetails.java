package ar.changeo.marketplace;

import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.moderation.ModerationFailure;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.time.LocalDate;
import java.util.*;

public record MarketplaceDetails(String title, String body, List<String> tags, String province, String area,
                                 Set<FulfillmentMode> modes, String availability, Optional<BigDecimal> amount,
                                 Optional<LocalDate> deadline, boolean badgeOptIn) {
    public static final List<String> PROVINCES = List.of("Buenos Aires", "Ciudad Autónoma de Buenos Aires", "Catamarca", "Chaco", "Chubut", "Córdoba", "Corrientes", "Entre Ríos", "Formosa", "Jujuy", "La Pampa", "La Rioja", "Mendoza", "Misiones", "Neuquén", "Río Negro", "Salta", "San Juan", "San Luis", "Santa Cruz", "Santa Fe", "Santiago del Estero", "Tierra del Fuego", "Tucumán");

    public MarketplaceDetails {
        title = text(title, 160, true);
        body = text(body, 12000, true);
        province = text(province, 80, true);
        area = text(area, 80, true);
        availability = text(availability, 300, false);
        if (!PROVINCES.contains(province)) { throw invalid(); }
        Objects.requireNonNull(tags);
        tags = tags.stream().map(tag -> text(tag, 40, true).toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        modes = Set.copyOf(modes);
        amount = Objects.requireNonNull(amount).map(value -> {
            if (value.signum() < 0 || value.scale() > 2 || value.precision() > 14) { throw invalid(); }
            return value.setScale(2);
        });
        deadline = Objects.requireNonNull(deadline);
        if (tags.size() > 8 || modes.isEmpty()) { throw invalid(); }
    }

    public String reviewText(PresenceType type) {
        StringBuilder value = new StringBuilder("CHANGEO_WHOLE_REVISION_V1\n");
        field(value, "tipo", type.name());
        field(value, "nombre", title);
        field(value, "descripción", body);
        field(value, "etiquetas", String.join(",", tags));
        field(value, "provincia", province);
        field(value, "zona_amplia", area);
        field(value, "modalidades", modes.stream().map(Enum::name).sorted().reduce((a,b) -> a + "," + b).orElseThrow());
        field(value, "disponibilidad", availability);
        field(value, "expectativa_ARS", amount.map(BigDecimal::toPlainString).orElse("A convenir"));
        field(value, "fecha_orientativa", deadline.map(LocalDate::toString).orElse("A convenir"));
        field(value, "insignia_opcional", Boolean.toString(badgeOptIn));
        String result = value.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > 32768) { throw invalid(); }
        return result;
    }

    public String price() { return amount.map(value -> "ARS " + value.toPlainString()).orElse("A convenir"); }
    public String summary() { return body.length() <= 220 ? body : body.substring(0, 220) + "…"; }
    private static void field(StringBuilder value, String name, String text) { value.append(name).append('[').append(text.length()).append("]:").append(text).append('\n'); }
    private static String text(String value, int maximum, boolean required) {
        if (value == null || value.length() > maximum || (required && value.isBlank()) || value.indexOf('\0') >= 0) { throw invalid(); }
        try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); }
        catch (CharacterCodingException failure) { throw invalid(); }
        return value.trim();
    }
    private static ModerationFailure invalid() { return new ModerationFailure("Revisá los campos: título, descripción, provincia y zona amplia; hasta ocho etiquetas y una modalidad como mínimo"); }
}
