package ar.changeo.subscriptions;

import ar.changeo.identity.api.*;
import ar.changeo.payments.api.BillingBinding;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class SubscriptionStore {
    final JdbcTemplate jdbc;
    final Clock clock;

    SubscriptionStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    PolicyPointer pointer(boolean lock) {
        return jdbc.query("select policy_version,version from subscriptions_policy_current where slot=1" + (lock ? " for update" : ""),
                (row, index) -> new PolicyPointer(row.getString(1), row.getLong(2))).stream().findFirst()
                .orElseThrow(() -> new SubscriptionFailure("Política sintética publicada pendiente"));
    }

    SubscriptionPolicy policy(String version, boolean lock) {
        return jdbc.query("select * from subscriptions_policy where version=?" + (lock ? " for share" : ""), (row, index) -> {
            var policy = new SubscriptionPolicy(row.getString("version"), row.getLong("gross_minor"), row.getInt("founding_days"), row.getInt("trial_days"),
                    row.getInt("grace_days"), row.getLong("credit_minor"), row.getLong("fee_minor"), ZoneId.of(row.getString("zone")));
            if (!policy.digest().equals(row.getString("digest")) || !policy.disclosure().equals(row.getString("disclosure"))) { throw new SubscriptionFailure("Términos publicados incompatibles"); }
            return policy;
        }, version).stream().findFirst().orElseThrow(() -> new SubscriptionFailure("Versión de términos no disponible"));
    }

    Optional<Provider> provider(SubjectId subject, boolean lock) {
        return jdbc.query("select * from subscriptions_provider where provider_id=?" + (lock ? " for update" : ""), (row, index) ->
                new Provider(subject, row.getLong("generation"), Optional.ofNullable(row.getObject("active_enrollment", UUID.class)), row.getBoolean("founding_used"),
                        row.getBoolean("trial_used"), row.getBoolean("public_achievements"), row.getLong("version")), subject.value()).stream().findFirst();
    }

    Enrollment enrollment(UUID id, boolean lock) {
        return jdbc.query("select * from subscriptions_enrollment where id=?" + (lock ? " for update" : ""), (row, index) ->
                new Enrollment(new BillingBinding(id, row.getLong("generation"), new SubjectId(row.getObject("provider_id", UUID.class)),
                        new ActorId(row.getObject("original_actor", UUID.class)), new AccountId(row.getObject("original_payer", UUID.class))), row.getString("policy_version"), Optional.ofNullable(row.getString("state")),
                        instant(row.getTimestamp("benefit_start")), instant(row.getTimestamp("benefit_end")), Optional.ofNullable(row.getObject("consent_id", UUID.class)),
                        instant(row.getTimestamp("cycle_start")), Optional.ofNullable(row.getObject("anchor_day", Integer.class)), row.getBoolean("stop_renewal"), row.getLong("version"),
                        Optional.ofNullable(row.getString("benefit_kind")), Optional.ofNullable(row.getString("benefit_policy_version"))), id)
                .stream().findFirst().orElseThrow(() -> new SubscriptionFailure("Inscripción original no disponible"));
    }

    Consent consent(UUID id) {
        return jdbc.query("select * from subscriptions_consent where id=?", (row, index) -> new Consent(id, row.getObject("enrollment_id", UUID.class),
                new ActorId(row.getObject("actor_id", UUID.class)), row.getString("policy_version"), row.getString("policy_digest"), row.getString("mode"), row.getBoolean("use_credit"),
                row.getTimestamp("period_start").toInstant(), row.getTimestamp("period_end").toInstant(), row.getLong("consent_version")), id)
                .stream().findFirst().orElseThrow(() -> new SubscriptionFailure("Consentimiento original no disponible"));
    }

    void event(Enrollment enrollment, String kind) { jdbc.update("insert into subscriptions_event values(?,?,?,?,?)", UUID.randomUUID(), enrollment.binding().enrollmentId(), kind, enrollment.policyVersion(), now()); }
    Instant instant() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    Timestamp now() { return Timestamp.from(instant()); }
    private static Optional<Instant> instant(Timestamp timestamp) { return Optional.ofNullable(timestamp).map(Timestamp::toInstant); }
    record PolicyPointer(String version, long revision) {}
    record Provider(SubjectId subject, long generation, Optional<UUID> enrollment, boolean foundingUsed, boolean trialUsed, boolean publicAchievements, long version) {}
    record Enrollment(BillingBinding binding, String policyVersion, Optional<String> state, Optional<Instant> benefitStart, Optional<Instant> benefitEnd,
            Optional<UUID> consent, Optional<Instant> cycleStart, Optional<Integer> anchorDay, boolean stopRenewal, long version, Optional<String> benefitKind, Optional<String> benefitPolicyVersion) {}
    record Consent(UUID id, UUID enrollment, ActorId actor, String policyVersion, String digest, String mode, boolean useCredit,
            Instant periodStart, Instant periodEnd, long version) {}
}
