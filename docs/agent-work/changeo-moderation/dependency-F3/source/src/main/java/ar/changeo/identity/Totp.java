package ar.changeo.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Totp {
    private Totp() {}

    static String code(byte[] secret, long counter, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = digest[digest.length - 1] & 15;
            int truncated = ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff;
            int modulus = digits == 8 ? 100000000 : 1000000;
            return String.format(java.util.Locale.ROOT, "%0" + digits + "d", truncated % modulus);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP cryptography unavailable", exception);
        }
    }

    static long match(byte[] secret, String submitted, long current, long last) {
        if (submitted == null || !submitted.matches("[0-9]{6}")) { return -1; }
        for (long counter = current - 1; counter <= current + 1; counter++) {
            if (counter > last && MessageDigest.isEqual(code(secret, counter, 6).getBytes(StandardCharsets.US_ASCII),
                    submitted.getBytes(StandardCharsets.US_ASCII))) { return counter; }
        }
        return -1;
    }
}
