package ar.changeo.payments;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PaymentContainerIT extends PaymentPostgresTest {
    @Container static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse("docker.io/library/postgres:18.0").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(System.getProperty("payments.test.database", "changeo_payments_01a11313"))
            .withUsername("changeo").withPassword(UUID.randomUUID().toString())
            .withCopyFileToContainer(MountableFile.forHostPath("/tmp/changeo-payments-01a11313/sandbox.dump", 0600), "/tmp/payments-original.dump")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0), new com.github.dockerjava.api.model.ExposedPort(5432))));

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        database.start();
        var restoration = database.execInContainer("pg_restore", "-U", database.getUsername(), "-d", database.getDatabaseName(), "--exit-on-error", "/tmp/payments-original.dump");
        assertThat(restoration.getExitCode()).as("Exact private V7 snapshot restoration").isZero();
        assertThat(database.execInContainer("rm", "/tmp/payments-original.dump").getExitCode()).isZero();
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword()); var statement = connection.createStatement()) {
            connection.setSchema("sandbox");
            try (var rows = statement.executeQuery("show server_version")) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).startsWith("18."); }
            Map<String, List<String>> columns = new TreeMap<>();
            try (var rows = statement.executeQuery("select table_name,column_name from information_schema.columns where table_schema='sandbox' order by table_name,ordinal_position")) {
                while (rows.next()) { columns.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(rows.getString(2)); }
            }
            Map<String, String> retained = new TreeMap<>();
            for (var table : columns.entrySet()) { retained.put(table.getKey(), fingerprint(statement, table.getKey(), table.getValue(), null)); }
            int historyRank;
            try (var rows = statement.executeQuery("select max(installed_rank) from flyway_schema_history")) { assertThat(rows.next()).isTrue(); historyRank = rows.getInt(1); }
            var migration = Flyway.configure().schemas("sandbox").defaultSchema("sandbox").dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).load();
            var originalHistory = migration.info().applied();
            assertThat(originalHistory).filteredOn(row -> row.getVersion() != null).hasSize(7);
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(2); migration.validate();
            assertThat(migration.migrate().migrationsExecuted).isZero();
            for (var table : columns.entrySet()) {
                assertThat(fingerprint(statement, table.getKey(), table.getValue(), table.getKey().equals("flyway_schema_history") ? historyRank : null))
                        .as("All original columns/rows of " + table.getKey()).isEqualTo(retained.get(table.getKey()));
            }
            for (var before : originalHistory) {
                var after = Arrays.stream(migration.info().applied()).filter(row -> row.getScript().equals(before.getScript())).findFirst().orElseThrow();
                assertThat(after.getVersion()).isEqualTo(before.getVersion()); assertThat(after.getChecksum()).isEqualTo(before.getChecksum());
            }
            try (var rows = statement.executeQuery("select count(*) from jobs_job where pre_hold_state is not null")) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero(); }
            String run = System.getProperty("cfr.browser.run", "focused");
            assertThat(run).matches("[a-z0-9-]+");
            Path receipt = Path.of("docs/agent-work/changeo-payments/developer/verification/populated-v7-container-upgrade-" + run + ".json");
            Files.createDirectories(receipt.getParent());
            Files.writeString(receipt, "{\"actualPostgreSQL18\":true,\"originalTables\":" + columns.size() + ",\"fullOriginalColumnRowsPreserved\":true,\"originalSevenChecksumsPreserved\":true,\"additiveMigrationCount\":2,\"repeatMigrationCount\":0,\"privateDumpRemovedInsideOwnedContainer\":true}\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
    }

    private static String fingerprint(Statement statement, String table, List<String> columns, Integer originalHistoryRank) throws Exception {
        assertThat(table).matches("[a-z0-9_]+");
        for (String column : columns) { assertThat(column).matches("[a-z0-9_]+"); }
        String projection = columns.stream().map(column -> "\"" + column + "\"").collect(java.util.stream.Collectors.joining(","));
        String retainedHistory = "";
        if (originalHistoryRank != null) { assertThat(table).isEqualTo("flyway_schema_history"); retainedHistory = " where installed_rank<=" + originalHistoryRank; }
        try (var rows = statement.executeQuery("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text,'[]') from (select " + projection + " from \"" + table + "\"" + retainedHistory + ") t")) {
            assertThat(rows.next()).isTrue();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.getString(1).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
