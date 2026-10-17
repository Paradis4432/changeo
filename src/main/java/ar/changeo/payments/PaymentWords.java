package ar.changeo.payments;

import ar.changeo.payments.api.JobMoneyStatus;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component("paymentWords")
public class PaymentWords {
    public String amount(long minor) { return "ARS " + BigDecimal.valueOf(minor, 2).toPlainString().replace('.', ','); }
    public String funding(JobMoneyStatus.Funding state) {
        return switch (state) {
            case INTEGRATION_PENDING -> "Integración pendiente: no se confirmaron fondos";
            case UNFUNDED -> "Sin financiar";
            case PENDING -> "Operación pendiente de confirmación";
            case FAILED -> "Sin confirmación completa; consultá el intento";
            case UNKNOWN -> "Pendiente de conciliación o resolución de la pausa";
            case CONFIRMED_FULL -> "Financiación sintética completa confirmada";
            case PARTIALLY_REFUNDED -> "Reembolso parcial confirmado, sin continuación";
            case CONFIRMED_REMAINING -> "Derecho restante respaldado; continuación bilateral confirmada";
            case REFUNDED -> "Reembolso total confirmado";
            case REVERSED -> "Respaldo revertido; requiere recuperación";
        };
    }
    public String release(JobMoneyStatus.Release state) {
        return switch (state) {
            case INTEGRATION_PENDING -> "Integración pendiente";
            case NOT_RELEASED -> "Sin liberar";
            case PENDING -> "Liberación pendiente";
            case UNKNOWN -> "Liberación por conciliar";
            case FAILED -> "Intento de liberación sin efecto confirmado";
            case RELEASED -> "Liberación confirmada";
        };
    }
    public String payout(JobMoneyStatus.BankPayout state) {
        return switch (state) {
            case INTEGRATION_PENDING -> "Integración pendiente";
            case NOT_PAID -> "Sin pago bancario";
            case PENDING -> "Pago bancario pendiente";
            case UNKNOWN -> "Pago bancario por conciliar";
            case FAILED -> "Intento bancario sin efecto confirmado";
            case PAID -> "Pago bancario sintético confirmado";
        };
    }
    public String kind(String kind) {
        return switch (kind) {
            case "FUND" -> "Financiación";
            case "RELEASE" -> "Liberación final";
            case "PAYOUT" -> "Pago bancario al proveedor original";
            case "REFUND" -> "Reembolso bilateral al pagador original";
            default -> "Recuperación";
        };
    }
    public String operation(String state) {
        return switch (state) {
            case "QUEUED", "CLAIMED", "RETRY_WAIT" -> "Pendiente";
            case "DISPATCHING", "UNKNOWN" -> "Por conciliar; conservamos las reservas";
            case "RECOVERY" -> "Respaldo por recuperar; requiere ayuda";
            case "SUCCESS" -> "Confirmada";
            case "FAILED_NO_EFFECT" -> "Sin efecto, con confirmación definitiva";
            case "CANCELLED" -> "Cancelada antes del efecto";
            default -> throw new PaymentFailure("Estado financiero no reconocido");
        };
    }
}
