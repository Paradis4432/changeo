package ar.changeo.jobs;

import ar.changeo.agreements.api.AgreementAccess;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
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
public class JobContentReader implements NoticeOwner {
    private final JobContentStore content;
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final AgreementAccess agreements;
    private final ModerationAccess moderation;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final ObjectProvider<JobContentOwner> owners;
    private final ObjectProvider<ContentReader> evidence;
    private final ParticipantIdentityAccess participants;
    private final TransactionTemplate authorTransaction;
    JobContentReader(JobContentStore content, JobAccess jobs, ConversationAccess conversations, AgreementAccess agreements,
            ModerationAccess moderation, IdentityAccess identity, SecurityProof proof, ObjectProvider<JobContentOwner> owners, ObjectProvider<ContentReader> evidence, ParticipantIdentityAccess participants, PlatformTransactionManager transactions) {
        this.content = content; this.jobs = jobs; this.conversations = conversations; this.agreements = agreements; this.moderation = moderation;
        this.identity = identity; this.proof = proof; this.owners = owners; this.evidence = evidence;
        this.participants = participants; this.authorTransaction = new TransactionTemplate(transactions);
        this.authorTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional public ContentView view(ContentReference reference, SubjectId subject) { return visible(reference, actor(), subject, false); }
    @Transactional public Optional<ContentView> historical(ContentReference reference, SubjectId subject) { return protectedProjection(reference, actor(), subject, true); }
    private ContentView visible(ContentReference reference, ActorId actor, SubjectId subject, boolean retained) {
        return protectedProjection(reference, actor, subject, retained).orElseThrow(() -> new IllegalStateException("Audiencia no disponible"));
    }
    private Optional<ContentView> protectedProjection(ContentReference reference, ActorId actor, SubjectId subject, boolean retained) {
        var discovered = content.resource(reference.resourceId(), false);
        var context = jobs.discover(discovered.jobId()).conversation();
        conversations.protect(context.id(), actor, subject, retained ? Capability.ACCESS_EXISTING_OBLIGATION : Capability.SEND_MARKETPLACE_MESSAGE);
        var job = jobs.lock(discovered.jobId());
        if (retained && !job.agreement().filter(discovered.terms()::equals).isPresent()) { throw new IllegalStateException("Evidencia ajena a la obligación seleccionada"); }
        var canonical = content.lock(discovered);
        var revision = content.revision(canonical.resource(), reference.revisionId());
        owners.getObject().prepareActivation(reference);
        var approvals = moderation.approvalsForUpdate(Set.of(reference, canonical.resource().terms()));
        var termsApproval = approvals.get(canonical.resource().terms());
        if (!termsApproval.reference().equals(canonical.terms().reference()) || !termsApproval.digest().equals(canonical.terms().digest())) { throw new IllegalStateException("Términos exactos incompatibles"); }
        if (retained && (!canonical.terms().approved() || !termsApproval.state().equals("APPROVED"))) { return Optional.empty(); }
        agreements.requireApproved(canonical.terms());
        var approval = approvals.get(reference);
        if (!reference.equals(canonical.resource().reference(reference.revisionId())) || !reference.revisionId().equals(canonical.resource().approved())
                || !approval.state().equals("APPROVED") || !approval.digest().equals(revision.input().digest())) {
            throw new IllegalStateException("Contenido exacto no disponible");
        }
        if (canonical.resource().kind() == JobContentStore.Kind.REVIEW && job.state() != JobSnapshot.State.COMPLETED) { return Optional.empty(); }
        Capability audience = switch (approval.label()) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; default -> Capability.VIEW_ADULT; };
        if ((retained && !identity.decide(PermissionRequest.self(new AccountId(actor.value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed())
                || (approval.label().equals("ADULT") && !actor.value().equals(subject.value()))
                || !identity.decide(PermissionRequest.self(new AccountId(actor.value()), audience)).allowed()) { return Optional.empty(); }
        return Optional.of(new ContentView(reference, canonical.resource().kind().name(), revision.body(), revision.score(), approval.label(), revision.input().attachments(), canonical.resource().version()));
    }
    public List<ContentReference> candidates(UUID job) {
        return content.jdbc.query("select id,kind,approved_revision from jobs_authored where job_id=? and approved_revision is not null order by id::text limit 10",
                (row, index) -> new ContentReference(JobContentStore.Kind.valueOf(row.getString(2)).surface(), row.getObject(1, UUID.class), row.getObject(3, UUID.class)), job);
    }
    public Optional<UUID> reviewResource(UUID jobId, SubjectId subject) {
        return content.jdbc.query("select id from jobs_authored where job_id=? and kind='REVIEW' and actor_id=? and subject_id=?",
                (row, index) -> row.getObject(1, UUID.class), jobId, actor().value(), subject.value()).stream().findFirst();
    }
    public List<PreparedContent> own(UUID jobId, SubjectId subject) { return ownPage(jobId, subject, Optional.empty(), Optional.empty()).entries(); }
    public PreparedPage ownPage(UUID jobId, SubjectId subject, Optional<UUID> selected, Optional<UUID> after) {
        var ids = selected.map(List::of).orElseGet(() -> content.jdbc.query("select id from jobs_authored where job_id=? and actor_id=? and subject_id=? and current_revision is not null and (cast(? as text) is null or id::text>?) order by id::text limit 11",
                (row, index) -> row.getObject(1, UUID.class), jobId, actor().value(), subject.value(), after.map(UUID::toString).orElse(null), after.map(UUID::toString).orElse(null)));
        var entries = ids.stream().limit(10).map(id -> authorTransaction.execute(status -> {
            var discovered = content.resource(id, false);
            if (!discovered.jobId().equals(jobId) || !discovered.actor().equals(actor().value()) || !discovered.subject().equals(subject.value())) { throw new IllegalArgumentException("Revisión propia del trabajo requerida"); }
            var reference = discovered.reference(discovered.current());
            var owner = owners.getObject(); var accounts = owner.identityParticipants(reference);
            participants.requireForUpdate(PermissionRequest.self(new AccountId(actor().value()), Capability.ACCESS_SUPPORT), accounts);
            var advisory = content.revision(discovered, discovered.current());
            var input = owner.lockRevision(advisory.author(), reference);
            var resource = content.resource(id, false);
            if (!discovered.equals(resource) || !input.equals(advisory.input()) || !accounts.equals(owner.identityParticipants(reference))) { throw new IllegalArgumentException("La revisión del trabajo cambió"); }
            var approval = moderation.findApprovalForUpdate(reference);
            approval.ifPresent(value -> { if (!value.reference().equals(reference) || !value.digest().equals(input.digest())) { throw new IllegalStateException("Evidencia exacta incompatible"); } });
            String label = approval.map(ModerationAccess.Approval::label).orElse(input.declaredLabel());
            Capability audience = switch (label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; case "ADULT" -> Capability.VIEW_ADULT; default -> throw new IllegalStateException("Clasificación desconocida"); };
            boolean visible = !(label.equals("ADULT") && !subject.value().equals(actor().value()))
                    && identity.decide(PermissionRequest.self(new AccountId(actor().value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed()
                    && identity.decide(PermissionRequest.self(new AccountId(actor().value()), audience)).allowed();
            return new PreparedContent(reference, resource.version(), approval.map(ModerationAccess.Approval::state).orElse("PREPARED_UNSUBMITTED"),
                    approval.map(ModerationAccess.Approval::reason).orElse("SUBMIT_REQUIRED"), visible ? advisory.body() : "Contenido oculto por la audiencia actual", resource.kind().name(), visible ? advisory.score() : null, visible);
        })).toList();
        return new PreparedPage(entries, ids.size() > 10 ? Optional.of(ids.get(9)) : Optional.empty());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAttachment(FileReference file, FileAccess.Purpose purpose) {
        if (purpose == FileAccess.Purpose.REVIEW) {
            if (!evidence.getObject().evidence(file.content()).attachments().contains(file)) { throw new IllegalArgumentException("Archivo ajeno al trabajo"); }
        } else {
            var job = jobs.discover(content.resource(file.content().resourceId(), false).jobId());
            var context = job.conversation();
            var actor = actor();
            if (!actor.equals(context.customerActor()) && !actor.equals(context.providerActor())) { throw new IllegalArgumentException("Participante histórico requerido"); }
            if (!visible(file.content(), actor, actor.equals(context.customerActor()) ? context.customer() : context.provider(), job.accepted()).attachments().contains(file)) {
                throw new IllegalArgumentException("Archivo ajeno al trabajo");
            }
        }
    }
    @Override public Set<SurfaceKind> noticeKinds() { return Set.of(SurfaceKind.JOB_FEEDBACK, SurfaceKind.COMPLETED_JOB_REVIEW); }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void requireNotice(ContentReference reference, ActorId recipient) {
        var context = jobs.discover(content.resource(reference.resourceId(), false).jobId()).conversation();
        visible(reference, recipient, recipient.equals(context.customerActor()) ? context.customer() : context.provider(), false);
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta requerida"))); }
    public record ContentView(ContentReference reference, String kind, String body, Integer score, String label, List<FileReference> attachments, long version) {
        public ContentView { attachments = List.copyOf(attachments); }
    }
    public record PreparedContent(ContentReference reference, long version, String state, String reason, String body, String kind, Integer score, boolean visible) {}
    public record PreparedPage(List<PreparedContent> entries, Optional<UUID> next) { public PreparedPage { entries = List.copyOf(entries); } }
}
