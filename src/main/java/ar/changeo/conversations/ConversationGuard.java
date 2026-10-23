package ar.changeo.conversations;

import ar.changeo.conversations.api.*;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.api.ContentReference;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ConversationGuard implements ConversationAccess {
    private final ConversationStore store;
    private final ParticipantIdentityAccess identity;
    private final IdentityAccess permissions;
    private final ObjectProvider<JobAccess> jobs;

    ConversationGuard(ConversationStore store, ParticipantIdentityAccess identity, IdentityAccess permissions, ObjectProvider<JobAccess> jobs) {
        this.store = store; this.identity = identity; this.permissions = permissions; this.jobs = jobs;
    }

    @Override public ConversationContext discover(UUID threadId) { return store.thread(threadId, false); }

    @Override public Set<AccountId> participants(UUID threadId) {
        var context = discover(threadId);
        Set<AccountId> accounts = new HashSet<>(List.of(new AccountId(context.customer().value()), new AccountId(context.provider().value()),
                new AccountId(context.customerActor().value()), new AccountId(context.providerActor().value())));
        identity.controllers(Set.of(context.customer(), context.provider())).values().forEach(actor -> accounts.add(new AccountId(actor.value())));
        jobs.getObject().discover(context.jobId()).legalPayer().ifPresent(accounts::add);
        return Set.copyOf(accounts);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public ConversationContext lock(UUID threadId) { return store.thread(threadId, true); }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public PermissionDecision protect(UUID threadId, ActorId actor, SubjectId subject, Capability capability) {
        var discovered = discover(threadId);
        var accounts = participants(threadId);
        var decision = authorize(threadId, actor, subject, capability);
        var protectedContext = lock(threadId);
        boolean membership = historical(capability) ? store.member(protectedContext, subject) : store.members(protectedContext);
        if (!discovered.equals(protectedContext) || !accounts.equals(participants(threadId)) || !membership) {
            throw ConversationFailure.unavailable();
        }
        if (!historical(capability)) { requireCurrent(protectedContext); }
        return decision;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public PermissionDecision authorize(UUID threadId, ActorId actor, SubjectId subject, Capability capability) {
        var discovered = discover(threadId);
        if (!discovered.participant(subject)) { throw ConversationFailure.unavailable(); }
        var accounts = participants(threadId);
        var decision = identity.requireForUpdate(permission(discovered, actor, subject, capability), accounts);
        if (historical(capability)) {
            var job = jobs.getObject().discover(discovered.jobId());
            if (!retainedSide(discovered, job, actor, subject)) { throw ConversationFailure.unavailable(); }
        } else {
            requireCurrent(discovered);
            if (!actor.equals(discovered.historicalActor(subject))) { throw ConversationFailure.unavailable(); }
        }
        return decision;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrent(ConversationContext context) {
        requireEligible(context);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void requireEligible(ConversationContext context) {
        if (!currentParticipants(context) || !currentAudience(context, context.listingLabel())) { throw ConversationFailure.unavailable(); }
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SubjectId> noticeSubject(UUID threadId, ActorId recipient) {
        var discovered = discover(threadId);
        var declared = noticeParticipants(threadId, recipient);
        identity.requireForUpdate(PermissionRequest.self(new AccountId(recipient.value()), Capability.ACCESS_SUPPORT), declared);
        if (!declared.equals(noticeParticipants(threadId, recipient))) { throw ConversationFailure.unavailable(); }
        var context = lock(threadId);
        if (!discovered.equals(context) || !declared.equals(noticeParticipants(threadId, recipient))) { throw ConversationFailure.unavailable(); }
        MessageReader.audience(context.listingLabel());
        if (!currentParticipants(context) || !currentAudience(context, context.listingLabel())) { return Optional.empty(); }
        return List.of(context.customer(), context.provider()).stream()
                .filter(subject -> recipient.equals(context.historicalActor(subject)))
                .filter(subject -> permissions.decide(permission(context, recipient, subject, Capability.SEND_MARKETPLACE_MESSAGE)).allowed())
                .findFirst();
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void requireReportable(UUID threadId, ActorId reporter, String effectiveLabel, Optional<ContentReference> terms) {
        MessageReader.audience(effectiveLabel);
        var context = discover(threadId);
        var job = jobs.getObject().discover(context.jobId());
        if (!job.conversation().equals(context)) { throw ConversationFailure.unavailable(); }
        boolean retained = job.accepted() && job.state() != JobSnapshot.State.NEGOTIATING
                && terms.map(reference -> job.agreement().filter(reference::equals).isPresent()).orElse(true);
        var capability = retained ? Capability.ACCESS_EXISTING_OBLIGATION : Capability.SEND_MARKETPLACE_MESSAGE;
        if (!retained) { requireCurrent(context); }
        var side = List.of(context.customer(), context.provider()).stream()
                .filter(subject -> reporter.equals(context.historicalActor(subject)))
                .filter(subject -> !retained || retainedSide(context, job, reporter, subject))
                .filter(subject -> sideAudience(reporter, subject, effectiveLabel))
                .filter(subject -> permissions.decide(permission(context, reporter, subject, capability)).allowed())
                .findFirst().orElseThrow(ConversationFailure::unavailable);
        authorize(threadId, reporter, side, capability);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean eligibleForLoyaltyFacts(ConversationContext context, Set<String> approvalLabels) {
        Set<String> labels = new HashSet<>(approvalLabels);
        labels.add(context.listingLabel());
        labels.forEach(MessageReader::audience);
        if (!context.equals(store.thread(context.id(), false))) { throw ConversationFailure.unavailable(); }
        if (!currentParticipants(context)) { return false; }
        return labels.stream().allMatch(label -> currentAudience(context, label));
    }

    boolean currentParticipants(ConversationContext context) {
        var controllers = identity.controllers(Set.of(context.customer(), context.provider()));
        if (!controllers.keySet().equals(Set.of(context.customer(), context.provider()))
                || !context.customerActor().equals(controllers.get(context.customer())) || !context.providerActor().equals(controllers.get(context.provider()))) {
            return false;
        }
        if (!permissions.decide(new PermissionRequest(Optional.of(context.customerActor()), Optional.of(context.customer()), Capability.NEW_REQUEST, Optional.empty())).allowed()
                || !permissions.decide(new PermissionRequest(Optional.of(context.providerActor()), Optional.of(context.provider()), Capability.NEW_PROVIDER_PARTICIPATION, Optional.empty())).allowed()) {
            return false;
        }
        return store.members(context);
    }

    boolean currentAudience(ConversationContext context, String label) {
        for (var subject : List.of(context.customer(), context.provider())) {
            var actor = context.historicalActor(subject);
            if (!sideAudience(actor, subject, label)) { return false; }
        }
        return true;
    }

    boolean sideAudience(ActorId actor, SubjectId subject, String label) {
        return !(label.equals("ADULT") && !actor.value().equals(subject.value()))
                && permissions.decide(PermissionRequest.self(new AccountId(actor.value()), MessageReader.audience(label))).allowed();
    }

    private Set<AccountId> noticeParticipants(UUID threadId, ActorId recipient) {
        Set<AccountId> accounts = new HashSet<>(participants(threadId));
        accounts.add(new AccountId(recipient.value()));
        return Set.copyOf(accounts);
    }

    private boolean retainedSide(ConversationContext context, JobSnapshot job, ActorId actor, SubjectId subject) {
        return actor.equals(context.historicalActor(subject)) && store.member(context, subject) && job.accepted()
                && job.state() != JobSnapshot.State.NEGOTIATING
                && (!subject.equals(context.customer()) || job.legalPayer().filter(payer -> payer.value().equals(actor.value())).isPresent());
    }

    private static PermissionRequest permission(ConversationContext context, ActorId actor, SubjectId subject, Capability capability) {
        return new PermissionRequest(Optional.of(actor), Optional.of(historical(capability) ? new SubjectId(actor.value()) : subject), capability,
                Set.of(Capability.ACCEPT_AGREEMENT, Capability.SYNTHETIC_FUNDING_ELIGIBILITY, Capability.ACCESS_EXISTING_OBLIGATION).contains(capability)
                        ? Optional.of(context.jobContext()) : Optional.empty());
    }

    private static boolean historical(Capability capability) { return capability == Capability.ACCESS_SUPPORT || capability == Capability.ACCESS_EXISTING_OBLIGATION; }
}
