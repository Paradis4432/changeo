package ar.changeo.conversations;

import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.api.*;
import ar.changeo.listings.api.ListingResponseAccess;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.notifications.api.NotificationAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class MessageOwner implements ContentOwner {
    private final MessageStore store;
    private final ConversationAccess conversations;
    private final ParticipantIdentityAccess identity;
    private final ObjectProvider<ListingResponseAccess> listings;
    private final NotificationAccess notifications;
    private final ObjectProvider<MessageReader> readers;
    private final Clock clock;
    MessageOwner(MessageStore store, ConversationAccess conversations, ParticipantIdentityAccess identity, ObjectProvider<ListingResponseAccess> listings,
            NotificationAccess notifications, ObjectProvider<MessageReader> readers, Clock clock) {
        this.store = store; this.conversations = conversations; this.identity = identity; this.listings = listings;
        this.notifications = notifications; this.readers = readers; this.clock = clock;
    }
    @Override public Set<SurfaceKind> kinds() { return Set.of(SurfaceKind.PRIVATE_MESSAGE, SurfaceKind.QUESTION, SurfaceKind.COMMENT); }
    @Override public boolean owns(ContentReference reference) { return store.owns(reference); }
    @Override public Set<AccountId> identityParticipants(ContentReference reference) {
        var resource = store.resource(reference.resourceId(), false);
        if (resource.thread() != null) { return conversations.participants(resource.thread()); }
        Set<AccountId> participants = new HashSet<>(List.of(new AccountId(resource.actor()), new AccountId(resource.subject()),
                new AccountId(resource.listingSubject()), new AccountId(resource.listingActor())));
        identity.controllers(Set.copyOf(new HashSet<>(List.of(new SubjectId(resource.subject()), new SubjectId(resource.listingSubject()))))).values()
                .forEach(controller -> participants.add(new AccountId(controller.value())));
        return Set.copyOf(participants);
    }
    @Override public Set<ContentReference> approvalDependencies(ContentReference reference) {
        var resource = store.resource(reference.resourceId(), false);
        return resource.thread() == null ? Set.of(resource.listing()) : Set.of();
    }
    @Override public ReviewInput lockRevision(AuthorContext author, ContentReference reference) {
        var discovered = store.resource(reference.resourceId(), false);
        if (discovered.thread() == null) {
            var parent = listings.getObject().prepareResponse(discovered.listing());
            if (!parent.listing().subject().value().equals(discovered.listingSubject()) || !parent.publisher().value().equals(discovered.listingActor())) {
                throw ConversationFailure.unavailable();
            }
        } else { conversations.lock(discovered.thread()); }
        var resource = store.resource(reference.resourceId(), true);
        var revision = store.revision(resource, reference.revisionId());
        if (!resource.reference(reference.revisionId()).equals(reference) || !revision.author().equals(author)) { throw ConversationFailure.unavailable(); }
        return revision.input();
    }
    @Override public boolean current(ContentReference reference) {
        return reference.revisionId().equals(store.resource(reference.resourceId(), false).current());
    }
    @Override public boolean reportable(ContentReference reference) {
        var resource = store.resource(reference.resourceId(), false);
        if (!reference.revisionId().equals(resource.approved())) { return false; }
        if (resource.thread() == null) { listings.getObject().requireOpen(resource.listing()); }
        else { conversations.requireEligible(conversations.discover(resource.thread())); }
        return true;
    }
    @Override public long activate(ContentReference reference) {
        var resource = store.resource(reference.resourceId(), false);
        if (!current(reference)) { throw ConversationFailure.unavailable(); }
        if (resource.thread() == null) { listings.getObject().requireOpen(resource.listing()); }
        else { conversations.requireEligible(conversations.discover(resource.thread())); }
        if (reference.revisionId().equals(resource.approved())) { return resource.version(); }
        store.jdbc.update("update conversations_authored set approved_revision=?,version=version+1 where id=?", reference.revisionId(), resource.id());
        if (resource.thread() != null) {
            var context = conversations.discover(resource.thread());
            store.jdbc.update("insert into conversations_event(thread_id,resource_id,revision_id,kind,occurred_at) values(?,?,?,'DELIVERED',?) on conflict do nothing",
                    context.id(), resource.id(), reference.revisionId(), Timestamp.from(clock.instant()));
            notifications.enqueue(reference, Set.of(context.customerActor(), context.providerActor()));
        }
        return resource.version() + 1;
    }
    @Override public void recall(ContentReference reference) {
        var resource = store.resource(reference.resourceId(), false);
        int changed = store.jdbc.update("update conversations_authored set approved_revision=null,version=version+1 where id=? and approved_revision=?", resource.id(), reference.revisionId());
        if (changed > 0 && resource.thread() != null) {
            store.jdbc.update("insert into conversations_event(thread_id,resource_id,revision_id,kind,occurred_at) values(?,?,?,'RECALLED',?) on conflict do nothing",
                    resource.thread(), resource.id(), reference.revisionId(), Timestamp.from(clock.instant()));
        }
    }
    @Override public void requireAttachment(FileReference reference, FileAccess.Purpose purpose) { readers.getObject().requireAttachment(reference, purpose); }
}
