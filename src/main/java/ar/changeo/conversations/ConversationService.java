package ar.changeo.conversations;

import ar.changeo.conversations.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.listings.api.*;
import ar.changeo.marketplace.PresenceType;
import ar.changeo.moderation.api.*;
import ar.changeo.subscriptions.api.ProviderEntitlementAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConversationService {
    private final ConversationStore store;
    private final ConversationAccess conversations;
    private final ParticipantIdentityAccess identity;
    private final ListingAccess listings;
    private final ListingResponseAccess responses;
    private final ModerationAccess moderation;
    private final JobAccess jobs;
    private final SecurityProof proof;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ConversationProjection projection;
    private final ProviderEntitlementAccess entitlements;

    ConversationService(ConversationStore store, ConversationAccess conversations, ParticipantIdentityAccess identity,
            ListingAccess listings, ListingResponseAccess responses, ModerationAccess moderation, JobAccess jobs,
            SecurityProof proof, Clock clock, PlatformTransactionManager transactions, ConversationProjection projection, ProviderEntitlementAccess entitlements) {
        this.store = store; this.conversations = conversations; this.identity = identity; this.listings = listings;
        this.responses = responses; this.moderation = moderation; this.jobs = jobs; this.proof = proof;
        this.clock = clock; this.transaction = new TransactionTemplate(transactions);
        this.projection = projection;
        this.entitlements = entitlements;
    }

    public UUID respond(UUID listingId, SubjectId respondingSubject, JobContext.FulfillmentMode mode) {
        var advisory = transaction.execute(status -> listings.currentPublic(listingId));
        return transaction.execute(status -> {
            ActorId actor = actor();
            SubjectId publisher = new SubjectId(advisory.subject().value());
            SubjectId customer = advisory.type() == PresenceType.OFFER ? respondingSubject : publisher;
            SubjectId provider = advisory.type() == PresenceType.OFFER ? publisher : respondingSubject;
            if (customer.equals(provider)) { throw ConversationFailure.unavailable(); }
            var discoveredThreads = store.jdbc.query("select id from conversations_thread where listing_id=? and customer_id=? and provider_id=?", (row, index) -> row.getObject(1, UUID.class), listingId, customer.value(), provider.value());
            if (discoveredThreads.size() > 1) { throw ConversationFailure.unavailable(); }
            var entitlement = entitlements.discover(Set.of(provider));
            var controllers = identity.controllers(Set.of(customer, provider));
            if (!controllers.keySet().equals(Set.of(customer, provider))) { throw ConversationFailure.unavailable(); }
            Set<AccountId> accounts = new HashSet<>(List.of(new AccountId(customer.value()), new AccountId(provider.value())));
            controllers.values().forEach(controller -> accounts.add(new AccountId(controller.value())));
            accounts.addAll(entitlement.requiredAccountIds()); accounts.add(new AccountId(actor.value())); accounts.add(new AccountId(respondingSubject.value()));
            for (UUID thread : discoveredThreads) { accounts.addAll(conversations.participants(thread)); }
            Capability responseCapability = advisory.type() == PresenceType.OFFER ? Capability.NEW_REQUEST : Capability.NEW_PROVIDER_PARTICIPATION;
            identity.requireForUpdate(new PermissionRequest(Optional.of(actor), Optional.of(respondingSubject), responseCapability, Optional.empty()), Set.copyOf(accounts));
            if (!actor.equals(controllers.get(respondingSubject))) { throw ConversationFailure.unavailable(); }
            for (var entry : controllers.entrySet()) {
                identity.requireForUpdate(new PermissionRequest(Optional.of(entry.getValue()), Optional.of(entry.getKey()),
                        entry.getKey().equals(customer) ? Capability.NEW_REQUEST : Capability.NEW_PROVIDER_PARTICIPATION, Optional.empty()), Set.copyOf(accounts));
            }
            if (discoveredThreads.isEmpty()) { entitlements.requireNewParticipationForUpdate(entitlement, Set.copyOf(accounts)); }
            var prepared = responses.prepareResponse(advisory.reference());
            if (!prepared.listing().subject().equals(advisory.subject()) || !prepared.publisher().equals(controllers.get(publisher))
                    || !prepared.listing().details().modes().contains(mode)) { throw ConversationFailure.unavailable(); }
            var existing = store.jdbc.queryForList("select id from conversations_thread where listing_id=? and customer_id=? and provider_id=?",
                    listingId, customer.value(), provider.value());
            if (!discoveredThreads.equals(existing.stream().map(row -> (UUID) row.get("id")).toList())) { throw new ConversationFailure("La conversación cambió; volvé a abrir el contacto actual"); }
            UUID id = existing.isEmpty() ? UUID.randomUUID() : (UUID) existing.getFirst().get("id");
            if (!existing.isEmpty()) {
                conversations.protect(id, actor, respondingSubject, Capability.SEND_MARKETPLACE_MESSAGE);
                return id;
            }
            UUID jobId = UUID.randomUUID();
            store.jdbc.update("insert into conversations_thread(id,job_id,listing_kind,listing_id,listing_revision,listing_digest,listing_label,listing_title,listing_snapshot,customer_id,provider_id,customer_actor,provider_actor,mode,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, jobId, advisory.reference().ownerKind().name(), listingId, advisory.reference().revisionId(), prepared.digest(), advisory.label(),
                    prepared.listing().details().title(), prepared.listing().details().reviewText(prepared.listing().type()), customer.value(), provider.value(),
                    controllers.get(customer).value(), controllers.get(provider).value(), mode.name(), Timestamp.from(clock.instant()));
            for (SubjectId subject : List.of(customer, provider)) { store.jdbc.update("insert into conversations_membership(thread_id,subject_id) values(?,?)", id, subject.value()); }
            jobs.createProspect(conversations.discover(id));
            conversations.requireEligible(conversations.discover(id));
            moderation.approvalsForUpdate(Set.of(advisory.reference()));
            var open = responses.requireOpen(advisory.reference());
            if (!open.digest().equals(prepared.digest()) || !open.listing().label().equals(advisory.label())) { throw ConversationFailure.unavailable(); }
            return id;
        });
    }

    public ConversationDisplay view(UUID id, SubjectId subject) { return projection.view(id, actor(), subject); }

    public void archive(UUID id, SubjectId subject, boolean archived) {
        transaction.executeWithoutResult(status -> {
            conversations.protect(id, actor(), subject, Capability.SEND_MARKETPLACE_MESSAGE);
            store.jdbc.update("update conversations_membership set archived=? where thread_id=? and subject_id=?", archived, id, subject.value());
        });
    }

    public void block(UUID id, SubjectId subject, boolean blocked) {
        transaction.executeWithoutResult(status -> {
            var context = conversations.discover(id);
            if (!context.participant(subject) || !actor().equals(context.historicalActor(subject))) { throw ConversationFailure.unavailable(); }
            var participants = conversations.participants(id);
            identity.requireForUpdate(new PermissionRequest(Optional.of(actor()), Optional.of(subject), Capability.SEND_MARKETPLACE_MESSAGE, Optional.empty()), participants);
            var protectedContext = conversations.lock(id);
            if (!protectedContext.equals(context) || !participants.equals(conversations.participants(id)) || !store.member(protectedContext, subject)) { throw ConversationFailure.unavailable(); }
            var other = subject.equals(context.customer()) ? context.provider() : context.customer();
            if (blocked) { store.jdbc.update("insert into conversations_block values(?,?) on conflict do nothing", subject.value(), other.value()); }
            else { store.jdbc.update("delete from conversations_block where subject_id=? and blocked_subject=?", subject.value(), other.value()); }
        });
    }

    public void leave(UUID id, SubjectId subject) {
        transaction.executeWithoutResult(status -> {
            conversations.protect(id, actor(), subject, Capability.SEND_MARKETPLACE_MESSAGE);
            var context = conversations.discover(id);
            if (jobs.lock(context.jobId()).accepted()) { throw new ConversationFailure("Un acuerdo seleccionado conserva su membresía para obligaciones y ayuda"); }
            store.jdbc.update("update conversations_membership set active=false where thread_id=? and subject_id=?", id, subject.value());
            store.jdbc.update("update conversations_thread set version=version+1 where id=?", id);
        });
    }

    public List<ConversationDisplay> inbox(SubjectId subject) {
        var ids = store.jdbc.query("select t.id from conversations_thread t join conversations_membership m on m.thread_id=t.id where m.subject_id=? and m.active and not m.archived order by t.created_at desc limit 20", (row, index) -> row.getObject(1, UUID.class), subject.value());
        return ids.stream().map(id -> view(id, subject)).toList();
    }

    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(ConversationFailure::unavailable)); }
}
