package ar.changeo.subscriptions;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SubscriptionPolicyTest {
    private final SubscriptionPolicy policy = SubscriptionPolicy.example();

    @Test void monthlyEndpointsRetainOriginalAnchorAfterClamping() {
        var january = ZonedDateTime.of(2027, 1, 31, 10, 0, 0, 0, policy.zone());
        var february = policy.nextEndpoint(january.toInstant(), 31);
        var march = policy.nextEndpoint(february, 31);
        assertThat(february.atZone(policy.zone()).toLocalDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(march.atZone(policy.zone()).toLocalDate()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(march.atZone(policy.zone()).toLocalTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test void disclosedTermsBindEveryRecurringCommercialRule() {
        var terms = policy.disclosure();
        assertThat(terms).contains("SL1B-EXAMPLE-2", "ARS 100", "30 días", "7 días", "2 días", "100 centavos", "cancelar", "sintético");
        assertThat(policy.digest()).hasSize(64);
        assertThat(policy.grossMinor()).isEqualTo(10000);
        assertThat(policy.feeMinor()).isEqualTo(10);
    }

    @Test void foundingAndTrialDoNotSupplyPaidGrace() {
        var now = Instant.parse("2027-01-31T13:00:00Z");
        assertThat(policy.foundingEnd(now)).isEqualTo(now.plus(Duration.ofDays(30)));
        assertThat(policy.trialEnd(now)).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(policy.graceEnd(now)).isEqualTo(now.plus(Duration.ofDays(2)));
    }

    @Test void changedSyntheticTermsDiscloseExactCentsAndDifferentDigest() {
        var changed = new SubscriptionPolicy("SL1C-CENTS", 12050, 30, 7, 2, 100, 20, policy.zone());
        assertThat(changed.disclosure()).contains("ARS 120,50", "ARS 119,50", "ARS 0,20");
        assertThat(changed.digest()).isNotEqualTo(policy.digest());
        assertThat(SubscriptionPolicy.ars(Long.MAX_VALUE)).isEqualTo("92233720368547758,07");
        assertThat(new SubscriptionPolicy("SL1C-ZERO-FEE", 101, 30, 7, 2, 100, 0, policy.zone()).disclosure()).contains("ARS 1,01", "ARS 0,01", "ARS 0,00");
    }

    @Test void unsupportedRewardGrossFeeAndCalendarCannotBePublished() {
        assertThatThrownBy(() -> new SubscriptionPolicy("bad", 10000, 30, 7, 2, 200, 10, policy.zone())).isInstanceOf(SubscriptionFailure.class);
        assertThatThrownBy(() -> new SubscriptionPolicy("bad", 100, 30, 7, 2, 100, 10, policy.zone())).isInstanceOf(SubscriptionFailure.class);
        assertThatThrownBy(() -> new SubscriptionPolicy("bad", 10000, 30, 7, 2, 100, -1, policy.zone())).isInstanceOf(SubscriptionFailure.class);
        assertThatThrownBy(() -> new SubscriptionPolicy("bad", 10000, 30, 7, 2, 100, 10, ZoneOffset.UTC)).isInstanceOf(SubscriptionFailure.class);
    }
}
