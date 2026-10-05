package ar.changeo.files.api;

import ar.changeo.moderation.api.ContentReference;
import java.util.Objects;
import java.util.UUID;

public record FileReference(UUID id, ContentReference content, String digest) {
    public FileReference { Objects.requireNonNull(id); Objects.requireNonNull(content); if (digest == null || !digest.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("Invalid file digest"); } }
}
