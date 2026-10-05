package ar.changeo.identity;

import java.io.IOException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FactorCipher {
    private final Path keyPath;
    private final SecureRandom random = new SecureRandom();

    FactorCipher(@Value("${changeo.mfa-key}") String keyPath) { this.keyPath = Path.of(keyPath); }

    void initializeForOperator(boolean hasFactors) {
        if (Files.exists(keyPath)) { key(); return; }
        if (hasFactors) { throw new IdentityFailure("Clave MFA requerida; no se reemplaza automáticamente"); }
        byte[] key = new byte[32]; random.nextBytes(key);
        try { PrivateFiles.writeNew(keyPath, key); }
        catch (IOException failure) { throw new IdentityFailure("No se pudo crear la clave privada MFA"); }
    }

    String encrypt(byte[] secret) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(secret);
            byte[] value = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, value, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(value);
        } catch (GeneralSecurityException failure) { throw new IdentityFailure("Protección MFA no disponible"); }
    }

    byte[] decrypt(String value) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(value);
            if (encrypted.length < 28) { throw new IdentityFailure("Factor MFA inválido"); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, encrypted, 0, 12));
            return cipher.doFinal(encrypted, 12, encrypted.length - 12);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IdentityFailure("Clave o factor MFA inválido");
        }
    }

    private SecretKeySpec key() {
        try {
            byte[] bytes = PrivateFiles.read(keyPath);
            if (bytes.length != 32) { throw new IdentityFailure("Clave MFA inválida"); }
            return new SecretKeySpec(bytes, "AES");
        } catch (IOException failure) { throw new IdentityFailure("Clave privada MFA no disponible"); }
    }
}
