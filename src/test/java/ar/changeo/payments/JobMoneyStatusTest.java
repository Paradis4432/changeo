package ar.changeo.payments;

import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import ar.changeo.payments.api.JobMoneyStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class JobMoneyStatusTest {
    @Test
    void shouldPermitExecutionForConfirmedBilateralBackedRemainingFunding() {
        var status = status("CONFIRMED_REMAINING");
        assertThatCode(status::requireFunded).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTEGRATION_PENDING", "UNFUNDED", "UNKNOWN"})
    void shouldDenyExecutionWithoutConfirmedBacking(String funding) {
        assertThatThrownBy(status(funding)::requireFunded).isInstanceOf(IllegalStateException.class);
    }

    private static JobMoneyStatus status(String funding) {
        return new JobMoneyStatus(UUID.randomUUID(), new ContentReference(SurfaceKind.QUOTE_DESCRIPTION,
                UUID.randomUUID(), UUID.randomUUID()), JobMoneyStatus.Funding.valueOf(funding),
                JobMoneyStatus.Release.NOT_RELEASED, JobMoneyStatus.BankPayout.NOT_PAID);
    }
}
