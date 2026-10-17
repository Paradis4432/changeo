package ar.changeo.jobs;

import ar.changeo.agreements.api.AgreementAccess;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.files.api.*;
import ar.changeo.identity.api.AccountId;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.notifications.api.NotificationAccess;
import ar.changeo.payments.api.JobMoneyStatusAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class JobContentOwner implements ContentOwner {
    private final JobContentStore content;
    private final JobStore store;
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final JobMoneyStatusAccess money;
    private final ObjectProvider<AgreementAccess> agreements;
    private final ObjectProvider<JobContentReader> readers;
    private final ObjectProvider<ModerationAccess> moderation;
    private final NotificationAccess notifications;
    private final Clock clock;
    private final JobParticipation participation;
    JobContentOwner(JobContentStore content, JobStore store, JobAccess jobs, ConversationAccess conversations, JobMoneyStatusAccess money,
            ObjectProvider<AgreementAccess> agreements, ObjectProvider<JobContentReader> readers, NotificationAccess notifications, Clock clock, JobParticipation participation,
            ObjectProvider<ModerationAccess> moderation) {
        this.content = content; this.store = store; this.jobs = jobs; this.conversations = conversations; this.money = money;
        this.agreements = agreements; this.readers = readers; this.notifications = notifications; this.clock = clock;
        this.participation = participation;
        this.moderation = moderation;
    }
    @Override public Set<SurfaceKind> kinds() { return Set.of(SurfaceKind.JOB_FEEDBACK, SurfaceKind.COMPLETED_JOB_REVIEW); }
    @Override public boolean owns(ContentReference reference) {
        return content.jdbc.queryForObject("select count(*) from jobs_authored a join jobs_revision r on r.resource_id=a.id where a.id=? and r.id=? and (a.kind='REVIEW')=?",
                Integer.class, reference.resourceId(), reference.revisionId(), reference.ownerKind() == SurfaceKind.COMPLETED_JOB_REVIEW) == 1 && kinds().contains(reference.ownerKind());
    }
    @Override public Set<AccountId> identityParticipants(ContentReference reference) {
        return conversations.participants(jobs.discover(content.resource(reference.resourceId(), false).jobId()).conversation().id());
    }
    @Override public Set<ContentReference> approvalDependencies(ContentReference reference) { return Set.of(content.resource(reference.resourceId(), false).terms()); }
    @Override public ReviewInput lockRevision(AuthorContext author, ContentReference reference) {
        var discovered = content.resource(reference.resourceId(), false);
        var job = jobs.lock(discovered.jobId());
        var canonical = content.lock(discovered);
        var resource = canonical.resource();
        var revision = content.revision(resource, reference.revisionId());
        if (!reference.equals(resource.reference(reference.revisionId())) || !revision.author().equals(author)
                || !job.conversation().participant(author.subject()) || !job.conversation().historicalActor(author.subject()).equals(author.actor())
                || !job.agreement().filter(resource.terms()::equals).isPresent()) { throw new IllegalArgumentException("Contenido ajeno a los términos del trabajo"); }
        return revision.input();
    }
    @Override public void prepareActivation(ContentReference reference) {
        var resource = content.resource(reference.resourceId(), false);
        var job = jobs.discover(resource.jobId());
        if (!job.agreement().filter(resource.terms()::equals).isPresent()) { throw new IllegalArgumentException("Términos de ejecución incompatibles"); }
        if (resource.kind() != JobContentStore.Kind.SUPPORT_NOTE) {
            var funding = money.currentForUpdate(job.id(), resource.terms()).requireExact(job.id(), resource.terms());
            if (!funding.permitsFundedWork()) {
                throw new ModerationFailure("Integración financiera pendiente: la ejecución requiere financiación completa exacta");
            }
        }
    }
    @Override public boolean current(ContentReference reference) { return reference.revisionId().equals(content.resource(reference.resourceId(), false).current()); }
    @Override public boolean reportable(ContentReference reference) {
        var resource = content.resource(reference.resourceId(), false);
        conversations.requireEligible(jobs.discover(resource.jobId()).conversation());
        return reference.revisionId().equals(resource.approved());
    }
    @Override public long activate(ContentReference reference) {
        var resource = content.resource(reference.resourceId(), false);
        var job = jobs.discover(resource.jobId());
        if (!current(reference)) { throw new IllegalArgumentException("Revisión del trabajo desactualizada"); }
        conversations.requireEligible(job.conversation());
        if (resource.kind() != JobContentStore.Kind.SUPPORT_NOTE) {
            participation.requireContract(job.conversation());
            participation.requireAudience(job.conversation(), moderation.getObject().approvalForUpdate(resource.terms()).label());
        }
        agreements.getObject().requireApproved(agreements.getObject().discover(resource.terms()));
        if (reference.revisionId().equals(resource.approved())) { return resource.version(); }
        var terms = agreements.getObject().discover(resource.terms()).terms();
        switch (resource.kind()) {
            case PROGRESS -> require(job.state() == JobSnapshot.State.IN_PROGRESS);
            case FEEDBACK -> {
                boolean first = store.jdbc.queryForObject("select count(*) from jobs_event where job_id=? and resource_id=? and kind='FEEDBACK_APPROVED'", Integer.class, job.id(), resource.id()) == 0;
                require(job.state() == JobSnapshot.State.IN_PROGRESS && (!first || job.feedbackCount() < terms.feedbackRounds()));
                if (first) {
                    store.jdbc.update("update jobs_job set feedback_count=feedback_count+1,version=version+1 where id=?", job.id());
                    store.event(job.id(), resource.actor(), "FEEDBACK_APPROVED", reference);
                }
            }
            case FINAL_DELIVERY -> {
                require(job.state() == JobSnapshot.State.IN_PROGRESS
                        || (job.state() == JobSnapshot.State.FINAL_DELIVERY_PENDING_REVIEW
                        && job.finalDelivery().map(ContentReference::resourceId).filter(resource.id()::equals).isPresent()));
                var revision = content.revision(resource, reference.revisionId());
                if (terms.completionEvidence() == ar.changeo.agreements.api.AgreementTerms.Evidence.PRIVATE_FILE && revision.input().attachments().isEmpty()) {
                    throw new ModerationFailure("Falta la evidencia privada acordada");
                }
                var now = clock.instant();
                store.jdbc.update("update jobs_job set state='FINAL_DELIVERY_PENDING_REVIEW',final_resource=?,final_revision=?,final_approved_at=?,review_due_at=?,version=version+1 where id=?",
                        resource.id(), reference.revisionId(), Timestamp.from(now), Timestamp.from(terms.dueAt(now)), job.id());
                store.event(job.id(), resource.actor(), "FINAL_DELIVERY_APPROVED", reference);
            }
            case REVIEW -> require(job.state() == JobSnapshot.State.COMPLETED);
            case SUPPORT_NOTE -> require(job.accepted());
        }
        content.jdbc.update("update jobs_authored set approved_revision=?,version=version+1 where id=?", reference.revisionId(), resource.id());
        store.changed(job.conversation());
        notifications.enqueue(reference, Set.of(job.conversation().customerActor(), job.conversation().providerActor()));
        return resource.version() + 1;
    }
    @Override public void recall(ContentReference reference) {
        var resource = content.resource(reference.resourceId(), false);
        int changed = content.jdbc.update("update jobs_authored set approved_revision=null,version=version+1 where id=? and approved_revision=?", resource.id(), reference.revisionId());
        if (changed > 0) {
            var job = jobs.discover(resource.jobId());
            store.changed(job.conversation());
            store.event(job.id(), null, "CONTENT_RECALLED", reference);
        }
    }
    @Override public void requireAttachment(FileReference reference, FileAccess.Purpose purpose) { readers.getObject().requireAttachment(reference, purpose); }
    private static void require(boolean condition) { if (!condition) { throw new ModerationFailure("La etapa no corresponde al estado del trabajo"); } }
}
