package ar.changeo.agreements;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
import ar.changeo.subscriptions.api.ProviderEntitlementAccess;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.notifications.api.NotificationAccess;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@Component
public class AgreementOwner implements ContentOwner, AgreementAccess {
    private final AgreementStore store;
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final ObjectProvider<ModerationAccess> moderation;
    private final ObjectProvider<AgreementReader> readers;
    private final NotificationAccess notifications;
    private final ProviderEntitlementAccess entitlements;
    AgreementOwner(AgreementStore store, JobAccess jobs, ConversationAccess conversations, ObjectProvider<ModerationAccess> moderation,
            ObjectProvider<AgreementReader> readers, NotificationAccess notifications, ProviderEntitlementAccess entitlements) {
        this.store = store; this.jobs = jobs; this.conversations = conversations; this.moderation = moderation; this.readers = readers; this.notifications = notifications;
        this.entitlements = entitlements;
    }
    @Override public Optional<String> caseOrigin(ContentReference reference) {
        return Optional.of("/agreements/origin?kind=" + reference.ownerKind() + "&resource=" + reference.resourceId() + "&revision=" + reference.revisionId());
    }
    @Override public Set<SurfaceKind> kinds() { return Set.of(SurfaceKind.QUOTE_DESCRIPTION); }
    @Override public boolean owns(ContentReference reference) {
        return reference.ownerKind() == SurfaceKind.QUOTE_DESCRIPTION && store.jdbc.queryForObject("select count(*) from agreements_revision where quote_id=? and id=?",
                Integer.class, reference.resourceId(), reference.revisionId()) == 1;
    }
    @Override public Set<AccountId> identityParticipants(ContentReference reference) {
        var quote = store.quote(reference.resourceId(), false);
        var job = jobs.discover(quote.jobId());
        Set<AccountId> accounts = new HashSet<>(conversations.participants(job.conversation().id()));
        if (quote.kind().equals("INITIAL") && !job.accepted()) { accounts.addAll(entitlements.discover(Set.of(job.conversation().provider())).requiredAccountIds()); }
        return Set.copyOf(accounts);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void prepareProtection(PermissionRequest permission, AuthorContext author, ContentReference reference, Set<AccountId> declaredIdentityAccounts) {
        var quote = store.quote(reference.resourceId(), false); var job = jobs.discover(quote.jobId());
        if (permission.capability() != Capability.SEND_MARKETPLACE_MESSAGE) { return; }
        selectionObservations().put(reference, job.accepted());
        if (quote.kind().equals("INITIAL") && !job.accepted()) { entitlements.requireNewParticipationForUpdate(entitlements.discover(Set.of(job.conversation().provider())), declaredIdentityAccounts); }
    }
    @Override public AgreementSnapshot discover(ContentReference reference) {
        if (!owns(reference)) { throw new IllegalArgumentException("Presupuesto ajeno"); }
        return store.revision(store.quote(reference.resourceId(), false), reference.revisionId());
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public AgreementSnapshot lock(ContentReference reference) { return store.revision(store.quote(reference.resourceId(), true), reference.revisionId(), true); }
    @Override public ReviewInput lockRevision(AuthorContext author, ContentReference reference) {
        var discovered = discover(reference);
        var job = jobs.lock(discovered.jobId());
        var selected = selectionObservations().get(reference);
        if (selected != null && selected != job.accepted()) { throw new IllegalStateException("La selección cambió durante la protección del presupuesto"); }
        var revision = lock(reference);
        if (!revision.author().equals(author) || !job.conversation().participant(author.subject()) || !job.conversation().historicalActor(author.subject()).equals(author.actor())) {
            throw new IllegalArgumentException("Autor del presupuesto ajeno al trabajo");
        }
        return new ReviewInput(reference, revision.terms().reviewText(), revision.declaredLabel(), revision.digest(), revision.attachments());
    }
    @Override public boolean current(ContentReference reference) { return discover(reference).current(); }
    @Override public boolean reportable(ContentReference reference, ActorId reporter, String effectiveLabel) {
        var revision = discover(reference);
        conversations.requireReportable(jobs.discover(revision.jobId()).conversation().id(), reporter, effectiveLabel, Optional.of(reference));
        return revision.approved();
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void requireApproved(AgreementSnapshot revision) {
        var current = discover(revision.reference());
        var approval = moderation.getObject().approvalForUpdate(revision.reference());
        if (!current.approved() || !current.digest().equals(revision.digest()) || !approval.state().equals("APPROVED")
                || !approval.digest().equals(revision.digest()) || !approval.reference().equals(revision.reference())) { throw new IllegalStateException("El presupuesto exacto no está aprobado"); }
    }
    @Override public long activate(ContentReference reference) {
        var revision = discover(reference);
        if (!revision.current()) { throw new IllegalArgumentException("Presupuesto desactualizado"); }
        var context = jobs.discover(revision.jobId()).conversation();
        conversations.requireEligible(context);
        if (revision.approved()) { return revision.version(); }
        store.jdbc.update("update agreements_quote set approved_revision=?,version=version+1 where id=?", reference.revisionId(), reference.resourceId());
        notifications.enqueue(reference, Set.of(context.customerActor(), context.providerActor()));
        return revision.version() + 1;
    }
    @Override public void recall(ContentReference reference) {
        store.jdbc.update("update agreements_quote set approved_revision=null,version=version+1 where id=? and approved_revision=?", reference.resourceId(), reference.revisionId());
    }
    @Override public void requireAttachment(FileReference file, FileAccess.Purpose purpose) { readers.getObject().requireAttachment(file, purpose); }

    @SuppressWarnings("unchecked")
    private Map<ContentReference, Boolean> selectionObservations() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Presupuesto sin transacción protegida"); }
        var observations = TransactionSynchronizationManager.getResource(this);
        if (observations != null) { return (Map<ContentReference, Boolean>) observations; }
        Map<ContentReference, Boolean> current = new HashMap<>(); TransactionSynchronizationManager.bindResource(this, current);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(AgreementOwner.this); }
        });
        return current;
    }
}
