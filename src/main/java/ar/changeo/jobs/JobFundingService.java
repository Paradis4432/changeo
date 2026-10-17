package ar.changeo.jobs;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.api.ModerationAccess;
import ar.changeo.payments.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class JobFundingService implements JobFundingAccess {
    private final JobAccess jobs;
    private final ConversationAccess conversations;
    private final AgreementAccess agreements;
    private final JobMoneyStatusAccess money;
    private final ModerationAccess moderation;
    private final SecurityProof proof;
    private final JobParticipation participation;
    private final JobFinancialPreparationAccess preparation;
    JobFundingService(JobAccess jobs, ConversationAccess conversations, AgreementAccess agreements, JobMoneyStatusAccess money, ModerationAccess moderation, SecurityProof proof, JobParticipation participation, JobFinancialPreparationAccess preparation) {
        this.jobs = jobs; this.conversations = conversations; this.agreements = agreements; this.money = money; this.moderation = moderation; this.proof = proof; this.participation = participation;
        this.preparation = preparation;
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public FundingAgreement requireFundable(UUID jobId, UUID agreementRevisionId) {
        return fundable(jobId, agreementRevisionId, Optional.empty());
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public FundingAgreement requireFundable(UUID jobId, UUID agreementRevisionId, FundingSelection selection) {
        return fundable(jobId, agreementRevisionId, Optional.of(Objects.requireNonNull(selection)));
    }
    private FundingAgreement fundable(UUID jobId, UUID agreementRevisionId, Optional<FundingSelection> selection) {
        var discovered = jobs.discover(jobId);
        var context = discovered.conversation();
        var actor = new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Pagador autenticado requerido")));
        if (!actor.equals(context.customerActor())) { throw new IllegalArgumentException("Solo el pagador histórico puede iniciar esta operación"); }
        var decision = conversations.authorize(context.id(), actor, context.customer(), Capability.SYNTHETIC_FUNDING_ELIGIBILITY);
        conversations.authorize(context.id(), context.providerActor(), context.provider(), Capability.ACCEPT_AGREEMENT);
        var job = jobs.lock(jobId);
        conversations.requireCurrent(job.conversation()); participation.requireContract(job.conversation());
        var reference = job.agreement().orElseThrow(() -> new IllegalStateException("El trabajo todavía no tiene acuerdo bilateral"));
        if (!reference.revisionId().equals(agreementRevisionId) || job.state() != JobSnapshot.State.AGREED_AWAITING_FUNDING
                || !decision.legalPayerId().equals(job.legalPayer())) { throw new IllegalStateException("Acuerdo, estado o pagador incompatible"); }
        var revision = agreements.lock(reference);
        revision.terms().requireComplete(context.mode());
        if (selection.filter(value -> value.expectedJobVersion() != job.version()).isPresent()) { throw new IllegalStateException("El trabajo cambió; volvé a observarlo"); }
        var terms = revision.terms();
        var funding = new FundingAgreement(jobId, reference, context.customer(), context.provider(), context.customerActor(), context.providerActor(),
                job.legalPayer().orElseThrow(), terms.serviceMinor(), terms.deliveryMinor(), terms.totalMinor(), terms, job.version());
        var status = selection.map(value -> preparation.prepareFundingForUpdate(funding, value))
                .orElseGet(() -> money.currentForUpdate(jobId, reference)).requireExact(jobId, reference);
        moderation.approvalsForUpdate(Set.of(reference));
        agreements.requireApproved(revision);
        var approval = moderation.approvalForUpdate(reference);
        participation.requireAudience(job.conversation(), approval.label());
        if (status.funding() == JobMoneyStatus.Funding.INTEGRATION_PENDING || status.funding() == JobMoneyStatus.Funding.UNKNOWN) {
            throw new IllegalStateException("La integración financiera exacta está pendiente");
        }
        return funding;
    }
}
