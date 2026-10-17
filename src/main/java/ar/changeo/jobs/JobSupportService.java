package ar.changeo.jobs;

import ar.changeo.agreements.api.AgreementAccess;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.ContentDigest;
import ar.changeo.moderation.api.ModerationAccess;
import ar.changeo.payments.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobSupportService {
    private final JobStore store;
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final AgreementAccess agreements;
    private final JobMoneyStatusAccess money;
    private final ModerationAccess moderation;
    private final SecurityProof proof;
    private final JobDisputeAccess financialDisputes;
    private final JobSettlementStatusAccess settlements;
    private final JobParticipation participation;
    private final TransactionTemplate transaction;
    JobSupportService(JobStore store, JobAccess jobs, ConversationAccess conversations, AgreementAccess agreements, JobMoneyStatusAccess money,
            ModerationAccess moderation, SecurityProof proof, PlatformTransactionManager transactions, JobDisputeAccess financialDisputes,
            JobSettlementStatusAccess settlements, JobParticipation participation) {
        this.store = store; this.jobs = jobs; this.conversations = conversations; this.agreements = agreements; this.money = money;
        this.moderation = moderation; this.proof = proof; this.transaction = new TransactionTemplate(transactions);
        this.financialDisputes = financialDisputes; this.settlements = settlements; this.participation = participation;
    }
    public SupportView view(UUID jobId, SubjectId side) {
        return transaction.execute(status -> {
            var context = jobs.discover(jobId).conversation();
            conversations.protect(context.id(), actor(), side, Capability.ACCESS_EXISTING_OBLIGATION);
            var job = jobs.lock(jobId);
            var events = store.jdbc.query("select kind from jobs_event where job_id=? order by occurred_at desc,id limit 20", (row, index) -> row.getString(1), jobId);
            return new SupportView(jobId, side.value(), job.state(), job.version(), job.legalPayer().filter(payer -> payer.value().equals(actor().value())).isPresent(), List.copyOf(events));
        });
    }
    public List<SupportView> obligations() {
        UUID current = actor().value();
        var ids = store.jdbc.query("select j.id,t.customer_id,t.provider_id,t.customer_actor from jobs_job j join conversations_thread t on t.id=j.conversation_id where j.accepted_revision is not null and (t.customer_actor=? or t.provider_actor=?) order by j.id::text limit 20",
                (row, index) -> new UUID[]{row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getObject(4, UUID.class)}, current, current);
        return ids.stream().map(row -> view(row[0], new SubjectId(current.equals(row[3]) ? row[1] : row[2]))).toList();
    }
    public void request(UUID command, UUID jobId, long version, SubjectId side, boolean dispute) {
        transaction.executeWithoutResult(status -> {
            var context = jobs.discover(jobId).conversation();
            conversations.protect(context.id(), actor(), side, Capability.ACCESS_SUPPORT);
            var job = jobs.lock(jobId);
            String operation = dispute ? "DISPUTE_REQUESTED" : "SUPPORT_REQUESTED";
            String digest = digest(jobId + ":" + version + ":" + operation + ":" + side);
            if (replay(command, operation, digest)) { return; }
            if (job.version() != version) { throw new IllegalStateException("El trabajo cambió; revisá el estado de ayuda"); }
            var terms = agreements.lock(job.agreement().orElseThrow());
            financialDisputes.pauseForUpdate(new JobFinancialFacts(JobFinancialMetadata.agreement(job, terms), job.state(), job.finalDelivery(), Optional.empty()), command, actor(), dispute);
            store.jdbc.update("update jobs_job set pre_hold_state=case when state<>'SUPPORT_HOLD' then coalesce(pre_hold_state,state) else pre_hold_state end,state='SUPPORT_HOLD',version=version+1 where id=?", jobId);
            store.event(jobId, actor().value(), operation, job.agreement().orElseThrow()); store.changed(context);
            record(command, operation, digest, jobId);
        });
    }
    public JobSnapshot resume(UUID command, UUID jobId, long version, SubjectId side, UUID settlementId) {
        return transaction.execute(status -> {
            var context = jobs.discover(jobId).conversation();
            conversations.authorize(context.id(), actor(), side, Capability.ACCEPT_AGREEMENT);
            conversations.authorize(context.id(), context.customerActor(), context.customer(), Capability.ACCEPT_AGREEMENT);
            conversations.authorize(context.id(), context.providerActor(), context.provider(), Capability.ACCEPT_AGREEMENT);
            var job = jobs.lock(jobId);
            conversations.requireCurrent(job.conversation()); participation.requireContract(job.conversation());
            var reference = job.agreement().orElseThrow(); var terms = agreements.lock(reference);
            String digest = digest(jobId + ":" + version + ":" + side + ":" + settlementId);
            if (replay(command, "RESUME", digest)) { return job; }
            String previous = store.jdbc.queryForObject("select pre_hold_state from jobs_job where id=?", String.class, jobId);
            if (job.version() != version || job.state() != JobSnapshot.State.SUPPORT_HOLD || previous == null
                    || !Set.of("AGREED_AWAITING_FUNDING", "IN_PROGRESS", "FINAL_DELIVERY_PENDING_REVIEW", "COMPLETED").contains(previous)) {
                throw new IllegalStateException("No hay un estado de trabajo vigente que pueda retomarse");
            }
            if (previous.equals("COMPLETED")) {
                var finalReference = job.finalDelivery().orElseThrow();
                int accepted = store.jdbc.queryForObject("select count(*) from jobs_event where job_id=? and kind='COMPLETED' and actor_id=? and resource_id=? and revision_id=?",
                        Integer.class, jobId, context.customerActor().value(), finalReference.resourceId(), finalReference.revisionId());
                if (accepted != 1) { throw new IllegalStateException("No existe la finalización expresa original que se pretende conservar"); }
            }
            var moneyStatus = money.currentForUpdate(jobId, reference).requireExact(jobId, reference);
            var settlement = settlements.currentForUpdate(jobId, reference, settlementId);
            if (!settlement.exactAgreement().equals(reference) || !settlement.successful() || !settlement.bilateralContinuation() || settlement.unresolved()) {
                throw new IllegalStateException("Se requiere la misma liquidación bilateral confirmada con continuación y sin incertidumbre");
            }
            moneyStatus.requireFunded();
            moderation.approvalsForUpdate(Set.of(reference)); agreements.requireApproved(terms);
            participation.requireAudience(context, moderation.approvalForUpdate(reference).label());
            store.jdbc.update("update jobs_job set state=?,pre_hold_state=null,version=version+1 where id=?", previous, jobId);
            store.event(jobId, actor().value(), "SUPPORT_RESUMED", reference); store.changed(context); record(command, "RESUME", digest, jobId);
            return jobs.discover(jobId);
        });
    }
    public void cancel(UUID command, UUID jobId, long version, SubjectId side) {
        transaction.executeWithoutResult(status -> {
            var context = jobs.discover(jobId).conversation();
            conversations.protect(context.id(), actor(), side, jobs.discover(jobId).accepted() ? Capability.ACCEPT_AGREEMENT : Capability.SEND_MARKETPLACE_MESSAGE);
            var job = jobs.lock(jobId);
            String digest = digest(jobId + ":" + version + ":CANCEL:" + side);
            if (replay(command, "CANCEL_REQUEST", digest)) { return; }
            if (job.version() != version || Set.of(JobSnapshot.State.COMPLETED, JobSnapshot.State.CANCELLED, JobSnapshot.State.SUPPORT_HOLD).contains(job.state())) {
                throw new IllegalStateException("Esta cancelación requiere revisión de soporte");
            }
            JobMoneyStatus financial = null;
            if (job.accepted()) {
                var reference = job.agreement().orElseThrow(); var terms = agreements.lock(reference);
                financial = money.currentForUpdate(jobId, reference).requireExact(jobId, reference);
                moderation.approvalsForUpdate(Set.of(reference)); agreements.requireApproved(terms);
            }
            store.event(jobId, actor().value(), "CANCELLATION_REQUESTED", job.agreement().orElse(null));
            int sides = store.jdbc.queryForObject("select count(distinct actor_id) from jobs_event where job_id=? and kind='CANCELLATION_REQUESTED' and actor_id in (?,?)",
                    Integer.class, jobId, context.customerActor().value(), context.providerActor().value());
            boolean cancelled = sides == 2 && (!job.accepted() || financial.funding() == JobMoneyStatus.Funding.UNFUNDED);
            store.jdbc.update("update jobs_job set state=?,version=version+1 where id=?", cancelled ? "CANCELLED" : "CANCELLATION_REQUESTED", jobId);
            if (cancelled) { store.event(jobId, actor().value(), "CANCELLED", job.agreement().orElse(null)); }
            store.changed(context); record(command, "CANCEL_REQUEST", digest, jobId);
        });
    }
    private boolean replay(UUID command, String operation, String digest) {
        var rows = store.jdbc.queryForList("select operation,digest from jobs_receipt where actor_id=? and command_id=?", actor().value(), command);
        if (rows.isEmpty()) { return false; }
        if (!operation.equals(rows.getFirst().get("operation")) || !digest.equals(rows.getFirst().get("digest"))) { throw new IllegalArgumentException("Comando de soporte reutilizado"); }
        return true;
    }
    private void record(UUID command, String operation, String digest, UUID outcome) { store.jdbc.update("insert into jobs_receipt values(?,?,?,?,?)", actor().value(), command, operation, digest, outcome); }
    private static String digest(String value) { return ContentDigest.bytes(value.getBytes(StandardCharsets.UTF_8)); }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta requerida"))); }
    public record SupportView(UUID jobId, UUID side, JobSnapshot.State state, long version, boolean legalPayer, List<String> events) {}
}
