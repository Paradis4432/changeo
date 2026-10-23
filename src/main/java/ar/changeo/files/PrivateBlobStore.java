package ar.changeo.files;

import ar.changeo.moderation.ModerationFailure;
import java.util.Objects;
import java.util.UUID;

interface PrivateBlobStore {
    int MAX_BYTES = 1_048_576;

    Blob create(UUID fileId, String mediaType, byte[] bytes);
    byte[] read(Blob blob, String mediaType, long expectedSize);
    Cleanup rollback(Blob created);

    sealed interface Blob permits Local, Remote {
        UUID fileId();
    }

    record Local(UUID fileId, Object identity) implements Blob {
        public Local { Objects.requireNonNull(fileId); }
        Local(UUID fileId) { this(fileId, null); }
    }

    record Remote(UUID fileId, String storeId, String objectKey, String etag) implements Blob {
        public Remote {
            Objects.requireNonNull(fileId);
            if (storeId == null || !storeId.matches("[a-f0-9]{64}")
                    || !("private-blobs/" + fileId).equals(objectKey)
                    || etag == null || etag.isEmpty() || etag.length() > 256
                    || !etag.chars().allMatch(value -> value >= 33 && value <= 126)) {
                throw unavailable();
            }
        }
    }

    enum Cleanup { REMOVED, RETAINED }

    static void requireSize(long size) {
        if (size < 1 || size > MAX_BYTES) { throw unavailable(); }
    }

    static ModerationFailure unavailable() { return new ModerationFailure("Almacenamiento privado no disponible"); }
}
