package ar.changeo.identity;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TokenDeliverySpoolTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path root;
    private Path spool;
    private Path key;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private TokenDeliverySpool.Binding binding;

    @BeforeEach void setup() throws Exception {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        spool = root.resolve("spool"); PrivateFiles.directory(spool);
        key = root.resolve("key"); byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); PrivateFiles.writeNew(key, bytes);
        binding = new TokenDeliverySpool.Binding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TokenPurpose.CONTACT, 1, 2, Instant.parse("2026-10-19T12:30:00Z"), AccountService.hash(raw));
    }

    @Test void shouldFreezeAuthenticatedPrivateMaterialAcrossRestartAndActualCopy() throws Exception {
        var owner = new TokenDeliverySpool(spool, key, json);
        owner.seal(binding, "sender@example.test", "recipient@example.test", raw);
        byte[] body = owner.open(binding);
        assertThat(json.readTree(body).path("text").asString()).contains(raw, binding.expiresAt().toString(), "CONTACT");
        Path sealed = spool.resolve(binding.deliveryId() + ".sealed");
        assertThat(new String(Files.readAllBytes(sealed), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(raw);
        assertThat(Files.getPosixFilePermissions(sealed)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(new TokenDeliverySpool(spool, key, json).open(binding)).isEqualTo(body);
        Path restored = root.resolve("restored"); PrivateFiles.directory(restored);
        Path restoredSpool = restored.resolve("spool"); PrivateFiles.directory(restoredSpool);
        Files.copy(key, restored.resolve("key")); Files.copy(sealed, restoredSpool.resolve(sealed.getFileName()));
        assertThat(new TokenDeliverySpool(restoredSpool, restored.resolve("key"), json).open(binding)).isEqualTo(body);
        assertThatThrownBy(() -> owner.seal(binding, "sender@example.test", "recipient@example.test", raw)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        try (var files = Files.list(spool)) { assertThat(files.count()).isEqualTo(1); }
    }

    @Test void shouldRejectWrongBindingKeyCorruptionMissingAndOversizedMaterial() throws Exception {
        var owner = new TokenDeliverySpool(spool, key, json); owner.seal(binding, "sender@example.test", "recipient@example.test", raw);
        var changed = new TokenDeliverySpool.Binding(binding.deliveryId(), binding.tokenId(), UUID.randomUUID(), binding.purpose(), 1, 2, binding.expiresAt(), binding.hash());
        assertThatThrownBy(() -> owner.open(changed)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        Path wrong = root.resolve("wrong"); PrivateFiles.writeNew(wrong, new byte[32]);
        assertThatThrownBy(() -> new TokenDeliverySpool(spool, wrong, json).open(binding)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        assertThatThrownBy(() -> new TokenDeliverySpool(spool, root.resolve("missing"), json)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        Path sealed = spool.resolve(binding.deliveryId() + ".sealed"); byte[] valid = Files.readAllBytes(sealed);
        valid[valid.length - 1] ^= 1; Files.write(sealed, valid);
        assertThatThrownBy(() -> owner.open(binding)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        Files.write(sealed, new byte[16385]);
        assertThatThrownBy(() -> owner.open(binding)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        assertThatThrownBy(() -> owner.seal(binding, "sender@example.test", "recipient@example.test", raw + "x")).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
    }

    @Test void shouldRejectPublicModesAndEverySymlinkAncestorWithoutRepairingPaths() throws Exception {
        var owner = new TokenDeliverySpool(spool, key, json);
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> owner.seal(binding, "sender@example.test", "recipient@example.test", raw)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        assertThat(Files.getPosixFilePermissions(key)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        Path alias = root.resolve("alias"); Files.createSymbolicLink(alias, spool);
        assertThatThrownBy(() -> new TokenDeliverySpool(alias, key, json)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        Path keyAlias = root.resolve("key-alias"); Files.createSymbolicLink(keyAlias, key);
        assertThatThrownBy(() -> new TokenDeliverySpool(spool, keyAlias, json)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
        Files.setPosixFilePermissions(spool, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> owner.seal(binding, "sender@example.test", "recipient@example.test", raw)).isInstanceOf(TokenDeliverySpool.MaterialFailure.class);
    }
}
