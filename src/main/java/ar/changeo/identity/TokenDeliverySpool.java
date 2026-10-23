package ar.changeo.identity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

final class TokenDeliverySpool {
    private static final int MAX_SEALED_BYTES = 16384;
    private final Path directory;
    private final Path keyPath;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    TokenDeliverySpool(Path directory, Path keyPath, ObjectMapper json) {
        this.directory = directory.toAbsolutePath().normalize();
        this.keyPath = keyPath.toAbsolutePath().normalize();
        this.json = json;
        try { validateDirectory(this.directory); key(); }
        catch (IOException failure) { throw new MaterialFailure(); }
    }

    void seal(Binding binding, String sender, String recipient, String raw) {
        if (!AccountService.hash(raw).equals(binding.hash()) || !raw.matches("[A-Za-z0-9_-]{43}")) { throw new MaterialFailure(); }
        byte[] body = request(binding, sender, recipient, raw);
        try {
            validateDirectory(directory);
            byte[] plain = json.writeValueAsBytes(Map.of("raw", raw, "body", Base64.getEncoder().encodeToString(body)));
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.bytes());
            byte[] encrypted = cipher.doFinal(plain);
            ByteBuffer envelope = ByteBuffer.allocate(1 + nonce.length + encrypted.length);
            envelope.put((byte) 1).put(nonce).put(encrypted).flip();
            if (envelope.remaining() > MAX_SEALED_BYTES) { throw new MaterialFailure(); }
            try (FileChannel file = FileChannel.open(path(binding), Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                while (envelope.hasRemaining()) { file.write(envelope); }
                file.force(true);
            }
            try (FileChannel folder = FileChannel.open(directory, StandardOpenOption.READ)) { folder.force(true); }
        } catch (IOException | GeneralSecurityException | tools.jackson.core.JacksonException failure) { throw new MaterialFailure(); }
    }

    byte[] open(Binding binding) {
        try {
            validateDirectory(directory);
            byte[] envelope = readPrivate(path(binding), MAX_SEALED_BYTES);
            if (envelope.length < 30 || envelope[0] != 1) { throw new MaterialFailure(); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, envelope, 1, 12));
            cipher.updateAAD(binding.bytes());
            var material = json.readTree(cipher.doFinal(envelope, 13, envelope.length - 13));
            String raw = material.path("raw").asString("");
            if (!raw.matches("[A-Za-z0-9_-]{43}") || !AccountService.hash(raw).equals(binding.hash())) { throw new MaterialFailure(); }
            byte[] body = Base64.getDecoder().decode(material.path("body").asString(""));
            var message = json.readTree(body);
            if (!message.path("to").isArray() || message.path("to").size() != 1) { throw new MaterialFailure(); }
            byte[] expected = request(binding, message.path("from").asString(""), message.path("to").path(0).asString(""), raw);
            if (!Arrays.equals(body, expected)) { throw new MaterialFailure(); }
            return body;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | tools.jackson.core.JacksonException failure) { throw new MaterialFailure(); }
    }

    private byte[] request(Binding binding, String sender, String recipient, String raw) {
        AccountService.normalize(sender); AccountService.normalize(recipient);
        var message = new LinkedHashMap<String, Object>();
        message.put("from", sender); message.put("to", List.of(recipient));
        message.put("subject", binding.purpose() == TokenPurpose.CONTACT ? "Verificá tu contacto de Changeo" : "Recuperá tu acceso a Changeo");
        message.put("text", "Código privado de Changeo (" + binding.purpose() + "): " + raw + "\nVence: " + binding.expiresAt() + "\nNo compartas este código.");
        return json.writeValueAsBytes(message);
    }

    private Path path(Binding binding) { return directory.resolve(binding.deliveryId() + ".sealed"); }

    private SecretKeySpec key() throws IOException {
        validateDirectory(keyPath.getParent());
        byte[] value = readPrivate(keyPath, 32);
        if (value.length != 32) { throw new MaterialFailure(); }
        return new SecretKeySpec(value, "AES");
    }

    static byte[] readPrivate(Path path, int maximum) throws IOException {
        rejectSymlinks(path.toAbsolutePath().normalize());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !owned(path)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))) { throw new IOException("Private material unavailable"); }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > maximum) { throw new IOException("Private material exceeds bound"); }
            ByteBuffer buffer = ByteBuffer.allocate(maximum + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            if (buffer.position() > maximum) { throw new IOException("Private material exceeds bound"); }
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    static void validateDirectory(Path path) throws IOException {
        rejectSymlinks(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !owned(path)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) { throw new IOException("Private directory unavailable"); }
    }

    private static boolean owned(Path path) throws IOException {
        return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name"));
    }

    private static void rejectSymlinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) { throw new IOException("Private path unavailable"); }
        }
    }

    record Binding(UUID deliveryId, UUID tokenId, UUID accountId, TokenPurpose purpose, long generation, long epoch, Instant expiresAt, String hash) {
        byte[] bytes() { return ("identity-token-v1|" + deliveryId + "|" + tokenId + "|" + accountId + "|" + purpose + "|" + generation + "|" + epoch + "|" + expiresAt + "|" + hash).getBytes(StandardCharsets.UTF_8); }
    }

    static final class MaterialFailure extends RuntimeException {
        MaterialFailure() { super("Private token delivery material unavailable"); }
    }
}
