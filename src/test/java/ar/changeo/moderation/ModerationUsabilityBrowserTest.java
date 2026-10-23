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
class ModerationUsabilityBrowserTest {
    static final Path PRIVATE_ROOT = Path.of(".runtime/moderation-usability-browser-" + UUID.randomUUID());
    static final String SCHEMA = "moderation_ui_browser_" + UUID.randomUUID().toString().replace("-", "");
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

    @Test void shouldNavigateExactCasesRecoverSafelyAndKeepPrivateEvidenceGuardedInRealHtml() throws Exception {
        Path enrollment = PRIVATE_ROOT.resolve("operator.private");
        operators.provision("moderationuibrowser" + UUID.randomUUID() + "@example.test", Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER", "MODERATION_AUDITOR"), enrollment);
        Path evidence = Path.of(System.getProperty("moderationui.evidence", "docs/agent-work/moderation-usability-20261017/developer"));
        String run = System.getProperty("moderationui.browser.run", UUID.randomUUID().toString());
        assertThat(run).matches("[a-z0-9-]+");
        var builder = new ProcessBuilder("node", System.getProperty("moderationui.browser.harness", evidence.resolve("browser.cjs").toString()))
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
        var money = jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'payments_%' or tablename like 'subscriptions_%') order by tablename", String.class);
        assertThat(money).isNotEmpty();
        for (String table : money) {
            assertThat(table).matches("[a-z_]+");
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
        }
    }
}
