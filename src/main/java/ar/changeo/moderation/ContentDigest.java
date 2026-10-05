package ar.changeo.moderation;

import ar.changeo.files.api.FileReference;
import java.nio.charset.*;
import java.nio.CharBuffer;
import java.security.*;
import java.util.*;

public final class ContentDigest {
    private ContentDigest() {}
    public static String bytes(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    public static String payload(String text, String label, List<FileReference> files) {
        StringBuilder value = new StringBuilder(text.length() + 256).append(text.length()).append(':').append(text).append(':').append(label);
        files.stream().sorted(Comparator.comparing(f -> f.id().toString())).forEach(f -> value.append(':').append(f.id()).append(':').append(f.digest()));
        return bytes(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void validate(String text, String label, List<FileReference> files) {
        if(text!=null) {
            try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)); }
            catch(CharacterCodingException failure) { throw new ModerationFailure("Texto UTF-8 inválido"); }
        }
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > 32768 || label == null || !Set.of("GENERAL", "SENSITIVE", "ADULT").contains(label) || files == null || files.size() > 4 || files.stream().anyMatch(Objects::isNull) || files.stream().map(FileReference::id).distinct().count() != files.size()) {
            throw new ModerationFailure("Texto, etiqueta o adjuntos inválidos; límite local 32 KiB y cuatro archivos");
        }
    }
}
