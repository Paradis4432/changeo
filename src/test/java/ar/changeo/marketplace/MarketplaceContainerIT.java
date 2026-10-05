package ar.changeo.marketplace;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MarketplaceContainerIT extends MarketplacePostgresTest {
    @Container static final PostgreSQLContainer database=new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("docker.io/library/postgres:18.0").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(5432))));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",database::getJdbcUrl); properties.add("spring.datasource.username",database::getUsername); properties.add("spring.datasource.password",database::getPassword);
    }
    @Test void shouldApplyValidateRepeatAndUpgradePopulatedV4WithoutChangingHistory() throws Exception {
        String schema="profiles_upgrade";
        var before=Flyway.configure().schemas(schema).defaultSchema(schema).target("4").dataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()).load();
        before.migrate();
        try (var connection=java.sql.DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword()); var statement=connection.createStatement()) {
            statement.execute("set search_path to "+schema);
            statement.execute("insert into identity_account(id,contact,password_hash) values('00000000-0000-0000-0000-000000000001','p1upgrade@example.test','synthetic-hash')");
            statement.execute("insert into workbench_resource(id,kind,actor_id,subject_id,capability,audience) values('00000000-0000-0000-0000-000000000002','REQUEST_BODY','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','NEW_REQUEST','PUBLIC')");
            statement.execute("update identity_account set contact_verified=true,security_epoch=7,version=3 where id='00000000-0000-0000-0000-000000000001'");
            statement.execute("insert into identity_account_role values('00000000-0000-0000-0000-000000000001','MODERATION_REVIEWER')");
            statement.execute("insert into identity_verification_evidence(id,account_id,kind,age,expires_at,scope,provenance,created_at) values('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','AGE',30,'2027-01-01','SANDBOX_SYNTHETIC','LOCAL_SYNTHETIC_OPERATOR:fixture','2026-10-01')");
            statement.execute("insert into identity_audit_event values('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','SYNTHETIC_UPGRADE_FIXTURE','2026-10-01','SANDBOX_SYNTHETIC')");
            statement.execute("insert into workbench_revision values('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000002','synthetic:clear','GENERAL',repeat('a',64),'2026-10-01')");
            statement.execute("insert into workbench_member values('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001')");
            statement.execute("insert into files_artifact values('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000005','REQUEST_BODY',repeat('b',64),'text/plain',15,true,'SAFE','2026-10-01')");
            statement.execute("insert into workbench_attachment values('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006',repeat('b',64))");
            statement.execute("insert into moderation_reference values('00000000-0000-0000-0000-000000000008','REQUEST_BODY','00000000-0000-0000-0000-000000000002')");
            statement.execute("insert into moderation_snapshot values('00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','NEW_REQUEST',null,null,'synthetic:clear','GENERAL',repeat('a',64),'2026-10-01')");
            statement.execute("insert into moderation_snapshot_attachment values('00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000006',repeat('b',64))");
            statement.execute("insert into moderation_submission(id,state,label,reason) values('00000000-0000-0000-0000-000000000009','APPROVED','GENERAL','MANUAL')");
            statement.execute("insert into moderation_decision values('00000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000001','APPROVE','GENERAL','MANUAL','fixture-policy','fixture-model','LOCAL_SYNTHETIC','2026-10-01')");
            statement.execute("update moderation_submission set decision_id='00000000-0000-0000-0000-000000000010',generation=2,version=4 where id='00000000-0000-0000-0000-000000000009'");
            statement.execute("update workbench_resource set current_revision='00000000-0000-0000-0000-000000000005',approved_revision='00000000-0000-0000-0000-000000000005',version=2 where id='00000000-0000-0000-0000-000000000002'");
            statement.execute("insert into moderation_task(id,submission_id,reference_id,kind,generation,state,available_at,reason,decision_id) values('00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000008','ACTIVATE',2,'COMPLETE','2026-10-01','ACTIVATED','00000000-0000-0000-0000-000000000010')");
            statement.execute("insert into moderation_signal values('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000010','ACTIVATED',2,'2026-10-01')");
            statement.execute("insert into moderation_case(id,reference_id,submission_id,reporter_id,kind,reason,note,state,version,created_at) values('00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000001','REPORT','SAFETY','synthetic upgrade case','OPEN',3,'2026-10-01')");
            statement.execute("insert into moderation_audit values('00000000-0000-0000-0000-000000000014','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000013','APPROVE','2026-10-01')");
            var retainedTables=java.util.List.of("identity_account","identity_account_role","identity_verification_evidence","identity_audit_event","workbench_resource","workbench_revision","workbench_member","files_artifact","workbench_attachment","moderation_reference","moderation_snapshot","moderation_snapshot_attachment","moderation_submission","moderation_decision","moderation_task","moderation_signal","moderation_case","moderation_audit");
            var retained=new java.util.LinkedHashMap<String,String>();
            for (String table:retainedTables) { retained.put(table,snapshot(statement,table)); }
            var histories=before.info().applied();
            var upgrade=Flyway.configure().schemas(schema).defaultSchema(schema).dataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1); upgrade.validate(); assertThat(upgrade.migrate().migrationsExecuted).isZero();
            for (var entry:retained.entrySet()) { assertThat(snapshot(statement,entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue()); }
            for (var history:histories) { var actual=java.util.Arrays.stream(upgrade.info().applied()).filter(row -> java.util.Objects.equals(row.getVersion(),history.getVersion()) && row.getScript().equals(history.getScript())).findFirst().orElseThrow(); assertThat(actual.getChecksum()).isEqualTo(history.getChecksum()); assertThat(actual.getScript()).isEqualTo(history.getScript()); }
            try (var rows=statement.executeQuery("select count(*) from workbench_resource where id='00000000-0000-0000-0000-000000000002'")) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1); }
        }
        var fresh=Flyway.configure().schemas("profiles_fresh").defaultSchema("profiles_fresh").dataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()).load();
        assertThat(fresh.migrate().migrationsExecuted).isEqualTo(5); fresh.validate(); assertThat(fresh.migrate().migrationsExecuted).isZero();
    }
    String snapshot(java.sql.Statement statement,String table) throws java.sql.SQLException {
        try (var rows=statement.executeQuery("select jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text from "+table+" t")) {
            assertThat(rows.next()).isTrue(); return rows.getString(1);
        }
    }
}
