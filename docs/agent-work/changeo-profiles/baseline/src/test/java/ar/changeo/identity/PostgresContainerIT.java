package ar.changeo.identity;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresContainerIT extends IdentityPostgresTest {
    @Container static final PostgreSQLContainer database = new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("docker.io/library/postgres:18.0").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            new com.github.dockerjava.api.model.ExposedPort(5432))));

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
    }

    @Test void shouldMigratePostgresTwiceAndEnforceAuditImmutability() throws Exception {
        Flyway flyway = Flyway.configure().schemas("migration_contract").defaultSchema("migration_contract").dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).load();
        var expectedIdentity = IdentityMigrationAssertions.expected(flyway);
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        IdentityMigrationAssertions.assertApplied(flyway, expectedIdentity);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
             var statement = connection.createStatement()) {
            connection.setSchema("migration_contract");
            assertThat(statement.executeQuery("select count(*) from identity_account").next()).isTrue();
            statement.executeUpdate("insert into identity_audit_event values (gen_random_uuid(),null,null,'TEST',now(),'SANDBOX_SYNTHETIC')");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate("update identity_audit_event set action='CHANGED'"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
