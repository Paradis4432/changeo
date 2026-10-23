package ar.changeo.payments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.test.context.DynamicPropertyRegistry;

final class CurrentFinancialTestFixture {
    private static final Path FRAMEWORK_ROOT = Path.of("/home/paradis/changeo/.runtime/financial-verification-20261017-new/framework");
    private static final Path CURRENT_FRAMEWORK_ROOT = Path.of("/home/paradis/changeo/.runtime/financial-verification-20261017-new/current-integration-20261020/framework");
    private static final String DATABASE = "financial_verification_qa";
    private static final String SCHEMA = "financial_verification_app";
    private final Path root;

    private CurrentFinancialTestFixture(Path root) { this.root = root; }

    static CurrentFinancialTestFixture supplied() {
        Path root = Path.of(required("financial.verification.root")).toAbsolutePath().normalize();
        if ((!root.equals(FRAMEWORK_ROOT) && !root.equals(CURRENT_FRAMEWORK_ROOT)) || !required("financial.verification.database").equals(DATABASE)
                || !required("financial.verification.schema").equals(SCHEMA)) {
            throw new IllegalArgumentException("Explicit FV1 framework fixture identity required");
        }
        validatePrivate(root, true);
        validatePrivate(root.resolve("artifacts"), true);
        validatePrivate(root.resolve("enrollments"), true);
        validatePrivate(root.resolve("payments"), true);
        for (Path key : new Path[] {root.resolve("mfa.key"), root.resolve("payments/auth.key")}) {
            validatePrivate(key, false);
            try {
                if (Files.size(key) != 32) { throw new IllegalArgumentException("FV1 original private key required"); }
            } catch (IOException unavailable) { throw new IllegalStateException("FV1 private key unavailable", unavailable); }
        }
        return new CurrentFinancialTestFixture(root);
    }

    void register(DynamicPropertyRegistry properties, boolean payments) {
        String url = required("financial.verification.datasource.url");
        String user = required("financial.verification.datasource.username");
        String password = System.getenv("FINANCIAL_VERIFICATION_DATABASE_PASSWORD");
        if (!url.equals("jdbc:postgresql://127.0.0.1:55449/" + DATABASE) || !user.equals(DATABASE)
                || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Explicit FV1 framework datasource required");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        for (String property : new String[] {"spring.flyway.schemas", "spring.flyway.default-schema", "spring.datasource.hikari.schema", "spring.jpa.properties.hibernate.default_schema"}) {
            properties.add(property, () -> SCHEMA);
        }
        properties.add("changeo.mfa-key", () -> root.resolve("mfa.key").toString());
        properties.add("changeo.files-root", () -> root.resolve("artifacts").toString());
        properties.add("changeo.mailbox", () -> root.resolve("mailbox").toString());
        properties.add("changeo.payments-auth-key", () -> root.resolve("payments/auth.key").toString());
        properties.add("changeo.synthetic-payments", () -> Boolean.toString(payments));
        properties.add("changeo.token-delivery", () -> "local");
        properties.add("changeo.files.storage", () -> "local");
        properties.add("changeo.moderation-preparation", () -> "off");
        for (String property : new String[] {"changeo.real-money", "changeo.payments-worker", "changeo.moderation-worker", "changeo.notification-worker", "changeo.synthetic-subscriptions", "changeo.subscription-worker"}) {
            properties.add(property, () -> "false");
        }
    }

    Properties enrollment(boolean audit) throws IOException {
        Path file = root.resolve(audit ? "enrollments/qa-audit.private" : "enrollments/qa-review.private");
        validatePrivate(file, false);
        var material = new Properties();
        try (var input = Files.newBufferedReader(file)) { material.load(input); }
        for (String key : new String[] {"account", "contact", "password", "totpBase32"}) {
            if (material.getProperty(key) == null || material.getProperty(key).isBlank()) {
                material.clear();
                throw new IllegalArgumentException("Complete FV1 private enrollment required");
            }
        }
        return material;
    }

    static String database() { return DATABASE; }
    static String schema() { return SCHEMA; }

    Path evidence() {
        return Path.of("docs/agent-work/financial-verification-20261019/developer",
                root.equals(CURRENT_FRAMEWORK_ROOT) ? "current-integration/framework" : "framework").toAbsolutePath().normalize();
    }

    String contract() { return root.equals(CURRENT_FRAMEWORK_ROOT) ? "FV4" : "FV1"; }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Missing explicit caller input: " + name); }
        return value;
    }

    private static void validatePrivate(Path path, boolean directory) {
        try {
            for (Path current = path; current != null; current = current.getParent()) {
                if (Files.isSymbolicLink(current)) { throw new IllegalArgumentException("FV1 fixture symlink rejected"); }
            }
            boolean correctType = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
            int requiredMode = directory ? 0700 : 0600;
            if (!correctType || (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) != 1000
                    || ((Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0777) != requiredMode) {
                throw new IllegalArgumentException("Owned private FV1 fixture required");
            }
        } catch (IOException unavailable) { throw new IllegalStateException("FV1 fixture unavailable", unavailable); }
    }
}
