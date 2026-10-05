package ar.changeo.identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import static org.assertj.core.api.Assertions.*;

final class IdentityMigrationAssertions {
    private static final Map<String, String> SCRIPTS = Map.of(
            "1", "V1__identity.sql", "2", "V2__identity_state_integrity.sql", "3", "V3__identity_moderation_roles.sql");

    static List<MigrationRecord> expected(Flyway flyway) {
        List<MigrationRecord> records = Arrays.stream(flyway.info().all())
                .filter(info -> info.getVersion() != null && SCRIPTS.containsKey(info.getVersion().getVersion()))
                .map(info -> new MigrationRecord(info.getVersion().getVersion(), info.getScript(), info.getChecksum(), true))
                .sorted(Comparator.comparing(MigrationRecord::version)).toList();
        assertThat(records).extracting(MigrationRecord::version).containsExactly("1", "2", "3");
        records.forEach(record -> {
            assertThat(record.script()).isEqualTo(SCRIPTS.get(record.version()));
            assertThat(record.checksum()).isNotNull();
        });
        return records;
    }

    static void assertApplied(Flyway flyway, List<MigrationRecord> expected) throws SQLException {
        flyway.validate();
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setSchema(flyway.getConfiguration().getDefaultSchema());
            assertThat(history(connection)).containsAll(expected);
        }
    }

    static void assertPopulatedUpgrade(DataSource source) throws SQLException {
        String schema = "f3_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway old = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("2").load();
        assertThat(old.migrate().migrationsExecuted).isPositive();
        old.validate();
        UUID account = UUID.randomUUID(); UUID token = UUID.randomUUID(); UUID event = UUID.randomUUID();
        List<MigrationRecord> historyBefore;
        Map<String, String> keysBefore;
        long tableBefore;
        try {
            try (Connection connection = source.getConnection()) {
                connection.setSchema(schema);
                historyBefore = history(connection);
                assertThat(historyBefore).extracting(MigrationRecord::version).containsExactly("1", "2");
                keysBefore = keys(connection); tableBefore = roleTable(connection);
                try (var insert = connection.prepareStatement("insert into identity_account(id,contact,password_hash,contact_verified,security_epoch,contact_generation,recovery_generation,sensitive_preference,adult_preference,version) values (?,?,?,true,7,8,9,true,true,10)")) {
                    insert.setObject(1, account); insert.setString(2, "migration@example.test"); insert.setString(3, "synthetic-fixture-hash"); insert.executeUpdate();
                }
                for (String role : List.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "AUDIT_READER")) { insertRole(connection, account, role); }
                try (var insert = connection.prepareStatement("insert into identity_contact_token values (?,?,'CONTACT',?,8,now()+interval '30 minutes',false)")) {
                    insert.setObject(1, token); insert.setObject(2, account); insert.setString(3, AccountService.hash("synthetic-migration-fixture")); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("insert into identity_audit_event values (?, ?, ?, 'MIGRATION_FIXTURE', now(), 'SANDBOX_SYNTHETIC')")) {
                    insert.setObject(1, event); insert.setObject(2, account); insert.setObject(3, account); insert.executeUpdate();
                }
                assertThatThrownBy(() -> insertRole(connection, account, "MODERATION_REVIEWER")).isInstanceOf(SQLException.class);
            }
            Flyway current = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();
            List<MigrationRecord> expected = expected(current);
            assertThat(current.migrate().migrationsExecuted).isPositive();
            assertApplied(current, expected);
            assertThat(current.migrate().migrationsExecuted).isZero();
            try (Connection connection = source.getConnection()) {
                connection.setSchema(schema);
                assertThat(history(connection)).containsAll(historyBefore);
                assertThat(keys(connection)).isEqualTo(keysBefore);
                assertThat(roleTable(connection)).isEqualTo(tableBefore);
                try (var query = connection.prepareStatement("select contact, password_hash, contact_verified, security_epoch, contact_generation, recovery_generation, sensitive_preference, adult_preference, version from identity_account where id=?")) {
                    query.setObject(1, account);
                    try (var row = query.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo("migration@example.test");
                        assertThat(row.getString(2)).isEqualTo("synthetic-fixture-hash");
                        assertThat(row.getBoolean(3)).isTrue();
                        assertThat(row.getLong(4)).isEqualTo(7); assertThat(row.getLong(5)).isEqualTo(8); assertThat(row.getLong(6)).isEqualTo(9);
                        assertThat(row.getBoolean(7)).isTrue(); assertThat(row.getBoolean(8)).isTrue(); assertThat(row.getLong(9)).isEqualTo(10);
                    }
                }
                assertThat(roles(connection, account)).containsExactly("AUDIT_READER", "IDENTITY_ADMIN", "RESTRICTION_ADMIN");
                insertRole(connection, account, "MODERATION_REVIEWER"); insertRole(connection, account, "MODERATION_AUDITOR");
                assertThat(roles(connection, account)).containsExactly("AUDIT_READER", "IDENTITY_ADMIN", "MODERATION_AUDITOR", "MODERATION_REVIEWER", "RESTRICTION_ADMIN");
                assertThatThrownBy(() -> insertRole(connection, account, "MODERATION_ADMIN")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertRole(connection, account, "moderation_reviewer")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertRole(connection, account, "MODERATION_REVIEWER")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertRole(connection, UUID.randomUUID(), "MODERATION_AUDITOR")).isInstanceOf(SQLException.class);
                try (var statement = connection.createStatement(); var row = statement.executeQuery("select (select count(*) from identity_contact_token where generation=8 and not consumed), (select count(*) from identity_audit_event where action='MIGRATION_FIXTURE')")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(1); assertThat(row.getInt(2)).isEqualTo(1);
                }
            }
        } finally {
            try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static List<MigrationRecord> history(Connection connection) throws SQLException {
        List<MigrationRecord> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select version,script,checksum,success from flyway_schema_history where version in ('1','2','3') order by version")) {
            while (rows.next()) { result.add(new MigrationRecord(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getBoolean(4))); }
        }
        return result;
    }

    private static Map<String, String> keys(Connection connection) throws SQLException {
        Map<String, String> result = new TreeMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select conname,pg_get_constraintdef(oid) from pg_constraint where conrelid='identity_account_role'::regclass and contype in ('p','f') order by conname")) {
            while (rows.next()) { result.put(rows.getString(1), rows.getString(2)); }
        }
        return result;
    }

    private static long roleTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("select 'identity_account_role'::regclass::oid")) {
            assertThat(row.next()).isTrue(); return row.getLong(1);
        }
    }

    private static List<String> roles(Connection connection, UUID account) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var query = connection.prepareStatement("select role from identity_account_role where account_id=? order by role")) {
            query.setObject(1, account);
            try (var rows = query.executeQuery()) { while (rows.next()) { result.add(rows.getString(1)); } }
        }
        return result;
    }

    private static void insertRole(Connection connection, UUID account, String role) throws SQLException {
        try (var insert = connection.prepareStatement("insert into identity_account_role values (?,?)")) {
            insert.setObject(1, account); insert.setString(2, role); insert.executeUpdate();
        }
    }

    record MigrationRecord(String version, String script, Integer checksum, boolean success) {}
}
