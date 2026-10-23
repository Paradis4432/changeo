package ar.changeo.files.api;

import ar.changeo.moderation.api.ContentReference;
import java.util.*;

public interface ReviewFileAccess {
    Material materialize(Request request);

    record Request(ContentReference content, UUID task, UUID token, long generation, List<FileReference> attachments) {
        public Request {
            Objects.requireNonNull(content); Objects.requireNonNull(task); Objects.requireNonNull(token);
            attachments = List.copyOf(attachments);
            if (generation < 0 || attachments.size() > 4 || attachments.stream().anyMatch(file -> !file.content().equals(content))
                    || attachments.stream().map(FileReference::id).distinct().count() != attachments.size()) {
                throw new IllegalArgumentException("Invalid review material binding");
            }
        }
    }

    record Material(List<File> files) {
        public Material { files = List.copyOf(files); if (files.size() > 4) { throw new IllegalArgumentException("Invalid material size"); } }
    }

    record File(FileReference reference, String mediaType, byte[] bytes) {
        public File {
            Objects.requireNonNull(reference);
            if (!Set.of("text/plain", "image/png", "image/jpeg").contains(mediaType) || bytes.length < 1 || bytes.length > 1048576) {
                throw new IllegalArgumentException("Unsupported review material");
            }
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
