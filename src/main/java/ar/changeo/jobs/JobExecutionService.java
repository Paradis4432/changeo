package ar.changeo.jobs;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobExecutionService {
    private final JobStore store;
    private final JobContentStore content;
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final AgreementAccess agreements;
    private final JobMoneyStatusAccess money;
    private final ModerationAccess moderation;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JobParticipation participation;
    JobExecutionService(JobStore store, JobContentStore content, JobAccess jobs, ConversationAccess conversations, AgreementAccess agreements,
            JobMoneyStatusAccess money, ModerationAccess moderation, IdentityAccess identity, SecurityProof proof, Clock clock, PlatformTransactionManager transactions, JobParticipation participation) {
        this.store = store; this.content = content; this.jobs = jobs; this.conversations = conversations; this.agreements = agreements;
        this.money = money; this.moderation = moderation; this.identity = identity; this.proof = proof; this.clock = clock; this.transaction = new TransactionTemplate(transactions);
        this.participation = participation;
    }
    public JobView view(UUID id, SubjectId subject) {
        return transaction.execute(status -> {
            var discovered = jobs.discover(id);
            conversations.protect(discovered.conversation().id(), actor(), subject, Capability.SEND_MARKETPLACE_MESSAGE);
            var job = jobs.lock(id);
            if (!job.accepted()) { return new JobView(job, Optional.empty(), job.reviewObservation(clock.instant())); }
            var reference = job.agreement().orElseThrow();
            var terms = agreements.lock(reference);
            var statusMoney = money.currentForUpdate(id, reference).requireExact(id, reference);
            moderation.approvalsForUpdate(Set.of(reference)); agreements.requireApproved(terms);
            return new JobView(job, Optional.of(statusMoney), job.reviewObservation(clock.instant()));
        });
    }
    public JobSnapshot start(UUID command, UUID id, long version, SubjectId subject) {
        return transaction.execute(status -> {
            var context = jobs.discover(id).conversation();
            if (!subject.equals(context.provider())) { throw new IllegalArgumentException("Solo la parte proveedora inicia la ejecución"); }
            conversations.authorize(context.id(), actor(), subject, Capability.ACCEPT_AGREEMENT);
            conversations.authorize(context.id(), context.customerActor(), context.customer(), Capability.ACCEPT_AGREEMENT);
            var job = jobs.lock(id);
            var terms = agreements.lock(job.agreement().orElseThrow());
            conversations.requireCurrent(job.conversation()); participation.requireContract(job.conversation());
            var statusMoney = money.currentForUpdate(id, terms.reference()).requireExact(id, terms.reference());
            moderation.approvalsForUpdate(Set.of(terms.reference())); agreements.requireApproved(terms);
            requireTermsAudience(terms.reference(), context);
            String digest = digest(id + ":" + version + ":START");
            if (replay(actor(), command, "START", digest)) { return job; }
            statusMoney.requireFunded(); terms.terms().requireComplete(context.mode());
            if (job.version() != version || job.state() != JobSnapshot.State.AGREED_AWAITING_FUNDING) { throw new IllegalStateException("El trabajo no está listo para comenzar"); }
            store.jdbc.update("update jobs_job set state='IN_PROGRESS',version=version+1 where id=?", id);
            store.event(id, actor().value(), "STARTED", terms.reference()); store.changed(context); record(actor(), command, "START", digest, id);
            return jobs.discover(id);
        });
    }
    public JobSnapshot acceptFinal(UUID command, UUID id, long version, SubjectId subject, ContentReference finalDelivery) {
        return transaction.execute(status -> {
            var context = jobs.discover(id).conversation();
            if (!subject.equals(context.customer())) { throw new IllegalArgumentException("Solo la parte cliente acepta la entrega final"); }
            conversations.authorize(context.id(), actor(), subject, Capability.ACCEPT_AGREEMENT);
            conversations.authorize(context.id(), context.providerActor(), context.provider(), Capability.ACCEPT_AGREEMENT);
            var job = jobs.lock(id);
            var discovered = content.resource(finalDelivery.resourceId(), false);
            conversations.requireCurrent(job.conversation()); participation.requireContract(job.conversation());
            var canonical = content.lock(discovered);
            var revision = content.revision(canonical.resource(), finalDelivery.revisionId());
            var statusMoney = money.currentForUpdate(id, canonical.terms().reference()).requireExact(id, canonical.terms().reference());
            var approvals = moderation.approvalsForUpdate(Set.of(finalDelivery, canonical.terms().reference())); agreements.requireApproved(canonical.terms());
            requireTermsAudience(canonical.terms().reference(), context);
            String digest = digest(id + ":" + version + ":FINAL:" + finalDelivery);
            if (replay(actor(), command, "ACCEPT_FINAL", digest)) { return job; }
            statusMoney.requireFunded();
            var approval = approvals.get(finalDelivery);
            if (job.version() != version || job.state() != JobSnapshot.State.FINAL_DELIVERY_PENDING_REVIEW || !job.finalDelivery().filter(finalDelivery::equals).isPresent()
                    || canonical.resource().kind() != JobContentStore.Kind.FINAL_DELIVERY || !canonical.resource().jobId().equals(id)
                    || !finalDelivery.revisionId().equals(canonical.resource().approved()) || !approval.state().equals("APPROVED")
                    || !approval.digest().equals(revision.input().digest()) || !job.agreement().filter(canonical.resource().terms()::equals).isPresent()
                    || (approval.label().equals("ADULT") && !subject.value().equals(actor().value()))
                    || !identity.decide(PermissionRequest.self(new AccountId(actor().value()), audience(approval.label()))).allowed()) {
                throw new IllegalStateException("La entrega final exacta no es aceptable");
            }
            store.jdbc.update("update jobs_job set state='COMPLETED',version=version+1 where id=?", id);
            store.event(id, actor().value(), "COMPLETED", finalDelivery); store.changed(context); record(actor(), command, "ACCEPT_FINAL", digest, id);
            return jobs.discover(id);
        });
    }
    private boolean replay(ActorId actor, UUID command, String operation, String digest) {
        var rows = store.jdbc.queryForList("select operation,digest from jobs_receipt where actor_id=? and command_id=?", actor.value(), command);
        if (rows.isEmpty()) { return false; }
        var row = rows.getFirst();
        if (!operation.equals(row.get("operation")) || !digest.equals(row.get("digest"))) { throw new IllegalArgumentException("Comando ligado a otra etapa"); }
        return true;
    }
    private void record(ActorId actor, UUID command, String operation, String digest, UUID outcome) { store.jdbc.update("insert into jobs_receipt values(?,?,?,?,?)", actor.value(), command, operation, digest, outcome); }
    private static Capability audience(String label) { return switch (label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; default -> Capability.VIEW_ADULT; }; }
    private void requireTermsAudience(ContentReference reference, ConversationContext context) {
        var approval = moderation.approvalForUpdate(reference);
        participation.requireAudience(context, approval.label());
    }
    private static String digest(String text) { return ContentDigest.bytes(text.getBytes(StandardCharsets.UTF_8)); }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta requerida"))); }
    public record JobView(JobSnapshot job, Optional<JobMoneyStatus> money, String reviewObservation) {}
}
