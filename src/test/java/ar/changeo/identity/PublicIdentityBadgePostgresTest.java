package ar.changeo.identity;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.api.*;
import ar.changeo.security.SessionProof;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=ChangeoApplication.class,properties={"spring.flyway.schemas=profiles_badge_test","spring.flyway.default-schema=profiles_badge_test","spring.jpa.properties.hibernate.default_schema=profiles_badge_test","spring.datasource.hikari.schema=profiles_badge_test","changeo.mfa-key=.runtime/profiles-badge-mfa.key","changeo.moderation-worker=false"})
class PublicIdentityBadgePostgresTest {
    @Autowired PublicIdentityBadgeAccess badges;
    @Autowired IdentityAccess identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @MockitoBean SessionProof proof;
    UUID subject;
    @BeforeEach void setup() {
        subject=UUID.randomUUID();
        jdbc.update("insert into identity_account(id,contact,password_hash) values(?,?,?)",subject,"p1badge"+subject+"@example.test","synthetic-fixture-hash");
        when(proof.actor()).thenReturn(Optional.empty());
    }
    Map<AccountId,PublicIdentityBadge> project() { return new TransactionTemplate(transactions).execute(status -> badges.findCurrent(Set.of(new AccountId(subject)))); }
    void evidence(Instant created, Instant until, String provenance, Integer age) {
        jdbc.update("insert into identity_verification_evidence(id,account_id,kind,age,expires_at,scope,provenance,created_at) values(?,?,'OPTIONAL_BADGE',?,?,'SANDBOX_SYNTHETIC',?,?)",UUID.randomUUID(),subject,age,Timestamp.from(until),provenance,Timestamp.from(created));
    }
    @Test void shouldRequireCallerTransactionAndExposeMinimalSyntheticCheckWithoutEligibility() {
        assertThatThrownBy(() -> badges.findCurrent(Set.of(new AccountId(subject)))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(project()).isEmpty();
        Instant checked=clock.instant().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS),until=clock.instant().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        evidence(checked,until,"LOCAL_SYNTHETIC_OPERATOR:"+UUID.randomUUID(),null);
        assertThat(project()).containsExactly(Map.entry(new AccountId(subject),new PublicIdentityBadge("SANDBOX_SYNTHETIC",checked,until,"LOCAL_SYNTHETIC_CHECK")));
        assertThat(identity.decide(PermissionRequest.self(new AccountId(subject),Capability.NEW_REQUEST)).allowed()).isFalse();
        jdbc.update("insert into identity_restriction values(?,?,true,'SYNTHETIC_REVIEW',?)",UUID.randomUUID(),subject,Timestamp.from(clock.instant()));
        assertThat(project()).isEmpty();
    }
    @Test void shouldHideExpiredFutureUnknownProvenanceAndInvalidBadgeEvidence() {
        evidence(clock.instant().minusSeconds(120),clock.instant(),"LOCAL_SYNTHETIC_OPERATOR:"+UUID.randomUUID(),null); assertThat(project()).isEmpty();
        evidence(clock.instant().plusSeconds(10),clock.instant().plusSeconds(600),"LOCAL_SYNTHETIC_OPERATOR:"+UUID.randomUUID(),null); assertThat(project()).isEmpty();
        evidence(clock.instant().minusSeconds(10),clock.instant().plusSeconds(600),"PRIVATE_OPERATOR_DATA",null); assertThat(project()).isEmpty();
        evidence(clock.instant().minusSeconds(10),clock.instant().plusSeconds(600),"LOCAL_SYNTHETIC_OPERATOR:"+UUID.randomUUID(),30); assertThat(project()).isEmpty();
    }
}
