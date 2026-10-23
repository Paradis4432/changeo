package ar.changeo.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

final class LocalPrivateBlobStore implements PrivateBlobStore {
    private final Path root;

    LocalPrivateBlobStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public Local create(UUID fileId, String mediaType, byte[] bytes) {
        PrivateBlobStore.requireSize(bytes.length);
        Path path = root.resolve(fileId.toString());
        Object identity = null;
        boolean created = false;
        try {
            directory(root);
            try (var channel = FileChannel.open(path, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                created = true;
                identity = attributes(path).fileKey();
                if (identity == null) { throw PrivateBlobStore.unavailable(); }
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
            }
            return new Local(fileId, identity);
        } catch (IOException | RuntimeException failure) {
            if (created && identity != null) { rollback(new Local(fileId, identity)); }
            throw PrivateBlobStore.unavailable();
        }
    }

    @Override public byte[] read(Blob blob, String mediaType, long expectedSize) {
        if (!(blob instanceof Local local)) { throw PrivateBlobStore.unavailable(); }
        PrivateBlobStore.requireSize(expectedSize);
        try {
            directory(root);
            Path path = root.resolve(local.fileId().toString());
            if (!attributes(path).isRegularFile()
                    || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))) {
                throw PrivateBlobStore.unavailable();
            }
            try (var channel = FileChannel.open(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() != expectedSize) { throw PrivateBlobStore.unavailable(); }
                ByteBuffer buffer = ByteBuffer.allocate((int) expectedSize);
                while (buffer.hasRemaining()) { if (channel.read(buffer) < 0) { throw PrivateBlobStore.unavailable(); } }
                if (channel.read(ByteBuffer.allocate(1)) != -1) { throw PrivateBlobStore.unavailable(); }
                return buffer.array();
            }
        } catch (IOException failure) { throw PrivateBlobStore.unavailable(); }
    }

    @Override public Cleanup rollback(Blob created) {
        if (!(created instanceof Local local) || local.identity() == null) { throw PrivateBlobStore.unavailable(); }
        Path path = root.resolve(local.fileId().toString());
        try {
            directory(root);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return Cleanup.REMOVED; }
            var current = attributes(path);
            if (!current.isRegularFile() || !local.identity().equals(current.fileKey())) { return Cleanup.RETAINED; }
            Files.delete(path);
            return Cleanup.REMOVED;
        } catch (IOException failure) { throw new IllegalStateException("Private rollback cleanup failed", failure); }
    }

    static void directory(Path root) throws IOException {
        Path current = root.getRoot();
        for (Path component : root) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) { throw PrivateBlobStore.unavailable(); }
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))) { throw PrivateBlobStore.unavailable(); }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
}
