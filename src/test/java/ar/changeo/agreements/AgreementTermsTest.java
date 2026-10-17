package ar.changeo.agreements;

import ar.changeo.agreements.api.AgreementTerms;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class AgreementTermsTest {
    AgreementTerms terms(long service, long delivery, int rounds, int minutes, Instant deadline, FulfillmentMode mode, AgreementTerms.Arrangement arrangement) {
        return new AgreementTerms("Alcance\nprivado:exacto", service, delivery, mode, arrangement, "Coordinación", "Instrucciones privadas", "Entregable",
                deadline, rounds, AgreementTerms.Evidence.TEXT, "Descripción de evidencia", AgreementTerms.PolicyScope.PRIVATE_SYNTHETIC,
                AgreementTerms.PolicyVersion.S1_EXPLICIT_ACCEPTANCE_V1, AgreementTerms.FinalRule.EXPLICIT_CUSTOMER_ACCEPTANCE,
                AgreementTerms.ReviewTrigger.APPROVED_FINAL_DELIVERY, minutes, AgreementTerms.SupportRoute.NONFINANCIAL_SUPPORT_CASE,
                AgreementTerms.InactivityRule.NO_AUTOMATIC_ACCEPTANCE_OR_RELEASE);
    }
    @Test void shouldRequireWholeTypedTermsAndPreserveExactMinorUnitPriceAndWindow() {
        var terms = terms(12345, 5, 10, 1440, Instant.parse("2026-12-01T18:30:00Z"), FulfillmentMode.SHIPPED, AgreementTerms.Arrangement.PICKUP);
        assertThat(terms.requireComplete(FulfillmentMode.SHIPPED)).isSameAs(terms);
        assertThat(terms.totalMinor()).isEqualTo(12350); assertThat(terms.totalPrice()).isEqualTo("ARS 123.50");
        assertThat(terms.dueAt(Instant.parse("2026-12-02T00:00:00Z"))).isEqualTo(Instant.parse("2026-12-03T00:00:00Z"));
        assertThat(terms.reviewText()).contains("Alcance\nprivado:exacto", "12345", "PRIVATE_SYNTHETIC", "S1_EXPLICIT_ACCEPTANCE_V1", "NO_AUTOMATIC_ACCEPTANCE_OR_RELEASE");
        assertThatThrownBy(() -> terms.requireComplete(FulfillmentMode.LOCAL)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @CsvSource({"-1,60", "11,60", "0,0", "0,1441"})
    void shouldRejectUnsupportedReviewBounds(int rounds, int minutes) {
        assertThatThrownBy(() -> terms(1, 0, rounds, minutes, Instant.parse("2026-12-01T18:30:00Z"), FulfillmentMode.REMOTE, AgreementTerms.Arrangement.REMOTE)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void shouldRejectOverflowRatherThanInventAnAmountOrDeadline() {
        assertThatThrownBy(() -> terms(Long.MAX_VALUE, 1, 0, 60, Instant.parse("2026-12-01T18:30:00Z"), FulfillmentMode.REMOTE, AgreementTerms.Arrangement.REMOTE)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> terms(1, 0, 0, 60, Instant.MAX, FulfillmentMode.REMOTE, AgreementTerms.Arrangement.REMOTE)).isInstanceOf(IllegalArgumentException.class);
        var terms = terms(1, 0, 0, 60, Instant.parse("2026-12-01T18:30:00Z"), FulfillmentMode.REMOTE, AgreementTerms.Arrangement.REMOTE);
        assertThatThrownBy(() -> terms.dueAt(Instant.MAX)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void shouldKeepExploratoryFieldsMissingAndRejectFreeTextPolicy() {
        var draft = new AgreementTerms("Alcance por acordar", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(draft.missing()).contains("importes exactos ARS", "política técnica privada explícita");
        assertThatThrownBy(() -> draft.requireComplete(FulfillmentMode.REMOTE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgreementTerms.FinalRule.valueOf("SILENCE_RELEASE")).isInstanceOf(IllegalArgumentException.class);
    }
}
