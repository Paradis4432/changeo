package ar.changeo.agreements;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.JobAccess;
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
public class AgreementReader implements NoticeOwner {
    private final AgreementStore store;
    private final AgreementAccess agreements;
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final ModerationAccess moderation;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final ObjectProvider<ContentReader> evidence;
    private final ParticipantIdentityAccess participants;
    private final ObjectProvider<AgreementOwner> owners;
    private final TransactionTemplate authorTransaction;
    AgreementReader(AgreementStore store, AgreementAccess agreements, JobAccess jobs, ConversationAccess conversations, ModerationAccess moderation,
            IdentityAccess identity, SecurityProof proof, ObjectProvider<ContentReader> evidence, ParticipantIdentityAccess participants, ObjectProvider<AgreementOwner> owners, PlatformTransactionManager transactions) {
        this.store = store; this.agreements = agreements; this.jobs = jobs; this.conversations = conversations; this.moderation = moderation;
        this.identity = identity; this.proof = proof; this.evidence = evidence;
        this.participants = participants; this.owners = owners; this.authorTransaction = new TransactionTemplate(transactions);
        this.authorTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional public AgreementSnapshot view(ContentReference reference, SubjectId subject) { return visible(reference, actor(), subject, false); }
    @Transactional public Optional<AgreementSnapshot> historical(ContentReference reference, SubjectId subject) { return protectedProjection(reference, actor(), subject, true); }
    @Transactional public CompositionContext composition(UUID jobId, SubjectId subject) {
        var discovered = jobs.discover(jobId);
        conversations.protect(discovered.conversation().id(), actor(), subject, Capability.SEND_MARKETPLACE_MESSAGE);
        var job = jobs.lock(jobId);
        if (!job.conversation().equals(discovered.conversation())) { throw new IllegalStateException("El contexto del presupuesto cambió"); }
        return new CompositionContext(job.id(), job.conversation().mode());
    }
    private AgreementSnapshot visible(ContentReference reference, ActorId actor, SubjectId subject, boolean retained) {
        return protectedProjection(reference, actor, subject, retained).orElseThrow(() -> new IllegalStateException("Audiencia no disponible"));
    }
    private Optional<AgreementSnapshot> protectedProjection(ContentReference reference, ActorId actor, SubjectId subject, boolean retained) {
        var discovered = agreements.discover(reference);
        var job = jobs.discover(discovered.jobId());
        conversations.protect(job.conversation().id(), actor, subject, retained ? Capability.ACCESS_EXISTING_OBLIGATION : Capability.SEND_MARKETPLACE_MESSAGE);
        var protectedJob = jobs.lock(job.id());
        if (retained && !protectedJob.agreement().filter(reference::equals).isPresent()) { throw new IllegalStateException("Presupuesto ajeno a la obligación seleccionada"); }
        var revision = agreements.lock(reference);
        moderation.approvalsForUpdate(Set.of(reference));
        var approval = moderation.approvalForUpdate(reference);
        if (!reference.equals(approval.reference()) || !revision.digest().equals(approval.digest())) { throw new IllegalStateException("Presupuesto exacto incompatible"); }
        if (retained && (!revision.approved() || !approval.state().equals("APPROVED"))) { return Optional.empty(); }
        agreements.requireApproved(revision);
        Capability audience = switch (approval.label()) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; default -> Capability.VIEW_ADULT; };
        if ((retained && !identity.decide(PermissionRequest.self(new AccountId(actor.value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed())
                || (approval.label().equals("ADULT") && !subject.value().equals(actor.value()))
                || !identity.decide(PermissionRequest.self(new AccountId(actor.value()), audience)).allowed()) { return Optional.empty(); }
        return Optional.of(revision);
    }
    public List<ContentReference> candidates(UUID jobId) {
        return store.jdbc.query("select id,approved_revision from agreements_quote where job_id=? and approved_revision is not null order by id::text limit 10",
                (row, index) -> new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject(1, UUID.class), row.getObject(2, UUID.class)), jobId);
    }
    @Transactional public AgreementSnapshot editable(ContentReference reference, SubjectId subject) {
        var discovered = agreements.discover(reference);
        var context = jobs.discover(discovered.jobId()).conversation();
        conversations.protect(context.id(), actor(), subject, Capability.SEND_MARKETPLACE_MESSAGE);
        jobs.lock(discovered.jobId());
        var revision = agreements.lock(reference);
        if (!revision.current() || !revision.author().actor().equals(actor()) || !revision.author().subject().equals(subject)) { throw new IllegalArgumentException("Edición propia actual requerida"); }
        var status = moderation.findApprovalForUpdate(reference);
        String label = status.map(ModerationAccess.Approval::label).orElse(revision.declaredLabel());
        Capability audience = switch (label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; case "ADULT" -> Capability.VIEW_ADULT; default -> throw new IllegalStateException("Clasificación desconocida"); };
        if ((label.equals("ADULT") && !subject.value().equals(actor().value()))
                || !identity.decide(PermissionRequest.self(new AccountId(actor().value()), audience)).allowed()) { throw new IllegalStateException("Audiencia actual requerida para editar"); }
        return revision;
    }
    public List<PreparedQuote> own(UUID jobId, SubjectId subject) { return ownPage(jobId, subject, Optional.empty(), Optional.empty()).entries(); }
    public PreparedPage ownPage(UUID jobId, SubjectId subject, Optional<UUID> selected, Optional<UUID> after) {
        var ids = selected.map(List::of).orElseGet(() -> store.jdbc.query("select q.id from agreements_quote q join agreements_revision r on r.id=q.current_revision where q.job_id=? and r.actor_id=? and r.subject_id=? and (cast(? as text) is null or q.id::text>?) order by q.id::text limit 11",
                (row, index) -> row.getObject(1, UUID.class), jobId, actor().value(), subject.value(), after.map(UUID::toString).orElse(null), after.map(UUID::toString).orElse(null)));
        var entries = ids.stream().limit(10).map(id -> authorTransaction.execute(status -> {
            var quote = store.quote(id, false);
            if (!quote.jobId().equals(jobId)) { throw new IllegalArgumentException("Presupuesto ajeno al trabajo"); }
            var reference = new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, id, quote.current());
            var advisory = agreements.discover(reference);
            if (!advisory.current()) { throw new IllegalArgumentException("Revisión propia actual requerida"); }
            return prepared(advisory, subject);
        })).toList();
        return new PreparedPage(entries, ids.size() > 10 ? Optional.of(ids.get(9)) : Optional.empty());
    }
    private PreparedQuote prepared(AgreementSnapshot advisory, SubjectId subject) {
        var reference = advisory.reference();
        if (!advisory.author().actor().equals(actor()) || !advisory.author().subject().equals(subject)) { throw new IllegalArgumentException("Revisión propia requerida"); }
        var owner = owners.getObject(); var accounts = owner.identityParticipants(reference);
        participants.requireForUpdate(PermissionRequest.self(new AccountId(actor().value()), Capability.ACCESS_SUPPORT), accounts);
        owner.lockRevision(advisory.author(), reference);
        var revision = agreements.discover(reference);
        if (!advisory.equals(revision) || !accounts.equals(owner.identityParticipants(reference))) { throw new IllegalArgumentException("El presupuesto cambió"); }
        var approval = moderation.findApprovalForUpdate(reference);
        approval.ifPresent(value -> { if (!value.reference().equals(reference) || !value.digest().equals(revision.digest())) { throw new IllegalStateException("Presupuesto exacto incompatible"); } });
        String label = approval.map(ModerationAccess.Approval::label).orElse(revision.declaredLabel());
        Capability audience = switch (label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; case "ADULT" -> Capability.VIEW_ADULT; default -> throw new IllegalStateException("Clasificación desconocida"); };
        boolean visible = !(label.equals("ADULT") && !subject.value().equals(actor().value()))
                && identity.decide(PermissionRequest.self(new AccountId(actor().value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed()
                && identity.decide(PermissionRequest.self(new AccountId(actor().value()), audience)).allowed();
        return new PreparedQuote(reference, revision.version(), visible ? Optional.of(revision) : Optional.empty(),
                approval.map(ModerationAccess.Approval::state).orElse("PREPARED_UNSUBMITTED"), approval.map(ModerationAccess.Approval::reason).orElse("SUBMIT_REQUIRED"));
    }
    @Transactional(propagation = Propagation.NEVER)
    public PreparedQuote origin(ContentReference reference) {
        return authorTransaction.execute(status -> {
            var revision = agreements.discover(reference);
            if (revision.author().actor().equals(actor())) { return prepared(revision, revision.author().subject()); }
            var job = jobs.discover(revision.jobId());
            var context = job.conversation();
            var actor = actor();
            if (!actor.equals(context.customerActor()) && !actor.equals(context.providerActor())) { throw ar.changeo.conversations.ConversationFailure.unavailable(); }
            var side = actor.equals(context.customerActor()) ? context.customer() : context.provider();
            boolean retained = job.agreement().filter(reference::equals).isPresent();
            var visible = protectedProjection(reference, actor, side, retained).orElseThrow(ar.changeo.conversations.ConversationFailure::unavailable);
            if (!Set.of("GENERAL", "SENSITIVE", "ADULT").contains(moderation.approvalForUpdate(reference).label())) { throw new IllegalStateException("Clasificación desconocida"); }
            return new PreparedQuote(reference, visible.version(), Optional.of(visible), "APPROVED", "");
        });
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAttachment(FileReference file, FileAccess.Purpose purpose) {
        if (purpose == FileAccess.Purpose.REVIEW) {
            if (!evidence.getObject().evidence(file.content()).attachments().contains(file)) { throw new IllegalArgumentException("Archivo ajeno al presupuesto"); }
        } else {
            var job = jobs.discover(agreements.discover(file.content()).jobId());
            var context = job.conversation();
            var actor = actor();
            if (!actor.equals(context.customerActor()) && !actor.equals(context.providerActor())) { throw new IllegalArgumentException("Participante histórico requerido"); }
            var subject = actor.equals(context.customerActor()) ? context.customer() : context.provider();
            boolean retained = job.agreement().filter(file.content()::equals).isPresent();
            if (!visible(file.content(), actor, subject, retained).attachments().contains(file)) { throw new IllegalArgumentException("Archivo ajeno al presupuesto"); }
        }
    }
    @Override public Set<SurfaceKind> noticeKinds() { return Set.of(SurfaceKind.QUOTE_DESCRIPTION); }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public boolean available(ContentReference reference, ActorId recipient) {
        var discovered = agreements.discover(reference);
        var context = jobs.discover(discovered.jobId()).conversation();
        var subject = conversations.noticeSubject(context.id(), recipient);
        if (subject.isEmpty()) { return false; }
        var job = jobs.lock(discovered.jobId());
        var revision = agreements.lock(reference);
        if (!context.equals(job.conversation()) || !discovered.equals(revision)) { throw new IllegalStateException("El presupuesto cambió"); }
        var approval = moderation.approvalsForUpdate(Set.of(reference)).get(reference);
        if (!approval.reference().equals(reference) || !approval.digest().equals(revision.digest())
                || !Set.of("PENDING", "MANUAL_REVIEW", "APPROVED", "AWAITING_ELIGIBILITY", "FAILED", "REJECTED", "RECALLED").contains(approval.state())) {
            throw new IllegalStateException("Presupuesto exacto incompatible");
        }
        Capability audience = switch (approval.label()) {
            case "GENERAL" -> Capability.BROWSE_GENERAL;
            case "SENSITIVE" -> Capability.VIEW_SENSITIVE;
            case "ADULT" -> Capability.VIEW_ADULT;
            default -> throw new IllegalStateException("Clasificación desconocida");
        };
        if (!revision.approved() || !approval.state().equals("APPROVED")) { return false; }
        agreements.requireApproved(revision);
        return !(approval.label().equals("ADULT") && !subject.orElseThrow().value().equals(recipient.value()))
                && identity.decide(PermissionRequest.self(new AccountId(recipient.value()), audience)).allowed();
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta requerida"))); }
    public record PreparedQuote(ContentReference reference, long version, Optional<AgreementSnapshot> revision, String state, String reason) {}
    public record CompositionContext(UUID jobId, JobContext.FulfillmentMode mode) {}
    public record PreparedPage(List<PreparedQuote> entries, Optional<UUID> next) { public PreparedPage { entries = List.copyOf(entries); } }
}
