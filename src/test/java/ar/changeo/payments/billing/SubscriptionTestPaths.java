package ar.changeo.payments.billing;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

final class SubscriptionTestPaths {
    private SubscriptionTestPaths() {}

    static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Falta configuración privada explícita: " + property); }
        return value;
    }

    static String database() {
        String database = required("subscriptions.test.database");
        if (!database.matches("changeo_subscriptions_[a-z0-9_]+")) { throw new IllegalStateException("Base privada de suscripciones incompatible"); }
        return database;
    }

    static void localConfiguration() {
        String expected = "jdbc:postgresql://127.0.0.1:55432/" + database();
        if (!required("subscriptions.test.url").equals(expected)) { throw new IllegalStateException("La URL debe corresponder a la copia local explícita"); }
        fixture("subscriptions.test.root");
    }

    static Path fixture(String property) {
        Path root = privateRoot(property);
        privateFile(root.resolve("mfa.key")); privateFile(root.resolve("payments/auth.key"));
        owned(root.resolve("artifacts"), true, 0700);
        return root;
    }

    static Path privateRoot(String property) {
        Path path = Path.of(required(property));
        if (!path.isAbsolute() || !path.equals(path.normalize())) { throw new IllegalStateException("La ruta privada debe ser absoluta y exacta"); }
        owned(path, true, 0700);
        return path;
    }

    static Path privateFile(Path path) { owned(path, false, 0600); return path; }

    static Path dump() {
        Path path = Path.of(required("subscriptions.test.v8dump"));
        if (!path.isAbsolute() || !path.equals(path.normalize())) { throw new IllegalStateException("El respaldo privado requiere una ruta absoluta exacta"); }
        owned(path.getParent(), true, 0700);
        return privateFile(path);
    }

    private static void owned(Path path, boolean directory, int mode) {
        try {
            if (!path.toRealPath().equals(path.toAbsolutePath().normalize()) || Files.isSymbolicLink(path)
                    || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalStateException("Objeto privado inexistente o enlazado");
            }
            Path process = Path.of("/proc", Long.toString(ProcessHandle.current().pid()));
            if (!Objects.equals(Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS), Files.getAttribute(process, "unix:uid"))
                    || ((Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0777) != mode) {
                throw new IllegalStateException("Propiedad o permisos privados incompatibles");
            }
        } catch (IOException failure) { throw new IllegalStateException("No se pudo validar la copia privada", failure); }
    }
}
