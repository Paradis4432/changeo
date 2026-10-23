package ar.changeo.conversations;

import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.listings.api.ListingResponseAccess;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.notifications.api.NoticeOwner;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MessageReader implements NoticeOwner {
    private final MessageStore store;
    private final ConversationAccess conversations;
    private final JobAccess jobs;
    private final ParticipantIdentityAccess identity;
    private final IdentityAccess permissions;
    private final ModerationAccess moderation;
    private final ListingResponseAccess listings;
    private final SecurityProof proof;
    private final ObjectProvider<MessageOwner> owners;
    private final ObjectProvider<ContentReader> evidence;
    private final TransactionTemplate authorTransaction;
    MessageReader(MessageStore store, ConversationAccess conversations, JobAccess jobs, ParticipantIdentityAccess identity, IdentityAccess permissions,
            ModerationAccess moderation, ListingResponseAccess listings, SecurityProof proof, ObjectProvider<MessageOwner> owners, ObjectProvider<ContentReader> evidence, PlatformTransactionManager transactions) {
        this.store = store; this.conversations = conversations; this.jobs = jobs; this.identity = identity; this.permissions = permissions;
        this.moderation = moderation; this.listings = listings; this.proof = proof; this.owners = owners; this.evidence = evidence;
        this.authorTransaction = new TransactionTemplate(transactions);
        this.authorTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional public MessageView ordinary(ContentReference reference, SubjectId subject) { return privateView(reference, actor(), subject); }

    @Transactional public Optional<MessageView> historical(ContentReference reference, SubjectId side) {
        var discovered = store.resource(reference.resourceId(), false);
        if (discovered.thread() == null) { throw ConversationFailure.unavailable(); }
        var actor = actor();
        conversations.protect(discovered.thread(), actor, side, Capability.ACCESS_EXISTING_OBLIGATION);
        var context = conversations.discover(discovered.thread());
        var job = jobs.lock(context.jobId());
        if (!job.accepted() || !job.conversation().equals(context)) { throw ConversationFailure.unavailable(); }
        var resource = store.resource(reference.resourceId(), true);
        var revision = store.revision(resource, reference.revisionId());
        var approval = moderation.approvalsForUpdate(Set.of(reference)).get(reference);
        if (!permissions.decide(PermissionRequest.self(new AccountId(actor.value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed()
                || !visible(resource, revision, approval, Optional.of(actor), Optional.of(side))) { return Optional.empty(); }
        return Optional.of(new MessageView(reference, revision.body(), approval.label(), revision.input().attachments(), resource.version(), 0));
    }

    @Transactional public List<MessageView> historicalPage(UUID threadId, SubjectId side) {
        return historicalPage(threadId, side, Optional.empty()).messages();
    }

    @Transactional public HistoricalPage historicalPage(UUID threadId, SubjectId side, Optional<UUID> after) {
        ActorId actor = actor();
        conversations.protect(threadId, actor, side, Capability.ACCESS_EXISTING_OBLIGATION);
        var context = conversations.discover(threadId);
        var job = jobs.lock(context.jobId());
        if (!job.accepted() || !job.conversation().equals(context)) { throw ConversationFailure.unavailable(); }
        after.ifPresent(id -> {
            if (!threadId.equals(store.resource(id, false).thread())) { throw ConversationFailure.unavailable(); }
        });
        var ids = store.jdbc.query("select id,approved_revision from conversations_authored where thread_id=? and approved_revision is not null and (cast(? as text) is null or id::text>?) order by id::text limit 11",
                (row, index) -> new UUID[]{row.getObject(1, UUID.class), row.getObject(2, UUID.class)}, threadId, after.map(UUID::toString).orElse(null), after.map(UUID::toString).orElse(null));
        var next = ids.size() > 10 ? Optional.of(ids.get(9)[0]) : Optional.<UUID>empty();
        Map<ContentReference, MessageStore.Resource> resources = new LinkedHashMap<>();
        for (var id : ids.stream().limit(10).toList()) {
            var resource = store.resource(id[0], true);
            if (!threadId.equals(resource.thread()) || !id[1].equals(resource.approved())) { throw ConversationFailure.unavailable(); }
            resources.put(resource.reference(id[1]), resource);
        }
        var approvals = resources.isEmpty() ? Map.<ContentReference, ModerationAccess.Approval>of() : moderation.approvalsForUpdate(Set.copyOf(resources.keySet()));
        if (!permissions.decide(PermissionRequest.self(new AccountId(actor.value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed()) { return new HistoricalPage(List.of(), next); }
        List<MessageView> result = new ArrayList<>();
        for (var entry : resources.entrySet()) {
            var revision = store.revision(entry.getValue(), entry.getKey().revisionId());
            var approval = approvals.get(entry.getKey());
            if (visible(entry.getValue(), revision, approval, Optional.of(actor), Optional.of(side))) {
                result.add(new MessageView(entry.getKey(), revision.body(), approval.label(), revision.input().attachments(), entry.getValue().version(), 0));
            }
        }
        return new HistoricalPage(result, next);
    }

    private MessageView privateView(ContentReference reference, ActorId actor, SubjectId subject) {
        var discovered = store.resource(reference.resourceId(), false);
        if (discovered.thread() == null) { throw ConversationFailure.unavailable(); }
        conversations.protect(discovered.thread(), actor, subject, Capability.SEND_MARKETPLACE_MESSAGE);
        var resource = store.resource(reference.resourceId(), true);
        var revision = store.revision(resource, reference.revisionId());
        var approvals = moderation.approvalsForUpdate(Set.of(reference));
        return checked(resource, revision, approvals.get(reference), Optional.of(actor), Optional.of(subject));
    }

    @Transactional public PollPage poll(UUID threadId, SubjectId subject, long after) {
        if (after < 0) { throw ConversationFailure.unavailable(); }
        ActorId actor = actor();
        conversations.protect(threadId, actor, subject, Capability.SEND_MARKETPLACE_MESSAGE);
        var events = store.jdbc.query("select sequence,resource_id,revision_id,kind from conversations_event where thread_id=? and sequence>? order by sequence limit 10",
                (row, index) -> new Event(row.getLong(1), row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getString(4)), threadId, after);
        var messages = projectEvents(threadId, subject, actor, events);
        long next = events.isEmpty() ? after : events.getLast().sequence();
        return new PollPage(messages, next, events.size() == 10);
    }

    private List<MessageView> projectEvents(UUID threadId, SubjectId subject, ActorId actor, List<Event> events) {
        Map<UUID, MessageStore.Resource> resources = new HashMap<>();
        for (UUID id : events.stream().map(Event::resource).distinct().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var resource = store.resource(id, true);
            if (!threadId.equals(resource.thread())) { throw ConversationFailure.unavailable(); }
            resources.put(id, resource);
        }
        Set<ContentReference> references = new HashSet<>();
        for (var event : events) {
            var resource = resources.get(event.resource());
            if (event.kind().equals("DELIVERED") && event.revision().equals(resource.approved())) { references.add(resource.reference(event.revision())); }
        }
        var approvals = references.isEmpty() ? Map.<ContentReference, ModerationAccess.Approval>of() : moderation.approvalsForUpdate(Set.copyOf(references));
        List<MessageView> messages = new ArrayList<>();
        for (var event : events) {
            var resource = resources.get(event.resource());
            var reference = resource.reference(event.revision());
            if (references.contains(reference)) {
                var revision = store.revision(resource, event.revision());
                var approval = approvals.get(reference);
                if (visible(resource, revision, approval, Optional.of(actor), Optional.of(subject))) {
                    messages.add(new MessageView(reference, revision.body(), approval.label(), revision.input().attachments(), resource.version(), event.sequence()));
                }
            }
        }
        return List.copyOf(messages);
    }

    @Transactional public PollPage recent(UUID threadId, SubjectId subject) {
        var sequences = store.jdbc.query("select sequence from conversations_event where thread_id=? order by sequence desc limit 10", (row, index) -> row.getLong(1), threadId);
        long after = sequences.isEmpty() ? 0 : Math.max(0, sequences.getLast() - 1);
        return poll(threadId, subject, after);
    }

    @Transactional public HistoryPage history(UUID threadId, SubjectId subject, Optional<String> cursor) {
        ActorId actor = actor();
        conversations.protect(threadId, actor, subject, Capability.SEND_MARKETPLACE_MESSAGE);
        var selected = cursor.map(HistoryCursor::parse);
        selected.ifPresent(value -> {
            var boundaries = store.jdbc.query("select sequence from conversations_event where thread_id=? and sequence in (?,?)",
                    (row, index) -> row.getLong(1), threadId, value.before(), value.observed());
            if (!boundaries.contains(value.before()) || !boundaries.contains(value.observed())) { throw ConversationFailure.unavailable(); }
        });
        long current = store.jdbc.queryForObject("select coalesce(max(sequence),0) from conversations_event where thread_id=?", Long.class, threadId);
        long observed = selected.map(HistoryCursor::observed).orElse(current);
        var descending = store.jdbc.query("select sequence,resource_id,revision_id,kind from conversations_event where thread_id=? and sequence<=? and (cast(? as bigint) is null or sequence<?) order by sequence desc limit 11",
                (row, index) -> new Event(row.getLong(1), row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getString(4)),
                threadId, observed, selected.map(HistoryCursor::before).orElse(null), selected.map(HistoryCursor::before).orElse(null));
        var events = new ArrayList<>(descending.stream().limit(10).toList());
        Collections.reverse(events);
        var next = descending.size() > 10 ? Optional.of(new HistoryCursor(events.getFirst().sequence(), observed).encoded()) : Optional.<String>empty();
        return new HistoryPage(projectEvents(threadId, subject, actor, events), next, selected.isPresent(), current > observed);
    }

    @Transactional public List<MessageView> discussion(ContentReference listing) {
        var ids = store.jdbc.query("select id,approved_revision from conversations_authored where listing_id=? and listing_revision=? and listing_kind=? and approved_revision is not null order by id::text limit 10",
                (row, index) -> new UUID[]{row.getObject(1, UUID.class), row.getObject(2, UUID.class)}, listing.resourceId(), listing.revisionId(), listing.ownerKind().name());
        return discussion(listing, ids);
    }

    private List<MessageView> discussion(ContentReference listing, List<UUID[]> ids) {
        if (ids.isEmpty()) { return List.of(); }
        var owner = owners.getObject();
        List<ContentReference> ownReferences = new ArrayList<>();
        Set<ContentReference> references = new HashSet<>();
        Set<AccountId> accounts = new HashSet<>();
        Map<ContentReference, MessageStore.Revision> revisions = new HashMap<>();
        Map<ContentReference, Set<AccountId>> declared = new HashMap<>();
        for (var id : ids) {
            var resource = store.resource(id[0], false);
            if (resource.thread() != null || !listing.equals(resource.listing())) { throw ConversationFailure.unavailable(); }
            var reference = resource.reference(id[1]); ownReferences.add(reference); references.add(reference); references.add(resource.listing());
            var participants = owner.identityParticipants(reference); declared.put(reference, participants); accounts.addAll(participants);
            revisions.put(reference, store.revision(resource, id[1]));
        }
        proof.actor().ifPresent(viewer -> accounts.add(new AccountId(viewer)));
        var first = revisions.get(ownReferences.getFirst());
        identity.requireForUpdate(first.author().permission(), Set.copyOf(accounts));
        var parent = listings.prepareResponse(listing);
        Map<ContentReference, MessageStore.Resource> resources = new HashMap<>();
        for (var reference : ownReferences.stream().sorted(Comparator.comparing(value -> value.resourceId().toString())).toList()) {
            var resource = store.resource(reference.resourceId(), true);
            if (!declared.get(reference).equals(owner.identityParticipants(reference)) || !reference.revisionId().equals(resource.approved())
                    || !parent.listing().subject().value().equals(resource.listingSubject()) || !parent.publisher().value().equals(resource.listingActor())) { throw ConversationFailure.unavailable(); }
            resources.put(reference, resource);
        }
        var approvals = moderation.approvalsForUpdate(Set.copyOf(references));
        listings.requireOpen(listing);
        List<MessageView> result = new ArrayList<>();
        for (var reference : ownReferences) {
            var resource = resources.get(reference); var revision = revisions.get(reference); var approval = approvals.get(reference);
            if (permissions.decide(revision.author().permission()).allowed() && visible(resource, revision, approval, proof.actor().map(ActorId::new), proof.actor().map(SubjectId::new))) {
                result.add(new MessageView(reference, revision.body(), approval.label(), revision.input().attachments(), resource.version(), 0));
            }
        }
        return List.copyOf(result);
    }

    public List<PreparedView> own(UUID threadId, SubjectId subject) { return ownPage(threadId, subject, Optional.empty(), Optional.empty()).entries(); }

    public PreparedPage ownPage(UUID threadId, SubjectId subject, Optional<UUID> selected, Optional<UUID> after) {
        ActorId actor = actor();
        var ids = selected.map(List::of).orElseGet(() -> store.jdbc.query("select id from conversations_authored where thread_id=? and actor_id=? and subject_id=? and current_revision is not null and (cast(? as text) is null or id::text>?) order by id::text limit 11",
                (row, index) -> row.getObject(1, UUID.class), threadId, actor.value(), subject.value(), after.map(UUID::toString).orElse(null), after.map(UUID::toString).orElse(null)));
        List<PreparedView> entries = ids.stream().limit(10).map(id -> authorTransaction.execute(status -> {
            var resource = store.resource(id, false);
            if (!Objects.equals(resource.thread(), threadId)) { throw ConversationFailure.unavailable(); }
            return ownRevision(resource, subject);
        })).toList();
        return new PreparedPage(entries, ids.size() > 10 ? Optional.of(ids.get(9)) : Optional.empty());
    }

    public PreparedView ownResource(UUID id, SubjectId subject) {
        return authorTransaction.execute(status -> ownRevision(store.resource(id, false), subject));
    }

    public PreparedPage ownDiscussion(SubjectId subject, Optional<UUID> after) {
        var ids = store.jdbc.query("select id from conversations_authored where thread_id is null and actor_id=? and subject_id=? and current_revision is not null and (cast(? as text) is null or id::text>?) order by id::text limit 11",
                (row, index) -> row.getObject(1, UUID.class), actor().value(), subject.value(), after.map(UUID::toString).orElse(null), after.map(UUID::toString).orElse(null));
        var entries = ids.stream().limit(10).map(id -> ownResource(id, subject)).toList();
        return new PreparedPage(entries, ids.size() > 10 ? Optional.of(ids.get(9)) : Optional.empty());
    }

    private PreparedView ownRevision(MessageStore.Resource discovered, SubjectId subject) {
        if (discovered.current() == null) { throw ConversationFailure.unavailable(); }
        return ownRevision(discovered, subject, discovered.reference(discovered.current()));
    }

    private PreparedView ownRevision(MessageStore.Resource discovered, SubjectId subject, ContentReference reference) {
        ActorId actor = actor();
        if (!discovered.actor().equals(actor.value()) || !discovered.subject().equals(subject.value()) || discovered.current() == null
                || !reference.equals(discovered.reference(reference.revisionId()))) { throw ConversationFailure.unavailable(); }
        var owner = owners.getObject();
        var participants = owner.identityParticipants(reference);
        identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.value()), Capability.ACCESS_SUPPORT), participants);
        var advisory = store.revision(discovered, reference.revisionId());
        var input = owner.lockRevision(advisory.author(), reference);
        var resource = store.resource(discovered.id(), false);
        if (!resource.equals(discovered) || !participants.equals(owner.identityParticipants(reference)) || !input.equals(advisory.input())) { throw ConversationFailure.unavailable(); }
        var status = moderation.findApprovalForUpdate(reference);
        status.ifPresent(approval -> {
            if (!approval.reference().equals(reference) || !approval.digest().equals(input.digest())) { throw ConversationFailure.unavailable(); }
        });
        String label = status.map(ModerationAccess.Approval::label).orElse(input.declaredLabel());
        boolean visible = !(label.equals("ADULT") && !subject.value().equals(actor.value()))
                && permissions.decide(PermissionRequest.self(new AccountId(actor.value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed()
                && permissions.decide(PermissionRequest.self(new AccountId(actor.value()), audience(label))).allowed();
        return new PreparedView(reference, resource.version(), status.map(ModerationAccess.Approval::state).orElse("PREPARED_UNSUBMITTED"),
                status.map(ModerationAccess.Approval::reason).orElse("SUBMIT_REQUIRED"), visible ? advisory.body() : "Contenido oculto por la audiencia actual", resource.kind().name(),
                Optional.ofNullable(resource.thread()), Optional.ofNullable(resource.listingId()), visible);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PreparedView origin(ContentReference reference) {
        return authorTransaction.execute(status -> {
            if (!store.owns(reference)) { throw ConversationFailure.unavailable(); }
            var resource = store.resource(reference.resourceId(), false);
            ActorId actor = actor();
            if (resource.actor().equals(actor.value())) {
                var own = ownRevision(resource, new SubjectId(resource.subject()), reference);
                var revision = store.revision(resource, reference.revisionId());
                var approval = moderation.findApprovalForUpdate(reference);
                audience(approval.map(ModerationAccess.Approval::label).orElse(revision.input().declaredLabel()));
                return own;
            }
            MessageView view;
            if (resource.thread() == null) {
                var visible = discussion(resource.listing(), List.<UUID[]>of(new UUID[]{reference.resourceId(), reference.revisionId()}));
                if (visible.size() != 1) { throw ConversationFailure.unavailable(); }
                view = visible.getFirst();
            } else {
                var context = conversations.discover(resource.thread());
                if (!actor.equals(context.customerActor()) && !actor.equals(context.providerActor())) { throw ConversationFailure.unavailable(); }
                var side = actor.equals(context.customerActor()) ? context.customer() : context.provider();
                view = jobs.discover(context.jobId()).accepted()
                        ? historical(reference, side).orElseThrow(ConversationFailure::unavailable)
                        : privateView(reference, actor, side);
            }
            return new PreparedView(reference, view.version(), "APPROVED", "", view.body(), resource.kind().name(), Optional.empty(), Optional.empty(), true);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAttachment(FileReference file, FileAccess.Purpose purpose) {
        if (purpose == FileAccess.Purpose.REVIEW) {
            if (!evidence.getObject().evidence(file.content()).attachments().contains(file)) { throw ConversationFailure.unavailable(); }
            return;
        }
        var resource = store.resource(file.content().resourceId(), false);
        if (resource.thread() == null) { throw ConversationFailure.unavailable(); }
        var context = conversations.discover(resource.thread());
        ActorId actor = actor();
        if (!actor.equals(context.customerActor()) && !actor.equals(context.providerActor())) { throw ConversationFailure.unavailable(); }
        SubjectId subject = actor.equals(context.customerActor()) ? context.customer() : context.provider();
        if (jobs.discover(context.jobId()).accepted()) {
            if (!historical(file.content(), subject).filter(view -> view.attachments().contains(file)).isPresent()) { throw ConversationFailure.unavailable(); }
        } else if (!privateView(file.content(), actor, subject).attachments().contains(file)) { throw ConversationFailure.unavailable(); }
    }

    @Override public Set<SurfaceKind> noticeKinds() { return Set.of(SurfaceKind.PRIVATE_MESSAGE); }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public boolean available(ContentReference reference, ActorId recipient) {
        if (!noticeKinds().contains(reference.ownerKind()) || !store.owns(reference)) { throw ConversationFailure.unavailable(); }
        var discovered = store.resource(reference.resourceId(), false);
        if (discovered.thread() == null) { throw ConversationFailure.unavailable(); }
        var subject = conversations.noticeSubject(discovered.thread(), recipient);
        if (subject.isEmpty()) { return false; }
        var resource = store.resource(reference.resourceId(), true);
        if (!resource.equals(discovered)) { throw ConversationFailure.unavailable(); }
        var revision = store.revision(resource, reference.revisionId());
        var approval = moderation.approvalsForUpdate(Set.of(reference)).get(reference);
        if (!approval.reference().equals(reference) || !approval.digest().equals(revision.input().digest())
                || !Set.of("PENDING", "MANUAL_REVIEW", "APPROVED", "AWAITING_ELIGIBILITY", "FAILED", "REJECTED", "RECALLED").contains(approval.state())) {
            throw ConversationFailure.unavailable();
        }
        audience(approval.label());
        return visible(resource, revision, approval, Optional.of(recipient), subject);
    }

    private MessageView checked(MessageStore.Resource resource, MessageStore.Revision revision, ModerationAccess.Approval approval,
            Optional<ActorId> actor, Optional<SubjectId> subject) {
        if (!visible(resource, revision, approval, actor, subject)) { throw ConversationFailure.unavailable(); }
        return new MessageView(revision.input().reference(), revision.body(), approval.label(), revision.input().attachments(), resource.version(), 0);
    }
    private boolean visible(MessageStore.Resource resource, MessageStore.Revision revision, ModerationAccess.Approval approval,
            Optional<ActorId> actor, Optional<SubjectId> subject) {
        if (!revision.input().reference().revisionId().equals(resource.approved()) || !approval.state().equals("APPROVED") || !approval.digest().equals(revision.input().digest())
                || !approval.reference().equals(revision.input().reference()) || (approval.label().equals("ADULT") && subject.isPresent() && actor.isPresent() && !subject.orElseThrow().value().equals(actor.orElseThrow().value()))) {
            return false;
        }
        return permissions.decide(new PermissionRequest(actor, actor.map(value -> new SubjectId(value.value())), audience(approval.label()), Optional.empty())).allowed();
    }
    static Capability audience(String label) {
        return switch (label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; case "ADULT" -> Capability.VIEW_ADULT; default -> throw ConversationFailure.unavailable(); };
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(ConversationFailure::unavailable)); }
    private record Event(long sequence, UUID resource, UUID revision, String kind) {}
    public record MessageView(ContentReference reference, String body, String label, List<FileReference> attachments, long version, long sequence) {
        public MessageView { attachments = List.copyOf(attachments); }
    }
    public record PreparedView(ContentReference reference, long version, String state, String reason, String body, String kind, Optional<UUID> thread, Optional<UUID> listing, boolean visible) {}
    public record PreparedPage(List<PreparedView> entries, Optional<UUID> next) { public PreparedPage { entries = List.copyOf(entries); } }
    private record HistoryCursor(long before, long observed) {
        HistoryCursor {
            if (before <= 0 || observed < before) { throw ConversationFailure.unavailable(); }
        }
        static HistoryCursor parse(String text) {
            if (!text.matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,18}")) { throw ConversationFailure.unavailable(); }
            var parts = text.split(":");
            try { return new HistoryCursor(Long.parseLong(parts[0]), Long.parseLong(parts[1])); }
            catch (NumberFormatException failure) { throw ConversationFailure.unavailable(); }
        }
        String encoded() { return before + ":" + observed; }
    }
    public record HistoryPage(List<MessageView> messages, Optional<String> next, boolean older, boolean laterActivity) {
        public HistoryPage { messages = List.copyOf(messages); }
        public static HistoryPage empty() { return new HistoryPage(List.of(), Optional.empty(), false, false); }
    }
    public record HistoricalPage(List<MessageView> messages, Optional<UUID> next) {
        public HistoricalPage { messages = List.copyOf(messages); }
    }
    public record PollPage(List<MessageView> messages, long next, boolean more) { public PollPage { messages = List.copyOf(messages); } }
}
