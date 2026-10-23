package ar.changeo.moderation;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.OperatorService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ChangeoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=true",
                "changeo.notification-worker=false", "changeo.synthetic-payments=false", "changeo.payments-worker=false",
                "changeo.synthetic-subscriptions=false", "changeo.subscription-worker=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkbenchDraftRecoveryBrowserTest {
    static final Path PRIVATE_ROOT = Path.of(".runtime/workbench-recovery-browser-" + UUID.randomUUID());
    static final String SCHEMA = "workbench_recovery_browser_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired OperatorService operators;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @DynamicPropertySource static void runtime(DynamicPropertyRegistry properties) throws Exception {
        Files.createDirectories(PRIVATE_ROOT.getParent());
        Files.createDirectory(PRIVATE_ROOT, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        properties.add("changeo.mfa-key", () -> PRIVATE_ROOT.resolve("mfa.key").toString());
        properties.add("changeo.files-root", () -> PRIVATE_ROOT.resolve("artifacts").toString());
        properties.add("changeo.mailbox", () -> PRIVATE_ROOT.resolve("mailbox").toString());
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Test void shouldRecoverDraftFailuresAndRealServletOversizeWithoutEffects() throws Exception {
        Path enrollment = PRIVATE_ROOT.resolve("operator.private");
        operators.provision("moderationuibrowser" + UUID.randomUUID() + "@example.test", Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER", "MODERATION_AUDITOR"), enrollment);
        Path evidence = Path.of(System.getProperty("workbench.evidence", "docs/agent-work/workbench-draft-recovery-20261018/developer"));
        String run = System.getProperty("workbench.browser.run", UUID.randomUUID().toString());
        assertThat(run).matches("[a-z0-9-]+");
        var builder = new ProcessBuilder("node", System.getProperty("workbench.browser.harness", evidence.resolve("browser.cjs").toString()))
                .redirectErrorStream(true).redirectOutput(evidence.resolve("browser-" + run + ".log").toFile());
        builder.environment().put("C4_QA_BASE", "http://127.0.0.1:" + port);
        builder.environment().put("C4_QA_ENROLLMENT", enrollment.toAbsolutePath().toString());
        builder.environment().put("C4_QA_MAILBOX", PRIVATE_ROOT.resolve("mailbox").toAbsolutePath().toString());
        builder.environment().put("C4_QA_FIXTURE", PRIVATE_ROOT.resolve("fixture.private").toAbsolutePath().toString());
        builder.environment().put("C4_QA_LOGS", evidence.resolve("browser-" + run).toString());
        var process = builder.start();
        Files.writeString(evidence.resolve("browser-" + run + ".pid"), process.pid() + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        int exit = process.waitFor();
        Files.writeString(evidence.resolve("browser-" + run + ".exit"), exit + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThat(exit).as("Actual browser; inspect saved first log").isZero();
        assertThat(jdbc.queryForObject("select count(*) from workbench_resource",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from workbench_receipt",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from workbench_member",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from files_artifact",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workbench_attachment",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from moderation_task",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from workbench_resource where approved_revision is not null",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision where text in ('unreadable parser caller','No-JS caller recovery','forbidden stale secret echo')",Long.class)).isZero();
        try(var artifacts=Files.list(PRIVATE_ROOT.resolve("artifacts"))) {
            var paths=artifacts.toList();assertThat(paths).hasSize(1);assertThat(Files.readString(paths.getFirst())).isEqualTo("synthetic:clear");
        }
        var money = jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'payments_%' or tablename like 'subscriptions_%') order by tablename", String.class);
        assertThat(money).isNotEmpty();
        for (String table : money) {
            assertThat(table).matches("[a-z_]+");
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
        }
    }
}
