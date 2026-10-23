package ar.changeo.files;

import ar.changeo.moderation.ModerationFailure;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.*;
import static org.assertj.core.api.Assertions.*;

class LocalPrivateBlobStoreTest {
    @TempDir(cleanup = CleanupMode.NEVER) Path directory;

    @Test void shouldCreatePrivateExactBytesAndRemoveOnlyItsOwnedBlob() throws Exception {
        var store = new LocalPrivateBlobStore(directory.resolve("objects"));
        UUID id = UUID.randomUUID();
        byte[] bytes = new byte[PrivateBlobStore.MAX_BYTES];
        var blob = store.create(id, "application/octet-stream", bytes);
        assertThat(store.read(blob, "application/octet-stream", bytes.length)).isEqualTo(bytes);
        assertThat(Files.getPosixFilePermissions(directory.resolve("objects"))).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(directory.resolve("objects").resolve(id.toString()))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(store.rollback(blob)).isEqualTo(PrivateBlobStore.Cleanup.REMOVED);
        assertThat(store.rollback(blob)).isEqualTo(PrivateBlobStore.Cleanup.REMOVED);
    }

    @Test void shouldPreserveCreateNewCollisionAndReplacement() throws Exception {
        var store = new LocalPrivateBlobStore(directory);
        UUID id = UUID.randomUUID();
        var original = store.create(id, "text/plain", new byte[]{1});
        assertThatThrownBy(() -> store.create(id, "text/plain", new byte[]{2})).isInstanceOf(ModerationFailure.class);
        assertThat(store.read(original, "text/plain", 1)).containsExactly(1);
        Path originalPath = directory.resolve(id.toString());
        Path retained = directory.resolve("retained-original");
        Files.move(originalPath, retained);
        store.create(id, "text/plain", new byte[]{2});
        assertThat(store.rollback(original)).isEqualTo(PrivateBlobStore.Cleanup.RETAINED);
        assertThat(Files.readAllBytes(originalPath)).containsExactly(2);
        assertThat(Files.readAllBytes(retained)).containsExactly(1);
    }

    @Test void shouldDenyUnsafeModesLinksSizesAndForeignBackend() throws Exception {
        var store = new LocalPrivateBlobStore(directory);
        UUID id = UUID.randomUUID();
        var blob = store.create(id, "text/plain", new byte[]{1});
        assertThatThrownBy(() -> store.read(blob, "text/plain", 0)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> store.read(blob, "text/plain", 2)).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> store.create(UUID.randomUUID(), "text/plain", new byte[PrivateBlobStore.MAX_BYTES + 1])).isInstanceOf(ModerationFailure.class);
        Path path = directory.resolve(id.toString());
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> store.read(blob, "text/plain", 1)).isInstanceOf(ModerationFailure.class);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Files.move(path, directory.resolve("original"));
        Files.createSymbolicLink(path, directory.resolve("original"));
        assertThatThrownBy(() -> store.read(blob, "text/plain", 1)).isInstanceOf(ModerationFailure.class);
        assertThat(store.rollback(blob)).isEqualTo(PrivateBlobStore.Cleanup.RETAINED);
        Files.createSymbolicLink(directory.resolve("linked"), directory);
        assertThatThrownBy(() -> new LocalPrivateBlobStore(directory.resolve("linked")).create(UUID.randomUUID(), "text/plain", new byte[]{1})).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(() -> store.read(new PrivateBlobStore.Remote(id, "a".repeat(64), "private-blobs/" + id, "tag"), "text/plain", 1)).isInstanceOf(ModerationFailure.class);
    }

    @Test void shouldCleanItsCreatedFileWhenWriteIsInterrupted() {
        var store = new LocalPrivateBlobStore(directory);
        UUID id = UUID.randomUUID();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> store.create(id, "text/plain", new byte[PrivateBlobStore.MAX_BYTES])).isInstanceOf(ModerationFailure.class);
            assertThat(Files.exists(directory.resolve(id.toString()))).isFalse();
        } finally { Thread.interrupted(); }
    }
}
