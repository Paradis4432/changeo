package ar.changeo.payments;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class CurrentFinancialDisabledPostgresTest extends PaymentDisabledPostgresTest {
    @DynamicPropertySource
    static void currentFixture(DynamicPropertyRegistry properties) {
        CurrentFinancialTestFixture.supplied().register(properties, false);
    }
}
