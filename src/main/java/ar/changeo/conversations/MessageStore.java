package ar.changeo.conversations;

import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.files.api.FileReference;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class MessageStore {
    final JdbcTemplate jdbc;
    private final ConversationAccess conversations;
    MessageStore(JdbcTemplate jdbc, ConversationAccess conversations) { this.jdbc = jdbc; this.conversations = conversations; }

    Resource resource(UUID id, boolean lock) {
        return jdbc.query("select * from conversations_authored where id=?" + (lock ? " for update" : ""), (row, index) ->
                new Resource(id, row.getObject("thread_id", UUID.class), row.getObject("listing_id", UUID.class), row.getObject("listing_revision", UUID.class),
                        row.getObject("listing_subject", UUID.class), row.getObject("listing_actor", UUID.class), row.getString("listing_kind"), Kind.valueOf(row.getString("kind")),
                        row.getObject("actor_id", UUID.class), row.getObject("subject_id", UUID.class), row.getObject("current_revision", UUID.class),
                        row.getObject("approved_revision", UUID.class), row.getLong("version")), id).stream().findFirst().orElseThrow(ConversationFailure::unavailable);
    }
    Revision revision(Resource resource, UUID revision) {
        var reference = resource.reference(revision);
        return jdbc.query("select * from conversations_revision where resource_id=? and id=?", (row, index) -> {
            String body = row.getString("body");
            var attachments = attachments(reference);
            var author = new AuthorContext(new ActorId(resource.actor()), new SubjectId(resource.subject()), Capability.SEND_MARKETPLACE_MESSAGE,
                    resource.thread() == null ? Optional.empty() : Optional.of(conversations.discover(resource.thread()).jobContext()));
            var input = new ReviewInput(reference, "CHANGEO_WHOLE_MESSAGE_V1\n" + resource.kind() + "\n" + body,
                    row.getString("declared_label"), row.getString("digest"), attachments);
            if (!ContentDigest.payload(input.text(), input.declaredLabel(), attachments).equals(input.digest())) { throw ConversationFailure.unavailable(); }
            return new Revision(author, body, input);
        }, resource.id(), revision).stream().findFirst().orElseThrow(ConversationFailure::unavailable);
    }
    List<FileReference> attachments(ContentReference reference) {
        return jdbc.query("select file_id,digest from conversations_attachment where revision_id=? order by file_id::text",
                (row, index) -> new FileReference(row.getObject(1, UUID.class), reference, row.getString(2)), reference.revisionId());
    }
    boolean owns(ContentReference reference) {
        return jdbc.queryForObject("select count(*) from conversations_authored a join conversations_revision r on r.resource_id=a.id where a.id=? and r.id=? and a.kind in (?,?)",
                Integer.class, reference.resourceId(), reference.revisionId(), Kind.forSurface(reference.ownerKind()).name(),
                reference.ownerKind() == SurfaceKind.PRIVATE_MESSAGE ? "PROPOSAL" : Kind.forSurface(reference.ownerKind()).name()) == 1;
    }
    enum Kind {
        PRIVATE_MESSAGE, PROPOSAL, QUESTION, COMMENT;
        SurfaceKind surface() { return this == QUESTION ? SurfaceKind.QUESTION : this == COMMENT ? SurfaceKind.COMMENT : SurfaceKind.PRIVATE_MESSAGE; }
        static Kind forSurface(SurfaceKind kind) {
            return switch (kind) { case QUESTION -> QUESTION; case COMMENT -> COMMENT; case PRIVATE_MESSAGE -> PRIVATE_MESSAGE; default -> throw ConversationFailure.unavailable(); };
        }
    }
    record Resource(UUID id, UUID thread, UUID listingId, UUID listingRevision, UUID listingSubject, UUID listingActor, String listingKind,
            Kind kind, UUID actor, UUID subject, UUID current, UUID approved, long version) {
        ContentReference reference(UUID revision) { return new ContentReference(kind.surface(), id, revision); }
        ContentReference listing() { return new ContentReference(SurfaceKind.valueOf(listingKind), listingId, listingRevision); }
    }
    record Revision(AuthorContext author, String body, ReviewInput input) {}
}
