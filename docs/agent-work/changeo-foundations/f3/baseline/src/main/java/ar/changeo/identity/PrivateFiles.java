package ar.changeo.identity;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

final class PrivateFiles {
    private PrivateFiles() {}

    static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path)) { throw new IOException("Private directory cannot be a symlink"); }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    static void writeNew(Path path, byte[] bytes) throws IOException {
        directory(path.toAbsolutePath().getParent());
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(path, bytes, StandardOpenOption.WRITE);
    }

    static byte[] read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("Private file requires owner-only access");
        }
        return Files.readAllBytes(path);
    }
}
