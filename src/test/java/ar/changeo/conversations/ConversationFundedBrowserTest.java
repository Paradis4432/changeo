package ar.changeo.conversations;

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

@SpringBootTest(classes = {ChangeoApplication.class, ConversationFundedBrowserTest.Configuration.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.schemas=conversations_browser_test", "spring.flyway.default-schema=conversations_browser_test",
                "spring.jpa.properties.hibernate.default_schema=conversations_browser_test", "spring.datasource.hikari.schema=conversations_browser_test",
                "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=true", "changeo.notification-worker=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConversationFundedBrowserTest {
    static final Path PRIVATE_ROOT = Path.of(".runtime/cfr-funded-browser-" + UUID.randomUUID());
    static final String SCHEMA = "conversations_browser_" + UUID.randomUUID().toString().replace("-", "");
    static final String RUN = System.getProperty("cfr.browser.run", UUID.randomUUID().toString());
    static final Path EVIDENCE = Path.of("docs/agent-work/changeo-conversations/developer/corrections/cfr7");
    @Autowired OperatorService operators;
    @Autowired JobAccess jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExactFundingFacts facts;
    @LocalServerPort int port;
    @Autowired org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext application;

    @DynamicPropertySource static void privateRuntime(DynamicPropertyRegistry properties) throws Exception {
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
    @Test void shouldCorrectFinalDeliveryAndUniqueReviewThroughRealBrowserWithExactTestOnlyFunding() throws Exception {
        var server = (org.springframework.boot.tomcat.TomcatWebServer) application.getWebServer();
        var protocol = (org.apache.coyote.http11.AbstractHttp11Protocol<?>) server.getTomcat().getConnector().getProtocolHandler();
        assertThat(protocol.getMaxSwallowSize()).isEqualTo(6 * 1024 * 1024);
        Files.writeString(EVIDENCE.resolve("connector-bound-" + RUN + ".json"), "{\"actualMaxSwallowBytes\":" + protocol.getMaxSwallowSize()
                + ",\"acceptedMultipartBytes\":5242880,\"perFileBytes\":1048576,\"scope\":\"Actual test-owned Tomcat connector, no property override\"}\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.writeString(EVIDENCE.resolve("funded-browser-runtime-" + RUN + ".json"), "{\"pid\":" + ProcessHandle.current().pid() + ",\"port\":" + port
                + ",\"privateRoot\":\"" + PRIVATE_ROOT + "\",\"scope\":\"Owned test JVM and exact loopback test port; private bytes and arguments excluded\"}\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        Path enrollment = PRIVATE_ROOT.resolve("operator.private"), fixture = PRIVATE_ROOT.resolve("fixture.private");
        operators.provision("cfrbrowser" + UUID.randomUUID() + "@example.test", Set.of("IDENTITY_ADMIN", "RESTRICTION_ADMIN", "MODERATION_REVIEWER"), enrollment);
        try {
            browser("--funded-setup", "funded-setup", enrollment, fixture);
            var identifiers = Files.readAllLines(Path.of(fixture + ".ready"));
            assertThat(identifiers).hasSize(3);
            UUID jobId = UUID.fromString(identifiers.get(0));
            var terms = new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, UUID.fromString(identifiers.get(1)), UUID.fromString(identifiers.get(2)));
            var job = jobs.discover(jobId);
            assertThat(job.agreement()).contains(terms);
            assertThat(jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and revision_id=?", Integer.class, terms.resourceId(), terms.revisionId())).isEqualTo(2);
            facts.confirm(job);
            browser("--funded-workflow", "funded-workflow", enrollment, fixture);
            browser("--upload-check", "funded-upload", enrollment, fixture);
            assertThat(jobs.discover(jobId).state()).isEqualTo(JobSnapshot.State.COMPLETED);
            assertThat(jdbc.queryForObject("select count(*) from jobs_authored where job_id=? and kind='REVIEW'", Integer.class, jobId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select r.score from jobs_authored a join jobs_revision r on r.id=a.approved_revision where a.job_id=? and a.kind='REVIEW'", Integer.class, jobId)).isEqualTo(5);
            var privacy = new ProcessBuilder("node", EVIDENCE.resolve("privacy-funded.cjs").toString(), enrollment.toString(), fixture.toString(), RUN)
                    .redirectErrorStream(true).redirectOutput(EVIDENCE.resolve("logs/privacy-funded-" + RUN + ".log").toFile()).start();
            int privacyExit = privacy.waitFor();
            Files.writeString(EVIDENCE.resolve("logs/privacy-funded-" + RUN + ".exit"), privacyExit + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
            assertThat(privacyExit).isZero();
        } finally {
            facts.confirmed.clear();
            try (var paths = Files.walk(PRIVATE_ROOT)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
            }
        }
    }
    private void browser(String mode, String log, Path enrollment, Path fixture) throws Exception {
        assertThat(RUN).matches("[a-z0-9-]+");
        Path output = EVIDENCE.resolve("logs/" + log + "-" + RUN + ".log");
        Files.createFile(output);
        var process = new ProcessBuilder("node", "scripts/conversations-browser-qa.cjs", mode).redirectErrorStream(true).redirectOutput(output.toFile());
        process.environment().put("C4_QA_BASE", "http://127.0.0.1:" + port);
        process.environment().put("C4_QA_ENROLLMENT", enrollment.toString());
        process.environment().put("C4_QA_FIXTURE", fixture.toString());
        process.environment().put("C4_QA_MAILBOX", PRIVATE_ROOT.resolve("mailbox").toString());
        process.environment().put("C4_QA_SCHEMA", SCHEMA);
        process.environment().put("C4_QA_LOGS", EVIDENCE.resolve("browser/" + log + "/" + RUN).toString());
        int exit = process.start().waitFor();
        Files.writeString(EVIDENCE.resolve("logs/" + log + "-" + RUN + ".exit"), exit + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThat(exit).as("Actual browser " + mode + "; inspect saved first log").isZero();
    }
}
