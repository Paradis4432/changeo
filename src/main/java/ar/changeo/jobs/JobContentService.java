package ar.changeo.jobs;

import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.marketplace.MarketplaceService.Upload;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobContentService {
    private final JobContentStore content;
    private final JobStore store;
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final ModerationAccess moderation;
    private final FileAccess files;
    private final SecurityProof proof;
    private final Clock clock;
    private final TransactionTemplate transaction;
    JobContentService(JobContentStore content, JobStore store, JobAccess jobs, ConversationAccess conversations, ModerationAccess moderation,
            FileAccess files, SecurityProof proof, Clock clock, PlatformTransactionManager transactions) {
        this.content = content; this.store = store; this.jobs = jobs; this.conversations = conversations; this.moderation = moderation;
        this.files = files; this.proof = proof; this.clock = clock; this.transaction = new TransactionTemplate(transactions);
    }
    public ContentReference save(Draft draft) {
        return transaction.execute(status -> {
            var discovered = jobs.discover(draft.jobId());
            var actor = actor();
            conversations.protect(discovered.conversation().id(), actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE);
            var job = jobs.lock(draft.jobId());
            var terms = job.agreement().orElseThrow(() -> new IllegalStateException("Falta el acuerdo bilateral"));
            var kind = JobContentStore.Kind.valueOf(draft.kind());
            if (draft.body().isBlank() || draft.body().length() > 12000 || draft.body().indexOf('\0') >= 0 || draft.uploads().size() > 4
                    || !Set.of("GENERAL", "SENSITIVE", "ADULT").contains(draft.label()) || ((kind == JobContentStore.Kind.REVIEW) != (draft.score() != null))
                    || (draft.score() != null && (draft.score() < 1 || draft.score() > 5))) { throw new IllegalArgumentException("Contenido del trabajo inválido"); }
            boolean customer = job.conversation().customer().equals(draft.subject());
            boolean finalCorrection = kind == JobContentStore.Kind.FINAL_DELIVERY
                    && job.state() == JobSnapshot.State.FINAL_DELIVERY_PENDING_REVIEW
                    && job.finalDelivery().map(ContentReference::resourceId).equals(draft.resourceId());
            if ((kind == JobContentStore.Kind.FINAL_DELIVERY && customer) || (kind == JobContentStore.Kind.FEEDBACK && !customer)
                    || (kind == JobContentStore.Kind.REVIEW && job.state() != JobSnapshot.State.COMPLETED)
                    || (Set.of(JobContentStore.Kind.PROGRESS, JobContentStore.Kind.FEEDBACK, JobContentStore.Kind.FINAL_DELIVERY).contains(kind)
                    && job.state() != JobSnapshot.State.IN_PROGRESS && !finalCorrection)) { throw new IllegalStateException("Esta parte o etapa no puede publicar ese contenido"); }
            String digest = ContentDigest.bytes((draft.jobId() + ":" + draft.resourceId() + ":" + draft.version() + ":" + draft.subject() + ":" + draft.kind()
                    + ":" + draft.score() + ":" + draft.label() + ":" + draft.body() + ":" + draft.uploads().stream().map(upload -> upload.mediaType() + ":" + ContentDigest.bytes(upload.bytes())).toList()).getBytes(StandardCharsets.UTF_8));
            var receipts = content.jdbc.queryForList("select digest,outcome_id from jobs_receipt where actor_id=? and command_id=?", actor.value(), draft.command());
            if (!receipts.isEmpty()) {
                if (!digest.equals(receipts.getFirst().get("digest"))) { throw new IllegalArgumentException("Comando ligado a otra revisión"); }
                UUID revision = (UUID) receipts.getFirst().get("outcome_id");
                UUID resource = content.jdbc.queryForObject("select resource_id from jobs_revision where id=?", UUID.class, revision);
                return new ContentReference(kind.surface(), resource, revision);
            }
            UUID id = draft.resourceId().orElseGet(UUID::randomUUID);
            UUID target = kind == JobContentStore.Kind.REVIEW ? (customer ? job.conversation().provider().value() : job.conversation().customer().value()) : null;
            JobContentStore.Resource resource;
            if (draft.resourceId().isPresent()) {
                resource = content.lock(content.resource(id, false)).resource();
                if (!resource.jobId().equals(job.id()) || resource.version() != draft.version() || !resource.actor().equals(actor.value())
                        || !resource.subject().equals(draft.subject().value()) || resource.kind() != kind || !resource.terms().equals(terms)) {
                    throw new IllegalArgumentException("Revisión del trabajo desactualizada o ajena");
                }
            } else {
                content.jdbc.update("insert into jobs_authored(id,job_id,kind,actor_id,subject_id,target_subject,term_quote,term_revision) values(?,?,?,?,?,?,?,?)",
                        id, job.id(), kind.name(), actor.value(), draft.subject().value(), target, terms.resourceId(), terms.revisionId());
                resource = content.lock(content.resource(id, false)).resource();
            }
            var reference = resource.reference(UUID.randomUUID());
            var author = new AuthorContext(actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE, Optional.of(job.conversation().jobContext()));
            List<FileReference> attachments = new ArrayList<>();
            for (int index = 0; index < draft.uploads().size(); index++) {
                var upload = draft.uploads().get(index);
                attachments.add(files.intake(UUID.nameUUIDFromBytes((draft.command() + ":" + index).getBytes(StandardCharsets.UTF_8)), author, reference, upload.mediaType(), upload.bytes()));
            }
            attachments.sort(Comparator.comparing(file -> file.id().toString()));
            String text = JobContentStore.reviewText(resource, draft.body(), draft.score());
            content.jdbc.update("insert into jobs_revision values(?,?,?,?,?,?,?)", reference.revisionId(), id, draft.body(), draft.score(), draft.label(),
                    ContentDigest.payload(text, draft.label(), attachments), Timestamp.from(clock.instant()));
            for (var file : attachments) { content.jdbc.update("insert into jobs_attachment values(?,?,?)", reference.revisionId(), file.id(), file.digest()); }
            content.jdbc.update("update jobs_authored set current_revision=?,version=version+1 where id=?", reference.revisionId(), id);
            store.changed(job.conversation());
            content.jdbc.update("insert into jobs_receipt values(?,?,'SAVE',?,?)", actor.value(), draft.command(), digest, reference.revisionId());
            return reference;
        });
    }
    public ModerationAccess.Approval submit(ContentReference reference) {
        return transaction.execute(status -> {
            var resource = content.resource(reference.resourceId(), false);
            var revision = content.revision(resource, reference.revisionId());
            if (!actor().equals(revision.author().actor())) { throw new IllegalArgumentException("Autor requerido"); }
            return moderation.submitForReview(revision.author(), reference, revision.input().text(), revision.input().declaredLabel(), revision.input().attachments());
        });
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta requerida"))); }
    public record Draft(UUID command, UUID jobId, Optional<UUID> resourceId, long version, SubjectId subject, String kind,
            String body, Integer score, String label, List<Upload> uploads) {
        public Draft { Objects.requireNonNull(command); Objects.requireNonNull(jobId); Objects.requireNonNull(resourceId); Objects.requireNonNull(subject); Objects.requireNonNull(kind); Objects.requireNonNull(body); Objects.requireNonNull(label); uploads = List.copyOf(uploads); }
    }
}
