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
    private static final int EVIDENCE_PAGE_SIZE = 10;
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
    @Transactional public Optional<ContentView> display(UUID jobId, ContentReference reference, SubjectId subject) {
        if (!content.resource(reference.resourceId(), false).jobId().equals(jobId)) { throw new IllegalArgumentException("Evidencia ajena al trabajo"); }
        return protectedProjection(reference, actor(), subject, false, true);
    }
    @Transactional public Optional<ContentView> historical(ContentReference reference, SubjectId subject) { return protectedProjection(reference, actor(), subject, true); }
    private ContentView visible(ContentReference reference, ActorId actor, SubjectId subject, boolean retained) {
        return protectedProjection(reference, actor, subject, retained).orElseThrow(() -> new IllegalStateException("Audiencia no disponible"));
    }
    private Optional<ContentView> protectedProjection(ContentReference reference, ActorId actor, SubjectId subject, boolean retained) {
        return protectedProjection(reference, actor, subject, retained, false);
    }
    private Optional<ContentView> protectedProjection(ContentReference reference, ActorId actor, SubjectId subject, boolean retained, boolean optionalDisplay) {
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
        if (!reference.equals(canonical.resource().reference(reference.revisionId())) || !approval.reference().equals(reference)
                || !approval.digest().equals(revision.input().digest())) { throw new IllegalStateException("Contenido exacto incompatible"); }
        requireKnownApproval(approval);
        if (!reference.revisionId().equals(canonical.resource().approved()) || !approval.state().equals("APPROVED")) {
            if (optionalDisplay) { return Optional.empty(); }
            throw new IllegalStateException("Contenido exacto no disponible");
        }
        if (canonical.resource().kind() == JobContentStore.Kind.REVIEW && job.state() != JobSnapshot.State.COMPLETED) { return Optional.empty(); }
        Capability audience = switch (approval.label()) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; default -> Capability.VIEW_ADULT; };
        if ((retained && !identity.decide(PermissionRequest.self(new AccountId(actor.value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed())
                || (approval.label().equals("ADULT") && !actor.value().equals(subject.value()))
                || !identity.decide(PermissionRequest.self(new AccountId(actor.value()), audience)).allowed()) { return Optional.empty(); }
        return Optional.of(new ContentView(reference, canonical.resource().kind().name(), revision.body(), revision.score(), approval.label(), revision.input().attachments(), canonical.resource().version()));
    }
    @Transactional public void requireDraftAccess(UUID jobId, SubjectId subject) {
        var discovered = jobs.discover(jobId);
        conversations.protect(discovered.conversation().id(), actor(), subject, Capability.SEND_MARKETPLACE_MESSAGE);
        jobs.lock(jobId).agreement().orElseThrow(() -> new IllegalStateException("Falta el acuerdo bilateral"));
    }
    public List<ContentReference> candidates(UUID job) {
        return content.jdbc.query("select id,kind,approved_revision from jobs_authored where job_id=? and approved_revision is not null order by id::text limit 10",
                (row, index) -> new ContentReference(JobContentStore.Kind.valueOf(row.getString(2)).surface(), row.getObject(1, UUID.class), row.getObject(3, UUID.class)), job);
    }
    public List<ContentReference> candidates(UUID job, Optional<ContentReference> selectedFinal) {
        Set<ContentReference> references = new LinkedHashSet<>();
        selectedFinal.ifPresent(references::add);
        references.addAll(candidates(job));
        return List.copyOf(references);
    }
    @Transactional(propagation = Propagation.NEVER)
    public EvidencePage evidencePage(UUID jobId, SubjectId subject, Optional<String> after, Optional<String> before) {
        return evidencePage(jobId, subject, after, before, false);
    }
    @Transactional(propagation = Propagation.NEVER)
    public EvidencePage historicalPage(UUID jobId, SubjectId subject, Optional<String> after, Optional<String> before) {
        return evidencePage(jobId, subject, after, before, true);
    }
    private EvidencePage evidencePage(UUID jobId, SubjectId subject, Optional<String> after, Optional<String> before, boolean retained) {
        var actor = actor();
        var selected = authorTransaction.execute(status -> selectEvidence(jobId, actor, subject, after, before, retained));
        List<ContentView> entries = new ArrayList<>();
        for (var reference : selected.references()) {
            authorTransaction.execute(status -> protectedProjection(reference, actor, subject, retained, true)).ifPresent(entries::add);
        }
        return new EvidencePage(entries, selected.previous(), selected.next(), after.isPresent() || before.isPresent());
    }
    private EvidenceSelection selectEvidence(UUID jobId, ActorId actor, SubjectId subject, Optional<String> after, Optional<String> before, boolean retained) {
        var context = jobs.discover(jobId).conversation();
        conversations.protect(context.id(), actor, subject, retained ? Capability.ACCESS_EXISTING_OBLIGATION : Capability.SEND_MARKETPLACE_MESSAGE);
        var job = jobs.lock(jobId);
        if (!job.conversation().equals(context) || (retained && !job.accepted())) { throw new IllegalArgumentException("Trabajo seleccionado requerido"); }
        if (after.isPresent() && before.isPresent()) { throw new IllegalArgumentException("Elegí una sola dirección de evidencia"); }
        var cursor = after.or(() -> before).map(value -> {
            if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("Referencia de evidencia inválida");
            }
            UUID id = UUID.fromString(value);
            if (!content.resource(id, false).jobId().equals(jobId)) { throw new IllegalArgumentException("Referencia ajena al trabajo"); }
            return id;
        });
        boolean backwards = before.isPresent();
        String comparison = backwards ? "<" : ">";
        var references = content.jdbc.query("select id,kind,approved_revision from jobs_authored where job_id=? and approved_revision is not null and (cast(? as text) is null or id::text" + comparison + "?) order by id::text " + (backwards ? "desc" : "asc") + " limit 11",
                (row, index) -> new ContentReference(JobContentStore.Kind.valueOf(row.getString(2)).surface(), row.getObject(1, UUID.class), row.getObject(3, UUID.class)),
                jobId, cursor.map(UUID::toString).orElse(null), cursor.map(UUID::toString).orElse(null));
        boolean more = references.size() > EVIDENCE_PAGE_SIZE;
        var page = new ArrayList<>(references.stream().limit(EVIDENCE_PAGE_SIZE).toList());
        if (backwards) { Collections.reverse(page); }
        Optional<UUID> first = page.isEmpty() ? cursor : Optional.of(page.getFirst().resourceId());
        Optional<UUID> last = page.isEmpty() ? cursor : Optional.of(page.getLast().resourceId());
        return new EvidenceSelection(page, backwards ? (more ? first : Optional.empty()) : (after.isPresent() ? first : Optional.empty()),
                backwards ? last : (more ? last : Optional.empty()));
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
            return prepared(jobId, subject, discovered, discovered.reference(discovered.current()));
        })).toList();
        return new PreparedPage(entries, ids.size() > 10 ? Optional.of(ids.get(9)) : Optional.empty());
    }
    private PreparedContent prepared(UUID jobId, SubjectId subject, JobContentStore.Resource discovered, ContentReference reference) {
        if (!discovered.jobId().equals(jobId) || !discovered.actor().equals(actor().value()) || !discovered.subject().equals(subject.value())) { throw new IllegalArgumentException("Revisión propia del trabajo requerida"); }
        var owner = owners.getObject(); var accounts = owner.identityParticipants(reference);
        participants.requireForUpdate(PermissionRequest.self(new AccountId(actor().value()), Capability.ACCESS_SUPPORT), accounts);
        var advisory = content.revision(discovered, reference.revisionId());
        var input = owner.lockRevision(advisory.author(), reference);
        var resource = content.resource(discovered.id(), false);
        if (!discovered.equals(resource) || !input.equals(advisory.input()) || !accounts.equals(owner.identityParticipants(reference))) { throw new IllegalArgumentException("La revisión del trabajo cambió"); }
        var approval = moderation.findApprovalForUpdate(reference);
        approval.ifPresent(value -> { if (!value.reference().equals(reference) || !value.digest().equals(input.digest())) { throw new IllegalStateException("Evidencia exacta incompatible"); } });
        String label = approval.map(ModerationAccess.Approval::label).orElse(input.declaredLabel());
        Capability audience = switch (label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; case "ADULT" -> Capability.VIEW_ADULT; default -> throw new IllegalStateException("Clasificación desconocida"); };
        boolean visible = !(label.equals("ADULT") && !subject.value().equals(actor().value()))
                && identity.decide(PermissionRequest.self(new AccountId(actor().value()), Capability.SEND_MARKETPLACE_MESSAGE)).allowed()
                && identity.decide(PermissionRequest.self(new AccountId(actor().value()), audience)).allowed();
        return new PreparedContent(reference, resource.version(), approval.map(ModerationAccess.Approval::state).orElse("PREPARED_UNSUBMITTED"),
                approval.map(ModerationAccess.Approval::reason).orElse("SUBMIT_REQUIRED"), visible ? advisory.body() : "Contenido oculto por la audiencia actual", resource.kind().name(), visible ? advisory.score() : null, visible, label);
    }
    @Transactional(propagation = Propagation.NEVER)
    public PreparedContent origin(ContentReference reference) {
        return authorTransaction.execute(status -> {
            var resource = content.resource(reference.resourceId(), false);
            if (!reference.equals(resource.reference(reference.revisionId()))) { throw ar.changeo.conversations.ConversationFailure.unavailable(); }
            var actor = actor();
            if (resource.actor().equals(actor.value())) { return prepared(resource.jobId(), new SubjectId(resource.subject()), resource, reference); }
            var job = jobs.discover(resource.jobId());
            var context = job.conversation();
            if (!actor.equals(context.customerActor()) && !actor.equals(context.providerActor())) { throw ar.changeo.conversations.ConversationFailure.unavailable(); }
            var side = actor.equals(context.customerActor()) ? context.customer() : context.provider();
            var view = protectedProjection(reference, actor, side, job.accepted(), true).orElseThrow(ar.changeo.conversations.ConversationFailure::unavailable);
            return new PreparedContent(reference, view.version(), "APPROVED", "", view.body(), view.kind(), view.score(), true, view.label());
        });
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
    public boolean available(ContentReference reference, ActorId recipient) {
        var owner = owners.getObject();
        if (!owner.owns(reference)) { throw new IllegalArgumentException("Contenido del trabajo incompatible"); }
        var discovered = content.resource(reference.resourceId(), false);
        var context = jobs.discover(discovered.jobId()).conversation();
        var subject = conversations.noticeSubject(context.id(), recipient);
        if (subject.isEmpty()) { return false; }
        var job = jobs.lock(discovered.jobId());
        var canonical = content.lock(discovered);
        if (!context.equals(job.conversation()) || !discovered.equals(canonical.resource())) { throw new IllegalStateException("La evidencia cambió"); }
        var revision = content.revision(canonical.resource(), reference.revisionId());
        boolean preparation = owner.preparationAvailable(reference);
        var approvals = moderation.approvalsForUpdate(Set.of(reference, canonical.resource().terms()));
        var termsApproval = approvals.get(canonical.resource().terms());
        var approval = approvals.get(reference);
        if (!termsApproval.reference().equals(canonical.terms().reference()) || !termsApproval.digest().equals(canonical.terms().digest())
                || !approval.reference().equals(reference) || !approval.digest().equals(revision.input().digest())) {
            throw new IllegalStateException("Contenido exacto incompatible");
        }
        for (var metadata : List.of(termsApproval, approval)) {
            requireKnownApproval(metadata);
        }
        if (!preparation || !canonical.terms().approved() || !termsApproval.state().equals("APPROVED")
                || !reference.revisionId().equals(canonical.resource().approved()) || !approval.state().equals("APPROVED")
                || (canonical.resource().kind() == JobContentStore.Kind.REVIEW && job.state() != JobSnapshot.State.COMPLETED)) { return false; }
        agreements.requireApproved(canonical.terms());
        Capability audience = switch (approval.label()) {
            case "GENERAL" -> Capability.BROWSE_GENERAL;
            case "SENSITIVE" -> Capability.VIEW_SENSITIVE;
            default -> Capability.VIEW_ADULT;
        };
        return !(approval.label().equals("ADULT") && !subject.orElseThrow().value().equals(recipient.value()))
                && identity.decide(PermissionRequest.self(new AccountId(recipient.value()), audience)).allowed();
    }
    private static void requireKnownApproval(ModerationAccess.Approval approval) {
        if (!Set.of("PENDING", "MANUAL_REVIEW", "APPROVED", "AWAITING_ELIGIBILITY", "FAILED", "REJECTED", "RECALLED").contains(approval.state())
                || !Set.of("GENERAL", "SENSITIVE", "ADULT").contains(approval.label())) { throw new IllegalStateException("Estado de evidencia desconocido"); }
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta requerida"))); }
    public record ContentView(ContentReference reference, String kind, String body, Integer score, String label, List<FileReference> attachments, long version) {
        public ContentView { attachments = List.copyOf(attachments); }
    }
    public record PreparedContent(ContentReference reference, long version, String state, String reason, String body, String kind, Integer score, boolean visible, String label) {}
    public record PreparedPage(List<PreparedContent> entries, Optional<UUID> next) { public PreparedPage { entries = List.copyOf(entries); } }
    private record EvidenceSelection(List<ContentReference> references, Optional<UUID> previous, Optional<UUID> next) {
        private EvidenceSelection { references = List.copyOf(references); }
    }
    public record EvidencePage(List<ContentView> entries, Optional<UUID> previous, Optional<UUID> next, boolean selected) {
        public EvidencePage { entries = List.copyOf(entries); }
    }
}
