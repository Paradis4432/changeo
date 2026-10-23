package ar.changeo.jobs;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.OperatorService;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.jobs.api.JobSnapshot;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.moderation.api.SurfaceKind;
import ar.changeo.payments.api.JobMoneyStatus;
import ar.changeo.payments.api.JobMoneyStatusAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {ChangeoApplication.class, JobUsabilityBrowserTest.Configuration.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=true", "changeo.notification-worker=false",
                "changeo.synthetic-payments=false", "changeo.payments-worker=false", "changeo.synthetic-subscriptions=false", "changeo.subscription-worker=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobUsabilityBrowserTest {
    static final Path PRIVATE_ROOT = Path.of(".runtime/job-ui-browser-" + UUID.randomUUID());
    static final String SCHEMA = "job_ui_browser_" + UUID.randomUUID().toString().replace("-", "");
    static final Path EVIDENCE = Path.of(System.getProperty("jobui.evidence", "docs/agent-work/job-settlement-usability-20261017/developer"));
    static final String RUN = System.getProperty("jobui.browser.run", UUID.randomUUID().toString());
    @Autowired OperatorService operators;
    @Autowired JobAccess jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExactFundingFacts facts;
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
    @TestConfiguration static class Configuration {
        @Bean @Primary ExactFundingFacts exactFundingFacts() { return new ExactFundingFacts(); }
    }
    static final class ExactFundingFacts implements JobMoneyStatusAccess {
        private final Map<UUID, ContentReference> confirmed = new ConcurrentHashMap<>();
        void confirm(JobSnapshot job) {
            assertThat(job.state()).isEqualTo(JobSnapshot.State.AGREED_AWAITING_FUNDING);
            assertThat(job.legalPayer()).isPresent();
            confirmed.put(job.id(), job.agreement().orElseThrow());
        }
        @Override public JobMoneyStatus currentForUpdate(UUID job, ContentReference terms) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new JobMoneyStatus(job, terms, terms.equals(confirmed.get(job)) ? JobMoneyStatus.Funding.CONFIRMED_FULL : JobMoneyStatus.Funding.INTEGRATION_PENDING,
                    JobMoneyStatus.Release.INTEGRATION_PENDING, JobMoneyStatus.BankPayout.INTEGRATION_PENDING);
        }
    }
    @Test void shouldShowExactFinalAndRecoverFormsThroughRealBrowserWithoutMoneyEffects() throws Exception {
        Path enrollment = PRIVATE_ROOT.resolve("operator.private"), fixture = PRIVATE_ROOT.resolve("fixture.private");
        operators.provision("jobuibrowser" + UUID.randomUUID() + "@example.test", Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER"), enrollment);
        try {
            browser("setup", enrollment, fixture);
            var identifiers = Files.readAllLines(Path.of(fixture + ".ready"));
            assertThat(identifiers).hasSize(3);
            UUID jobId = UUID.fromString(identifiers.getFirst());
            var terms = new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, UUID.fromString(identifiers.get(1)), UUID.fromString(identifiers.get(2)));
            var job = jobs.discover(jobId);
            assertThat(job.agreement()).contains(terms);
            assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and revision_id=?", Integer.class, terms.resourceId(), terms.revisionId())).isEqualTo(2);
            facts.confirm(job);
            browser("workflow", enrollment, fixture);
            browser("lifecycle", enrollment, fixture);
            var moneyTables = jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'payments_%' or tablename like 'subscriptions_%') order by tablename", String.class);
            assertThat(moneyTables).isNotEmpty();
            for (String table : moneyTables) {
                assertThat(table).matches("[a-z_]+");
                assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            }
            assertThat(jobs.discover(jobId).state()).isEqualTo(JobSnapshot.State.SUPPORT_HOLD);
            assertThat(jobs.discover(jobId).feedbackCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='FEEDBACK_APPROVED'", Integer.class, jobId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='COMPLETED'", Integer.class, jobId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from jobs_authored where job_id=? and kind='REVIEW'", Integer.class, jobId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select r.score from jobs_authored a join jobs_revision r on r.id=a.approved_revision where job_id=? and kind='REVIEW'", Integer.class, jobId)).isEqualTo(5);
        } finally { facts.confirmed.clear(); }
    }
    private void browser(String mode, Path enrollment, Path fixture) throws Exception {
        assertThat(RUN).matches("[a-z0-9-]+");
        Path output = EVIDENCE.resolve("browser-" + RUN + "-" + mode + ".log");
        var process = new ProcessBuilder("node", System.getProperty("jobui.browser.harness", EVIDENCE.resolve("browser.cjs").toString()), mode).redirectErrorStream(true).redirectOutput(output.toFile());
        process.environment().put("C4_QA_BASE", "http://127.0.0.1:" + port);
        process.environment().put("C4_QA_ENROLLMENT", enrollment.toAbsolutePath().toString());
        process.environment().put("C4_QA_FIXTURE", fixture.toAbsolutePath().toString());
        process.environment().put("C4_QA_MAILBOX", PRIVATE_ROOT.resolve("mailbox").toAbsolutePath().toString());
        process.environment().put("JOBUI_SCHEMA", SCHEMA);
        process.environment().put("C4_QA_LOGS", EVIDENCE.resolve("browser-" + RUN).toString());
        int exit = process.start().waitFor();
        Files.writeString(EVIDENCE.resolve("browser-" + RUN + "-" + mode + ".exit"), exit + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThat(exit).as("Actual browser " + mode + "; inspect saved first log").isZero();
    }
}
