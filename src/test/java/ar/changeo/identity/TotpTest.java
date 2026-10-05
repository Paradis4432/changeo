package ar.changeo.identity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {
    @Test
    void shouldMatchRfc6238Sha1Vector() {
        byte[] secret = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(Totp.code(secret, 1, 8)).isEqualTo("94287082");
        assertThat(Totp.code(secret, 37037036, 8)).isEqualTo("07081804");
    }
}
