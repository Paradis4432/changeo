package ar.changeo.jobs;

import ar.changeo.conversations.api.*;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobSnapshot;
import ar.changeo.moderation.api.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JobStore {
    final JdbcTemplate jdbc;
    private final ConversationAccess conversations;
    private final Clock clock;
    JobStore(JdbcTemplate jdbc, ConversationAccess conversations, Clock clock) { this.jdbc = jdbc; this.conversations = conversations; this.clock = clock; }

    JobSnapshot job(UUID id, boolean lock) {
        return jdbc.query("select * from jobs_job where id=?" + (lock ? " for update" : ""), (row, index) -> {
            UUID quote = row.getObject("accepted_quote", UUID.class);
            UUID acceptedRevision = row.getObject("accepted_revision", UUID.class);
            UUID finalResource = row.getObject("final_resource", UUID.class);
            UUID finalRevision = row.getObject("final_revision", UUID.class);
            return new JobSnapshot(id, conversations.discover(row.getObject("conversation_id", UUID.class)), JobSnapshot.State.valueOf(row.getString("state")),
                    Optional.ofNullable(quote).map(value -> new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, value, acceptedRevision)),
                    Optional.ofNullable(row.getObject("legal_payer", UUID.class)).map(AccountId::new),
                    Optional.ofNullable(finalResource).map(value -> new ContentReference(SurfaceKind.JOB_FEEDBACK, value, finalRevision)),
                    instant(row.getTimestamp("final_approved_at")), instant(row.getTimestamp("review_due_at")), row.getInt("feedback_count"), row.getLong("version"));
        }, id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Trabajo no disponible"));
    }

    void cohorts(ConversationContext context) {
        List<UUID> subjects = List.of(context.customer().value(), context.provider().value()).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        for (UUID subject : subjects) { jdbc.queryForObject("select version from jobs_reputation_cohort where subject_id=? for update", Long.class, subject); }
    }
    void changed(ConversationContext context) {
        jdbc.update("update jobs_reputation_cohort set version=version+1 where subject_id in (?,?)", context.customer().value(), context.provider().value());
    }
    void event(UUID job, UUID actor, String kind, ContentReference reference) {
        jdbc.update("insert into jobs_event values(?,?,?,?,?,?,?)", UUID.randomUUID(), job, actor, kind,
                reference == null ? null : reference.resourceId(), reference == null ? null : reference.revisionId(), Timestamp.from(clock.instant()));
    }
    private static Optional<Instant> instant(Timestamp value) { return Optional.ofNullable(value).map(Timestamp::toInstant); }
}
