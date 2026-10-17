package ar.changeo.conversations;

import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.listings.api.*;
import ar.changeo.marketplace.MarketplaceService.Upload;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MessageService {
    private static final Pattern PRIVATE_INPUT = Pattern.compile("(?i)(https?://|/files/|/conversations/|/jobs/|/agreements/|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?:\\+?54|\\b11)[\\d ()-]{7,}|\\b(?:whatsapp|telegram|domicilio|calle|piso|departamento|cbu|cvu|alias bancario)\\b)");
    private final MessageStore store;
    private final ConversationAccess conversations;
    private final ParticipantIdentityAccess identity;
    private final ListingAccess listings;
    private final ListingResponseAccess responses;
    private final ModerationAccess moderation;
    private final FileAccess files;
    private final SecurityProof proof;
    private final Clock clock;
    private final TransactionTemplate transaction;
    MessageService(MessageStore store, ConversationAccess conversations, ParticipantIdentityAccess identity, ListingAccess listings,
            ListingResponseAccess responses, ModerationAccess moderation, FileAccess files, SecurityProof proof, Clock clock, PlatformTransactionManager transactions) {
        this.store = store; this.conversations = conversations; this.identity = identity; this.listings = listings; this.responses = responses;
        this.moderation = moderation; this.files = files; this.proof = proof; this.clock = clock; this.transaction = new TransactionTemplate(transactions);
    }

    public ContentReference save(Draft draft) {
        ListingSnapshot advisory = draft.listingId().map(id -> transaction.execute(status -> listings.currentPublic(id))).orElse(null);
        return transaction.execute(status -> {
            ActorId actor = actor();
            validate(draft);
            MessageStore.Kind kind = MessageStore.Kind.valueOf(draft.kind());
            AuthorContext author;
            ListingResponse parent = null;
            if (draft.threadId().isPresent()) {
                conversations.protect(draft.threadId().orElseThrow(), actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE);
                var context = conversations.discover(draft.threadId().orElseThrow());
                if (kind == MessageStore.Kind.PROPOSAL && !context.provider().equals(draft.subject())) { throw ConversationFailure.unavailable(); }
                author = new AuthorContext(actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE, Optional.of(context.jobContext()));
            } else {
                if (advisory == null || (kind != MessageStore.Kind.QUESTION && kind != MessageStore.Kind.COMMENT) || !draft.uploads().isEmpty()
                        || PRIVATE_INPUT.matcher(draft.body()).find()) { throw new ConversationFailure("La discusión pública no admite contacto, ubicación precisa, enlaces privados ni adjuntos"); }
                var subjects = Set.copyOf(new HashSet<>(List.of(draft.subject(), new SubjectId(advisory.subject().value()))));
                Set<AccountId> participants = new HashSet<>();
                subjects.forEach(subject -> participants.add(new AccountId(subject.value())));
                identity.controllers(subjects).values().forEach(controller -> participants.add(new AccountId(controller.value())));
                author = new AuthorContext(actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE);
                identity.requireForUpdate(author.permission(), Set.copyOf(participants));
                parent = responses.prepareResponse(advisory.reference());
                if (!participants.contains(new AccountId(parent.publisher().value()))) { throw ConversationFailure.unavailable(); }
            }
            String operationDigest = ContentDigest.bytes((draft.resourceId() + ":" + draft.version() + ":" + draft.threadId() + ":" + draft.listingId() + ":" + draft.subject()
                    + ":" + draft.kind() + ":" + draft.label() + ":" + draft.body() + ":" + draft.uploads().stream().map(upload -> upload.mediaType() + ":" + ContentDigest.bytes(upload.bytes())).toList()).getBytes(StandardCharsets.UTF_8));
            var replay = store.jdbc.queryForList("select digest,outcome_id from conversations_receipt where actor_id=? and command_id=?", actor.value(), draft.command());
            if (!replay.isEmpty()) {
                if (!operationDigest.equals(replay.getFirst().get("digest"))) { throw ConversationFailure.unavailable(); }
                UUID revisionId = (UUID) replay.getFirst().get("outcome_id");
                UUID resourceId = store.jdbc.queryForObject("select resource_id from conversations_revision where id=?", UUID.class, revisionId);
                return store.resource(resourceId, false).reference(revisionId);
            }
            UUID id = draft.resourceId().orElseGet(UUID::randomUUID);
            if (draft.resourceId().isPresent()) {
                var resource = store.resource(id, true);
                if (!resource.actor().equals(actor.value()) || !resource.subject().equals(draft.subject().value()) || resource.version() != draft.version()
                        || !Objects.equals(resource.thread(), draft.threadId().orElse(null)) || resource.kind() != kind
                        || !Objects.equals(resource.listingId(), draft.listingId().orElse(null))
                        || (parent != null && !resource.listing().equals(parent.listing().reference()))) { throw ConversationFailure.unavailable(); }
            } else {
                store.jdbc.update("insert into conversations_authored(id,thread_id,listing_id,listing_revision,listing_subject,listing_actor,listing_kind,kind,actor_id,subject_id) values(?,?,?,?,?,?,?,?,?,?)",
                        id, draft.threadId().orElse(null), parent == null ? null : parent.listing().reference().resourceId(), parent == null ? null : parent.listing().reference().revisionId(),
                        parent == null ? null : parent.listing().subject().value(), parent == null ? null : parent.publisher().value(), parent == null ? null : parent.listing().reference().ownerKind().name(),
                        kind.name(), actor.value(), draft.subject().value());
            }
            var reference = new ContentReference(kind.surface(), id, UUID.randomUUID());
            List<FileReference> attachments = new ArrayList<>();
            for (int index = 0; index < draft.uploads().size(); index++) {
                var upload = draft.uploads().get(index);
                attachments.add(files.intake(UUID.nameUUIDFromBytes((draft.command() + ":" + index).getBytes(StandardCharsets.UTF_8)), author, reference, upload.mediaType(), upload.bytes()));
            }
            attachments.sort(Comparator.comparing(file -> file.id().toString()));
            String text = "CHANGEO_WHOLE_MESSAGE_V1\n" + kind + "\n" + draft.body();
            store.jdbc.update("insert into conversations_revision values(?,?,?,?,?,?)", reference.revisionId(), id, draft.body(), draft.label(), ContentDigest.payload(text, draft.label(), attachments), Timestamp.from(clock.instant()));
            for (var file : attachments) { store.jdbc.update("insert into conversations_attachment values(?,?,?)", reference.revisionId(), file.id(), file.digest()); }
            store.jdbc.update("update conversations_authored set current_revision=?,version=version+1 where id=?", reference.revisionId(), id);
            if (parent != null) { moderation.approvalsForUpdate(Set.of(parent.listing().reference())); responses.requireOpen(parent.listing().reference()); }
            store.jdbc.update("insert into conversations_receipt values(?,?,'SAVE',?,?)", actor.value(), draft.command(), operationDigest, reference.revisionId());
            return reference;
        });
    }

    public ModerationAccess.Approval submit(ContentReference reference) {
        return transaction.execute(status -> {
            var resource = store.resource(reference.resourceId(), false);
            var revision = store.revision(resource, reference.revisionId());
            if (!actor().equals(revision.author().actor())) { throw ConversationFailure.unavailable(); }
            return moderation.submitForReview(revision.author(), reference, revision.input().text(), revision.input().declaredLabel(), revision.input().attachments());
        });
    }
    private static void validate(Draft draft) {
        if (draft.threadId().isPresent() == draft.listingId().isPresent() || draft.body().isBlank() || draft.body().length() > 12000 || draft.body().indexOf('\0') >= 0
                || draft.uploads().size() > 4 || !Set.of("GENERAL", "SENSITIVE", "ADULT").contains(draft.label())) { throw ConversationFailure.unavailable(); }
        var kind = MessageStore.Kind.valueOf(draft.kind());
        if (draft.threadId().isPresent() && (kind == MessageStore.Kind.QUESTION || kind == MessageStore.Kind.COMMENT)) { throw ConversationFailure.unavailable(); }
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(ConversationFailure::unavailable)); }
    public record Draft(UUID command, Optional<UUID> resourceId, long version, Optional<UUID> threadId, Optional<UUID> listingId,
            SubjectId subject, String kind, String body, String label, List<Upload> uploads) {
        public Draft { Objects.requireNonNull(command); Objects.requireNonNull(resourceId); Objects.requireNonNull(threadId); Objects.requireNonNull(listingId); Objects.requireNonNull(subject); Objects.requireNonNull(kind); Objects.requireNonNull(body); Objects.requireNonNull(label); uploads = List.copyOf(uploads); }
    }
}
