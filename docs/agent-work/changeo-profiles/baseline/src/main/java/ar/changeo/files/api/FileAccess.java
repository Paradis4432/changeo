package ar.changeo.files.api;

import ar.changeo.moderation.api.*;
import java.util.UUID;

public interface FileAccess {
    FileReference intake(UUID command, AuthorContext author, ContentReference content, String mediaType, byte[] bytes);
    Metadata metadata(FileReference reference);
    Download read(FileReference reference, Purpose purpose);
    enum Purpose { ORDINARY, REVIEW }
    record Metadata(FileReference reference, String mediaType, long size, boolean safe, String reason) {}
    record Download(byte[] bytes, String mediaType) {
        public Download { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
