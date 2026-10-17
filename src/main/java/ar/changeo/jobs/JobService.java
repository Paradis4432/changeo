package ar.changeo.jobs;

import ar.changeo.conversations.api.*;
import ar.changeo.identity.api.AccountId;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.payments.api.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.*;

@Service
public class JobService implements JobAccess {
    private final JobStore store;
    private final ConversationAccess conversations;
    private final JobMoneyStatusAccess money;
    private final Clock clock;
    private final boolean financialEnabled;
    JobService(JobStore store, ConversationAccess conversations, JobMoneyStatusAccess money, Clock clock,
            @Value("${changeo.synthetic-payments:false}") boolean financialEnabled) {
        this.store = store; this.conversations = conversations; this.money = money; this.clock = clock; this.financialEnabled = financialEnabled;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void createProspect(ConversationContext context) {
        store.jdbc.update("insert into jobs_job(id,conversation_id,customer_id,provider_id,customer_actor,provider_actor,mode) values(?,?,?,?,?,?,?)",
                context.jobId(), context.id(), context.customer().value(), context.provider().value(), context.customerActor().value(), context.providerActor().value(), context.mode().name());
        for (UUID subject : List.of(context.customer().value(), context.provider().value()).stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            store.jdbc.update("insert into jobs_reputation_cohort(subject_id) values(?) on conflict do nothing", subject);
        }
    }
    @Override public JobSnapshot discover(UUID id) { return store.job(id, false); }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public JobSnapshot lock(UUID id) {
        var discovered = discover(id);
        var context = conversations.lock(discovered.conversation().id());
        store.cohorts(context);
        var job = store.job(id, true);
        if (!job.conversation().equals(context)) { throw new IllegalArgumentException("Contexto del trabajo cambiado"); }
        return job;
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void agree(UUID id, ContentReference agreement, AccountId legalPayer) {
        var job = discover(id);
        if (job.state() != JobSnapshot.State.NEGOTIATING || job.accepted()) { throw new IllegalStateException("El trabajo ya tiene acuerdo o está cerrado"); }
        store.jdbc.update("update jobs_job set accepted_quote=?,accepted_revision=?,legal_payer=?,state='AGREED_AWAITING_FUNDING',version=version+1 where id=?",
                agreement.resourceId(), agreement.revisionId(), legalPayer.value(), id);
        store.event(id, legalPayer.value(), "AGREED", agreement);
        store.changed(job.conversation());
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void changePending(UUID id, ContentReference amendment) {
        var job = discover(id); var reference = job.agreement().orElseThrow();
        if (financialEnabled) { money.currentForUpdate(id, reference).requireExact(id, reference); }
        store.event(id, null, financialEnabled ? "CHANGE_REQUIRES_SETTLEMENT_AND_NEW_JOB" : "CHANGE_PENDING_FINANCIAL_OWNER", amendment);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void replaceUnfundedAgreement(UUID jobId, ContentReference expectedOld, ContentReference replacement, AccountId originalPayer) {
        var job = store.job(jobId, true);
        if (!job.agreement().filter(expectedOld::equals).isPresent() || !job.legalPayer().filter(originalPayer::equals).isPresent()
                || job.state() != JobSnapshot.State.AGREED_AWAITING_FUNDING || job.finalDelivery().isPresent() || job.feedbackCount() != 0
                || expectedOld.equals(replacement)) { throw new IllegalStateException("Solo se sustituye el acuerdo exacto todavía sin ejecución y con su pagador original"); }
        long version = Math.addExact(job.version(), 1);
        store.jdbc.update("insert into jobs_selection_history values(?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), jobId, expectedOld.resourceId(), expectedOld.revisionId(),
                replacement.resourceId(), replacement.revisionId(), originalPayer.value(), version, Timestamp.from(clock.instant()));
        store.jdbc.update("update jobs_job set accepted_quote=?,accepted_revision=?,version=? where id=?", replacement.resourceId(), replacement.revisionId(), version, jobId);
        store.event(jobId, originalPayer.value(), "AGREEMENT_REPLACED", replacement); store.changed(job.conversation());
    }
}
