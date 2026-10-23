package ar.changeo.jobs;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.OperatorService;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.jobs.api.JobSnapshot;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
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
        properties = {"changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=true", "changeo.notification-worker=false",
                "changeo.synthetic-payments=false", "changeo.payments-worker=false", "changeo.synthetic-subscriptions=false", "changeo.subscription-worker=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobEvidenceHistoryBrowserTest {
    static final Path PRIVATE_ROOT = Path.of(".runtime/job-history-browser-" + UUID.randomUUID());
    static final String SCHEMA = "job_history_browser_" + UUID.randomUUID().toString().replace("-", "");
    static final Path EVIDENCE = Path.of(System.getProperty("jobhistory.evidence", "docs/agent-work/job-evidence-history-20261017/developer"));
    static final String RUN = System.getProperty("jobhistory.browser.run", UUID.randomUUID().toString());
    @Autowired OperatorService operators;
    @Autowired JobAccess jobs;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @DynamicPropertySource static void privateRuntime(DynamicPropertyRegistry properties) throws Exception {
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
    @Test void shouldNavigateCurrentAndRetainedEvidenceWithoutJavaScriptOrMoneyEffects() throws Exception {
        Path enrollment = PRIVATE_ROOT.resolve("operator.private"), fixture = PRIVATE_ROOT.resolve("fixture.private");
        operators.provision("jobhistorybrowser" + UUID.randomUUID() + "@example.test", Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER"), enrollment);
        browser("setup", enrollment, fixture);
        var identifiers = Files.readAllLines(Path.of(fixture + ".ready"));
        assertThat(identifiers).hasSize(3);
        UUID jobId = UUID.fromString(identifiers.getFirst());
        var terms = new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, UUID.fromString(identifiers.get(1)), UUID.fromString(identifiers.get(2)));
        var job = jobs.discover(jobId);
        assertThat(job.agreement()).contains(terms);
        assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and revision_id=?", Integer.class, terms.resourceId(), terms.revisionId())).isEqualTo(2);

        browser("workflow", enrollment, fixture);

        var moneyTables = jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'payments_%' or tablename like 'subscriptions_%') order by tablename", String.class);
        assertThat(moneyTables).isNotEmpty();
        for (String table : moneyTables) {
            assertThat(table).matches("[a-z_]+");
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
        }
        assertThat(jobs.discover(jobId).state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
        assertThat(jdbc.queryForObject("select count(*) from jobs_authored where job_id=? and approved_revision is not null", Integer.class, jobId)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select count(*) from jobs_authored where job_id=?", Integer.class, jobId)).isEqualTo(12);
    }
    private void browser(String mode, Path enrollment, Path fixture) throws Exception {
        assertThat(RUN).matches("[a-z0-9-]+");
        Path output = EVIDENCE.resolve("browser-" + RUN + "-" + mode + ".log");
        var process = new ProcessBuilder("node", System.getProperty("jobhistory.browser.harness", EVIDENCE.resolve("browser.cjs").toString()), mode).redirectErrorStream(true).redirectOutput(output.toFile());
        process.environment().put("C4_QA_BASE", "http://127.0.0.1:" + port);
        process.environment().put("C4_QA_ENROLLMENT", enrollment.toAbsolutePath().toString());
        process.environment().put("C4_QA_FIXTURE", fixture.toAbsolutePath().toString());
        process.environment().put("C4_QA_MAILBOX", PRIVATE_ROOT.resolve("mailbox").toAbsolutePath().toString());
        process.environment().put("JOBUI_SCHEMA", SCHEMA);
        process.environment().put("C4_QA_LOGS", EVIDENCE.resolve("browser-" + RUN).toString());
        var started = process.start();
        Files.writeString(EVIDENCE.resolve("browser-" + RUN + "-" + mode + ".pid"), started.pid() + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        int exit = started.waitFor();
        Files.writeString(EVIDENCE.resolve("browser-" + RUN + "-" + mode + ".exit"), exit + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThat(exit).as("Actual browser " + mode + "; inspect saved first log").isZero();
    }
}
