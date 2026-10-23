package ar.changeo.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PrivateHttpsOriginTest {
    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1", "https://127.0.0.1:443"})
    void shouldTreatOnlyDefaultHttpsPortAsOptional(String value) {
        var origin = PrivateHttpsOrigin.parse(value);
        assertThat(origin.matchesAuthority("127.0.0.1")).isTrue();
        assertThat(origin.matchesAuthority("127.0.0.1:443")).isTrue();
        assertThat(origin.matchesAuthority("127.0.0.1:58107")).isFalse();
        assertThat(origin.matchesOrigin("https://127.0.0.1:443")).isTrue();
    }

    @Test
    void shouldRequireExactExplicitPortAndLiteralIpv6Authority() {
        var origin = PrivateHttpsOrigin.parse("https://[::1]:58107");
        assertThat(origin.host()).isEqualTo("::1");
        assertThat(origin.port()).isEqualTo(58107);
        assertThat(origin.matchesAuthority("[::1]:58107")).isTrue();
        assertThat(origin.matchesAuthority("[::1]")).isFalse();
        assertThat(origin.matchesAuthority("::1:58107")).isFalse();
        assertThat(origin.matchesAuthority("[0:0:0:0:0:0:0:1]:58107")).isFalse();
        assertThat(origin.matchesServer("::1", 58107)).isTrue();
        assertThat(origin.matchesServer("[::1]", 58107)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "false", "http://127.0.0.1", "HTTPS://127.0.0.1", "https://localhost",
            "https://127.1", "https://127.0.0.2", "https://2130706433", "https://127.0.0.01",
            "https://127.0.0.1/", "https://127.0.0.1?x=1", "https://127.0.0.1#x", "https://u@127.0.0.1",
            "https://127.0.0.1\\x", "https://127.0.0.1%2f", "https://127.0.0.1 ", " https://127.0.0.1",
            "https://127.0.0.1:0", "https://127.0.0.1:65536", "https://127.0.0.1:-1", "https://127.0.0.1:0443",
            "https://127.0.0.1:", "https://[::ffff:127.0.0.1]", "https://::1", "https://[::1%25lo]",
            "https://127.0.0.1\t", "https://127.0.0.1\n", "https://127.0.0.1\u00a0"})
    void shouldRejectInvalidConfigurationWithoutReflectingItsValue(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> PrivateHttpsOrigin.parse(value))
                .withMessage("Invalid private HTTPS origin").withNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "https://127.0.0.1:58107/", "https://127.0.0.1:58107 https://127.0.0.1:58107",
            "https://127.0.0.1:58107,https://127.0.0.1:58107", "http://127.0.0.1:58107", "https://evil.test:58107"})
    void shouldDenyMalformedOrForeignPresentOrigin(String value) {
        assertThat(PrivateHttpsOrigin.parse("https://127.0.0.1:58107").matchesOrigin(value)).isFalse();
    }

    @Test
    void shouldBoundInputsAndRejectMissingAuthority() {
        var origin = PrivateHttpsOrigin.parse("https://127.0.0.1:58107");
        assertThat(origin.matchesAuthority(null)).isFalse();
        assertThat(origin.matchesAuthority("x".repeat(129))).isFalse();
        assertThat(origin.matchesOrigin("https://127.0.0.1:58107" + "x".repeat(129))).isFalse();
        assertThat(origin.matchesServer("evil.test", 58107)).isFalse();
        assertThat(origin.matchesServer("127.0.0.1", 443)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> PrivateHttpsOrigin.parse(null))
                .withMessage("Invalid private HTTPS origin").withNoCause();
    }

    @Test
    void shouldIndependentlyAdmitErrorAndAsyncDispatchesWithoutReadingSessionBodyOrParameters() throws Exception {
        var filter = new PrivateHttpsOriginFilter(PrivateHttpsOrigin.parse("https://127.0.0.1:58107"));
        for (var dispatcher : java.util.List.of(jakarta.servlet.DispatcherType.REQUEST,
                jakarta.servlet.DispatcherType.ERROR, jakarta.servlet.DispatcherType.ASYNC)) {
            var request = new org.springframework.mock.web.MockHttpServletRequest() {
                @Override public jakarta.servlet.http.HttpSession getSession(boolean create) { throw new AssertionError("Session touched"); }
                @Override public String getParameter(String name) { throw new AssertionError("Parameters touched"); }
                @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Body touched"); }
                @Override public java.io.BufferedReader getReader() { throw new AssertionError("Body touched"); }
            };
            request.setDispatcherType(dispatcher);
            request.setAttribute(PrivateHttpsOriginFilter.class.getName() + ".FILTERED", true);
            request.setMethod("POST");
            request.setScheme("https");
            request.setSecure(true);
            request.setServerName("127.0.0.1");
            request.setServerPort(58107);
            request.addHeader("Host", "127.0.0.1:58107");
            request.addHeader("Origin", "null");
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(request, response, (admitted, output) -> { throw new AssertionError("Rejected dispatch admitted"); });
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).isEqualTo("forbidden\n");
            assertThat(response.getHeader("Set-Cookie")).isNull();
        }
    }
}
