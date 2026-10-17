package ar.changeo.conversations;

import ar.changeo.conversations.api.ConversationContext;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ConversationStore {
    final JdbcTemplate jdbc;
    ConversationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    ConversationContext thread(UUID id, boolean lock) {
        return jdbc.query("select * from conversations_thread where id=?" + (lock ? " for update" : ""), (row, index) ->
                new ConversationContext(row.getObject("id", UUID.class), row.getObject("job_id", UUID.class),
                        new ContentReference(SurfaceKind.valueOf(row.getString("listing_kind")), row.getObject("listing_id", UUID.class), row.getObject("listing_revision", UUID.class)),
                        row.getString("listing_digest"), row.getString("listing_label"), row.getString("listing_title"), row.getString("listing_snapshot"),
                        new SubjectId(row.getObject("customer_id", UUID.class)), new SubjectId(row.getObject("provider_id", UUID.class)),
                        new ActorId(row.getObject("customer_actor", UUID.class)), new ActorId(row.getObject("provider_actor", UUID.class)),
                        JobContext.FulfillmentMode.valueOf(row.getString("mode")), row.getLong("version")), id)
                .stream().findFirst().orElseThrow(ConversationFailure::unavailable);
    }

    boolean members(ConversationContext context) {
        return jdbc.queryForObject("select count(*) from conversations_membership where thread_id=? and active and subject_id in (?,?)", Integer.class,
                context.id(), context.customer().value(), context.provider().value()) == 2
                && jdbc.queryForObject("select count(*) from conversations_block where (subject_id=? and blocked_subject=?) or (subject_id=? and blocked_subject=?)", Integer.class,
                context.customer().value(), context.provider().value(), context.provider().value(), context.customer().value()) == 0;
    }

    boolean member(ConversationContext context, SubjectId subject) {
        return context.participant(subject) && jdbc.queryForObject("select count(*) from conversations_membership where thread_id=? and subject_id=? and active",
                Integer.class, context.id(), subject.value()) == 1;
    }
}
