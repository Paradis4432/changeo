package ar.changeo.conversations;

import ar.changeo.conversations.api.*;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.listings.api.*;
import ar.changeo.marketplace.PresenceType;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ConversationProjection {
    private final ConversationAccess conversations;
    private final ConversationGuard guard;
    private final ConversationStore store;
    private final ParticipantIdentityAccess identity;
    private final IdentityAccess permissions;
    private final ListingResponseAccess listings;
    private final JobAccess jobs;
    private final ModerationAccess moderation;
    private final TransactionTemplate transaction;

    ConversationProjection(ConversationAccess conversations, ConversationGuard guard, ConversationStore store,
            ParticipantIdentityAccess identity, IdentityAccess permissions, ListingResponseAccess listings,
            JobAccess jobs, ModerationAccess moderation, PlatformTransactionManager transactions) {
        this.conversations = conversations; this.guard = guard; this.store = store; this.identity = identity;
        this.permissions = permissions; this.listings = listings; this.jobs = jobs; this.moderation = moderation;
        this.transaction = new TransactionTemplate(transactions);
    }

    ConversationDisplay view(UUID id, ActorId actor, SubjectId side) {
        return transaction.execute(status -> {
            var discovered = conversations.discover(id);
            var discoveredJob = jobs.discover(discovered.jobId());
            boolean retained = discoveredJob.accepted();
            if (!discovered.participant(side) || !actor.equals(discovered.historicalActor(side))) { throw ConversationFailure.unavailable(); }
            Set<AccountId> participants = new HashSet<>(conversations.participants(id));
            participants.add(new AccountId(actor.value()));
            Set<AccountId> accounts = Set.copyOf(participants);
            var request = retained
                    ? new PermissionRequest(Optional.of(actor), Optional.of(new SubjectId(actor.value())), Capability.ACCESS_EXISTING_OBLIGATION, Optional.of(discovered.jobContext()))
                    : new PermissionRequest(Optional.of(actor), Optional.of(side), Capability.SEND_MARKETPLACE_MESSAGE, Optional.empty());
            identity.requireForUpdate(request, accounts);
            if (retained) { requireRetained(discovered, discoveredJob, actor, side); }
            else if (!guard.currentParticipants(discovered)) { throw ConversationFailure.unavailable(); }
            var listing = listings.prepareResponse(discovered.listing());
            requireCapture(discovered, listing);
            var context = conversations.lock(id);
            if (!context.equals(discovered) || !accounts.equals(withCaller(conversations.participants(id), actor))) { throw ConversationFailure.unavailable(); }
            var job = retained ? jobs.lock(context.jobId()) : jobs.discover(context.jobId());
            if (!job.equals(discoveredJob)) { throw ConversationFailure.unavailable(); }
            if (retained) { requireRetained(context, job, actor, side); }
            else if (!guard.currentParticipants(context)) { throw ConversationFailure.unavailable(); }
            boolean normal = guard.currentParticipants(context) && guard.currentAudience(context, context.listingLabel())
                    && permissions.decide(new PermissionRequest(Optional.of(actor), Optional.of(side), Capability.SEND_MARKETPLACE_MESSAGE, Optional.empty())).allowed();
            var approvals = moderation.approvalsForUpdate(Set.of(context.listing()));
            var approval = approvals.get(context.listing());
            if (!approvals.keySet().equals(Set.of(context.listing())) || !approval.reference().equals(context.listing())
                    || !approval.digest().equals(context.listingDigest())
                    || !Set.of("PENDING", "MANUAL_REVIEW", "APPROVED", "REJECTED", "FAILED", "AWAITING_ELIGIBILITY", "RECALLED").contains(approval.state())) {
                throw ConversationFailure.unavailable();
            }
            MessageReader.audience(approval.label());
            boolean audience = retained
                    ? permissions.decide(PermissionRequest.self(new AccountId(actor.value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed()
                        && guard.sideAudience(actor, side, approval.label())
                    : guard.currentAudience(context, approval.label());
            Optional<ConversationDisplay.CapturedListing> capture = approval.state().equals("APPROVED") && audience
                    ? Optional.of(new ConversationDisplay.CapturedListing(context.listingTitle(), context.listingSnapshot(), approval.label())) : Optional.empty();
            return new ConversationDisplay(context.id(), context.jobId(), context.listing(), context.customer(), context.provider(), context.mode(),
                    job.state(), job.accepted(), normal, capture);
        });
    }

    private Set<AccountId> withCaller(Set<AccountId> participants, ActorId actor) {
        Set<AccountId> accounts = new HashSet<>(participants); accounts.add(new AccountId(actor.value())); return Set.copyOf(accounts);
    }

    private void requireRetained(ConversationContext context, JobSnapshot job, ActorId actor, SubjectId side) {
        if (!job.conversation().equals(context) || !job.accepted() || job.state() == JobSnapshot.State.NEGOTIATING
                || !store.member(context, side) || !actor.equals(context.historicalActor(side))
                || (side.equals(context.customer()) && !job.legalPayer().filter(payer -> payer.value().equals(actor.value())).isPresent())) {
            throw ConversationFailure.unavailable();
        }
    }

    private void requireCapture(ConversationContext context, ListingResponse response) {
        var listing = response.listing();
        SubjectId publisher = listing.type() == PresenceType.OFFER ? context.provider() : context.customer();
        if (!Set.of(PresenceType.OFFER, PresenceType.REQUEST).contains(listing.type()) || !listing.reference().equals(context.listing())
                || !response.digest().equals(context.listingDigest()) || !listing.subject().value().equals(publisher.value())
                || !response.publisher().equals(context.historicalActor(publisher)) || !listing.details().modes().contains(context.mode())
                || !listing.details().title().equals(context.listingTitle()) || !listing.details().reviewText(listing.type()).equals(context.listingSnapshot())) {
            throw ConversationFailure.unavailable();
        }
    }
}
