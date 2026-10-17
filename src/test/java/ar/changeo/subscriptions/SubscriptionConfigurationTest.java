package ar.changeo.subscriptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class SubscriptionConfigurationTest {
    private ApplicationContextRunner enabled() {
        return new ApplicationContextRunner().withUserConfiguration(SubscriptionConfiguration.class).withPropertyValues(
                "spring.profiles.active=sandbox", "server.address=127.0.0.1", "changeo.real-money=false",
                "changeo.synthetic-subscriptions=true", "changeo.synthetic-payments=true");
    }

    @Test void enabledRequiresExplicitSyntheticPaymentsAndOnlyTheAcceptedInitialBacking() {
        enabled().run(context -> { assertThat(context).hasNotFailed(); assertThat(context.getBean(SubscriptionPolicy.class)).isEqualTo(SubscriptionPolicy.example()); });
        enabled().withPropertyValues("changeo.synthetic-payments=false").run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("changeo.subscription-fee-capital-minor=10001").run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("changeo.subscription-promotion-budget-minor=0").run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest @ValueSource(strings = {"server.address=0.0.0.0", "changeo.real-money=true", "spring.profiles.active=production", "spring.profiles.active=sandbox,production", "changeo.subscription-fee-capital-minor=-1"})
    void unsafeEnabledConfigurationCannotCreateAPolicy(String property) {
        enabled().withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test void defaultHasOnlyPendingBehaviorAndNoPolicy() {
        new ApplicationContextRunner().withUserConfiguration(SubscriptionConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(SubscriptionPolicy.class);
            assertThat(context).hasSingleBean(ar.changeo.subscriptions.api.ProviderEntitlementAccess.class);
            assertThat(context).hasSingleBean(ar.changeo.subscriptions.api.LoyaltyPublicAccess.class);
        });
    }
}
