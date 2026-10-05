package ar.changeo.moderation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ModerationContainerIT extends ModerationPostgresTest {
    @Container static final PostgreSQLContainer database=new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("docker.io/library/postgres:18.0").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(command->command.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(5432))));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",database::getJdbcUrl);properties.add("spring.datasource.username",database::getUsername);properties.add("spring.datasource.password",database::getPassword);
    }
    @Test void shouldApplyModerationMigrationValidateAndRepeatWithoutEffects() {
        var flyway=Flyway.configure().schemas("moderation_migration_contract").defaultSchema("moderation_migration_contract").dataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(4);flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(java.util.Arrays.stream(flyway.info().applied()).filter(row->row.getVersion()!=null && row.getVersion().getVersion().equals("4")).map(row->row.getScript())).containsExactly("V4__moderation_and_private_files.sql");
    }
}
