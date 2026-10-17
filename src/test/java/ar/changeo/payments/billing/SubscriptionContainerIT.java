package ar.changeo.payments.billing;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SubscriptionContainerIT extends SubscriptionBillingPostgresTest {
    static final Path FIXTURE = SubscriptionTestPaths.fixture("subscriptions.container.root");
    static final Path DUMP = SubscriptionTestPaths.dump();
    static final Path RECEIPTS = SubscriptionTestPaths.privateRoot("subscriptions.test.receipts");
    @Container static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse("docker.io/library/postgres:18.0").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(SubscriptionTestPaths.database())
            .withUsername("changeo").withPassword(UUID.randomUUID().toString())
            .withCopyFileToContainer(MountableFile.forHostPath(DUMP, 0600), "/tmp/subscriptions-v8.dump")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0), new com.github.dockerjava.api.model.ExposedPort(5432))));

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        database.start();
        assertThat(database.execInContainer("pg_restore", "-U", database.getUsername(), "-d", database.getDatabaseName(), "--exit-on-error", "/tmp/subscriptions-v8.dump").getExitCode()).isZero();
        assertThat(database.execInContainer("rm", "/tmp/subscriptions-v8.dump").getExitCode()).isZero();
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword()); var statement = connection.createStatement()) {
            connection.setSchema("sandbox");
            try (var rows = statement.executeQuery("show server_version")) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).startsWith("18."); }
            var tables = new TreeSet<String>();
            try (var rows = statement.executeQuery("select tablename from pg_tables where schemaname='sandbox' order by tablename")) { while (rows.next()) { tables.add(rows.getString(1)); } }
            assertThat(tables).hasSize(73);
            try (var rows = statement.executeQuery("select count(*) from payments_root")) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isPositive(); }
            var retained = new TreeMap<String, String>();
            for (String table : tables) { retained.put(table, fingerprint(statement, table, null)); }
            int historyRank;
            try (var rows = statement.executeQuery("select max(installed_rank) from flyway_schema_history")) { assertThat(rows.next()).isTrue(); historyRank = rows.getInt(1); }
            var migration = Flyway.configure().schemas("sandbox").defaultSchema("sandbox").dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).load();
            var before = migration.info().applied(); assertThat(before).filteredOn(value -> value.getVersion() != null).hasSize(8);
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1); migration.validate(); assertThat(migration.migrate().migrationsExecuted).isZero();
            for (String table : tables) { assertThat(fingerprint(statement, table, table.equals("flyway_schema_history") ? historyRank : null)).as("Full populated V8 rows: " + table).isEqualTo(retained.get(table)); }
            for (var original : before) {
                var after = Arrays.stream(migration.info().applied()).filter(value -> value.getScript().equals(original.getScript())).findFirst().orElseThrow();
                assertThat(after.getVersion()).isEqualTo(original.getVersion()); assertThat(after.getChecksum()).isEqualTo(original.getChecksum());
            }
            String run = SubscriptionTestPaths.required("subscriptions.test.run"); assertThat(run).matches("[a-z0-9-]+");
            Path receipt = RECEIPTS.resolve("populated-v8-upgrade-" + run + ".json");
            Files.writeString(receipt, "{\"actualPostgreSQL18\":true,\"fullPopulatedV8Tables\":73,\"allOriginalRowsAndEightChecksumsPreserved\":true,\"additiveMigrations\":1,\"repeatMigrations\":0,\"privateDumpRemovedInsideContainer\":true}\n", StandardOpenOption.CREATE_NEW);
        }
        properties.add("spring.datasource.url", database::getJdbcUrl); properties.add("spring.datasource.username", database::getUsername); properties.add("spring.datasource.password", database::getPassword);
        properties.add("changeo.mfa-key", () -> FIXTURE.resolve("mfa.key").toString());
        properties.add("changeo.files-root", () -> FIXTURE.resolve("artifacts").toString());
        properties.add("changeo.payments-auth-key", () -> FIXTURE.resolve("payments/auth.key").toString());
    }

    private static String fingerprint(Statement statement, String table, Integer originalHistoryRank) throws Exception {
        assertThat(table).matches("[a-z0-9_]+");
        try (var rows = statement.executeQuery("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text,'[]') from (select * from \"" + table + "\"" + (originalHistoryRank == null ? "" : " where installed_rank<=" + originalHistoryRank) + ") t")) {
            assertThat(rows.next()).isTrue(); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.getString(1).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
