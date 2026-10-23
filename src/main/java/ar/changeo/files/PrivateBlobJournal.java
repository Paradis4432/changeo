package ar.changeo.files;

import ar.changeo.moderation.ContentDigest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

final class PrivateBlobJournal {
    private final Path root;
    private final Clock clock;

    PrivateBlobJournal(Path root, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    void attempt(UUID fileId, String storeId, String mediaType, byte[] bytes) {
        append(fileId, "attempt", "POSSIBLE_EFFECT\n" + storeId + "\nprivate-blobs/" + fileId + "\n"
                + ContentDigest.bytes(bytes) + "\n" + bytes.length + "\n" + mediaType + "\n");
    }

    void outcome(UUID fileId, Outcome outcome) { append(fileId, "outcome", outcome.name() + "\n"); }

    void completion(UUID fileId, Completion completion) { append(fileId, "completion", completion.name() + "\n"); }

    private void append(UUID fileId, String stage, String observation) {
        try {
            LocalPrivateBlobStore.directory(root);
            try (var channel = FileChannel.open(root.resolve(fileId + "." + stage),
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = StandardCharsets.UTF_8.encode(observation + clock.instant() + "\n");
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            }
        } catch (IOException failure) { throw PrivateBlobStore.unavailable(); }
    }

    enum Outcome { CREATED, QUARANTINED }
    enum Completion { COMMITTED, RETAINED }
}
