package ar.changeo.subscriptions;

import ar.changeo.moderation.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.Objects;

public record SubscriptionPolicy(String version, long grossMinor, int foundingDays, int trialDays, int graceDays,
        long creditMinor, long feeMinor, ZoneId zone) {
    public SubscriptionPolicy {
        Objects.requireNonNull(version); Objects.requireNonNull(zone);
        if (version.isBlank() || version.length() > 64 || grossMinor <= 100 || foundingDays < 0 || trialDays < 0
                || graceDays < 0 || creditMinor != 100 || creditMinor >= grossMinor || feeMinor < 0
                || !zone.equals(ZoneId.of("America/Argentina/Buenos_Aires"))) {
            throw new SubscriptionFailure("Política sintética incompatible");
        }
    }

    public static SubscriptionPolicy example() {
        return new SubscriptionPolicy("SL1B-EXAMPLE-2", 10000, 30, 7, 2, 100, 10, ZoneId.of("America/Argentina/Buenos_Aires"));
    }

    public Instant nextEndpoint(Instant start, int anchorDay) {
        if (anchorDay < 1 || anchorDay > 31) { throw new SubscriptionFailure("Día de renovación incompatible"); }
        var local = start.atZone(zone);
        var month = YearMonth.from(local).plusMonths(1);
        return ZonedDateTime.of(month.atDay(Math.min(anchorDay, month.lengthOfMonth())), local.toLocalTime(), zone).toInstant();
    }

    public Instant foundingEnd(Instant start) { return start.plus(Duration.ofDays(foundingDays)); }
    public Instant trialEnd(Instant start) { return start.plus(Duration.ofDays(trialDays)); }
    public Instant graceEnd(Instant paidEnd) { return paidEnd.plus(Duration.ofDays(graceDays)); }

    public String disclosure() {
        return "Ejemplo privado sintético " + version + ": ARS " + ars(grossMinor) + " por mes calendario argentino; "
                + foundingDays + " días de inicio gratuito sin autorización de cobro; prueba opcional de " + trialDays
                + " días con consentimiento recurrente explícito; gracia de " + graceDays
                + " días solo después de un período pagado no revertido. Podés cancelar futuros cobros y conservar el tiempo válido; "
                + "no se prometen reembolsos automáticos. Crédito inicial no transferible de " + creditMinor
                + " centavos por dos clientes pagadores distintos con trabajos válidos; un premio por proveedor y pagador en la campaña. "
                + "Con ese crédito válido, el importe externo del período es ARS " + ars(Math.subtractExact(grossMinor, creditMinor)) + "; los períodos siguientes sin crédito vuelven al importe mensual completo. "
                + "El crédito se reserva hasta un resultado cierto y se consume con el cobro completo; no se devuelve tras una reversión posterior. "
                + "Cambio de términos requiere nuevo consentimiento. Cobro parcial o incierto no activa un período pagado; "
                + "una devolución terminal exige compra y consentimiento nuevos. No hay cobros de meses omitidos. "
                + "Changeo cubre la comisión sintética de ARS " + ars(feeMinor) + " con capital independiente. "
                + "Datos y dinero sintéticos; no es una factura fiscal ni una política comercial de producción.";
    }

    public String digest() { return ContentDigest.bytes(disclosure().getBytes(StandardCharsets.UTF_8)); }
    public String grossArs() { return ars(grossMinor); }
    public String creditedNetArs() { return ars(Math.subtractExact(grossMinor, creditMinor)); }
    public static String ars(long minor) { return BigDecimal.valueOf(minor, 2).toPlainString().replace('.', ','); }
}
