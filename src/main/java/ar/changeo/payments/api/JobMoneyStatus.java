package ar.changeo.payments.api;

import ar.changeo.moderation.api.ContentReference;
import java.util.*;

public record JobMoneyStatus(UUID jobId, ContentReference agreement, Funding funding, Release release, BankPayout bankPayout) {
    public enum Funding { INTEGRATION_PENDING, UNFUNDED, CONFIRMED_FULL, UNKNOWN, PENDING, FAILED, PARTIALLY_REFUNDED, CONFIRMED_REMAINING, REFUNDED, REVERSED }
    public enum Release { INTEGRATION_PENDING, NOT_RELEASED, RELEASED, UNKNOWN, PENDING, FAILED }
    public enum BankPayout { INTEGRATION_PENDING, NOT_PAID, PAID, UNKNOWN, PENDING, FAILED }
    public JobMoneyStatus { Objects.requireNonNull(jobId); Objects.requireNonNull(agreement); Objects.requireNonNull(funding); Objects.requireNonNull(release); Objects.requireNonNull(bankPayout); }
    public JobMoneyStatus requireExact(UUID expectedJob, ContentReference expectedAgreement) {
        if (!jobId.equals(expectedJob) || !agreement.equals(expectedAgreement)) { throw new IllegalArgumentException("Estado financiero ajeno al acuerdo"); }
        return this;
    }
    public void requireFunded() {
        if (!permitsFundedWork()) {
            throw new IllegalStateException(funding == Funding.INTEGRATION_PENDING
                    ? "Integración financiera pendiente: la ejecución requiere financiación completa exacta"
                    : "La ejecución financiera requiere financiación confirmada y respaldo del importe vigente");
        }
    }
    public boolean permitsFundedWork() { return funding == Funding.CONFIRMED_FULL || funding == Funding.CONFIRMED_REMAINING; }
}
