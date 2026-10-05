package ar.changeo.identity;

import ar.changeo.config.SandboxSafety;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SandboxSafetyTest {
    private MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.address", "127.0.0.1").withProperty("changeo.real-money", "false");
        environment.setActiveProfiles("sandbox"); return environment;
    }
    @Test void shouldAllowOnlyPrivateSandbox() { assertThatCode(() -> SandboxSafety.validate(environment())).doesNotThrowAnyException(); }
    @ParameterizedTest @ValueSource(strings = {"0.0.0.0", "localhost", "", "192.168.1.10"})
    void shouldDenyPublicOrAmbiguousBind(String bind) {
        assertThatThrownBy(() -> SandboxSafety.validate(environment().withProperty("server.address", bind))).isInstanceOf(IllegalStateException.class);
    }
    @Test void shouldDenyMissingManagementBind() {
        assertThatThrownBy(() -> SandboxSafety.validate(environment().withProperty("management.server.port", "8081"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void shouldDenyRealMoneyAndProfileOverrides() {
        assertThatThrownBy(() -> SandboxSafety.validate(environment().withProperty("changeo.real-money", "true"))).isInstanceOf(IllegalStateException.class);
        MockEnvironment environment = environment(); environment.setActiveProfiles("sandbox", "production");
        assertThatThrownBy(() -> SandboxSafety.validate(environment)).isInstanceOf(IllegalStateException.class);
    }
}
