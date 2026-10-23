package ar.changeo.conversations;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ConversationsContainerIT extends ConversationJourneyPostgresTest {
    @Container static final PostgreSQLContainer database = new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("docker.io/library/postgres:18.0").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0), new com.github.dockerjava.api.model.ExposedPort(5432))));

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
    }

    @Test void shouldUpgradePopulatedV5WithExactHistoryChecksumsAndRepeatZero() throws Exception {
        String schema = "conversations_upgrade";
        var original = migration(schema, "5");
        assertThat(original.migrate().migrationsExecuted).isEqualTo(5);
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                var statement = connection.createStatement()) {
            connection.setSchema(schema);
            statement.executeUpdate("insert into identity_account(id,contact,password_hash,contact_verified,security_epoch,version) values('00000000-0000-0000-0000-000000000001','c4upgrade@example.test','synthetic-hash',true,7,3)");
            statement.executeUpdate("insert into identity_audit_event values('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','SYNTHETIC_UPGRADE_FIXTURE','2026-10-01','SANDBOX_SYNTHETIC')");
            statement.executeUpdate("insert into marketplace_resource(id,subject_id,creator_id,type) values('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','REQUEST')");
            statement.executeUpdate("insert into marketplace_revision(id,resource_id,actor_id,subject_id,capability,title,body,tags,province,area,modes,availability,badge_opt_in,review_text,declared_label,digest,created_at) values('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','DRAFT_REQUEST','Pedido sintético retenido','Alcance anterior sin aprobar',array['restauración'],'Buenos Aires','La Plata',array['REMOTE'],'A coordinar',false,'Whole synthetic draft','GENERAL',repeat('a',64),'2026-10-01')");
            statement.executeUpdate("update marketplace_resource set current_revision='00000000-0000-0000-0000-000000000004',version=1 where id='00000000-0000-0000-0000-000000000003'");
            statement.executeUpdate("insert into marketplace_receipt values('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000005','SAVE',repeat('b',64),'00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004')");
            List<String> tables = new ArrayList<>();
            try (var rows = statement.executeQuery("select tablename from pg_tables where schemaname=current_schema() and tablename<>'flyway_schema_history' order by tablename")) {
                while (rows.next()) { tables.add(rows.getString(1)); }
            }
            Map<String, String> retained = new LinkedHashMap<>();
            for (String table : tables) { retained.put(table, snapshot(statement, table)); }
            var history = original.info().applied();
            var upgraded = migration(schema, null);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(7); upgraded.validate();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            for (var row : retained.entrySet()) { assertThat(snapshot(statement, row.getKey())).as(row.getKey()).isEqualTo(row.getValue()); }
            for (var before : history) {
                var after = Arrays.stream(upgraded.info().applied()).filter(row -> row.getScript().equals(before.getScript())).findFirst().orElseThrow();
                assertThat(after.getVersion()).isEqualTo(before.getVersion()); assertThat(after.getChecksum()).isEqualTo(before.getChecksum());
            }
            assertThatThrownBy(() -> statement.executeUpdate("update marketplace_revision set body='rewritten'")).isInstanceOf(SQLException.class);
            try (var rows = statement.executeQuery("select count(*) from jobs_job")) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero(); }
        }
        var fresh = migration("conversations_fresh", null);
        assertThat(fresh.migrate().migrationsExecuted).isEqualTo(12); fresh.validate(); assertThat(fresh.migrate().migrationsExecuted).isZero();
    }

    private Flyway migration(String schema, String target) {
        var configuration = Flyway.configure().schemas(schema).defaultSchema(schema).dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        if (target != null) { configuration.target(target); }
        return configuration.load();
    }
    private String snapshot(Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("select jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text from " + table + " t")) {
            assertThat(rows.next()).isTrue(); return rows.getString(1);
        }
    }
}
