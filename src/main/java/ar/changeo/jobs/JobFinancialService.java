package ar.changeo.jobs;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.ConversationAccess;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.payments.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class JobFinancialService implements JobFinancialAccess {
    private final JobStore store;
    private final JobContentStore content;
    private final JobAccess jobs;
    private final AgreementAccess agreements;
    private final ConversationAccess conversations;
    private final JobFinancialPreparationAccess preparation;
    private final SecurityProof proof;

    JobFinancialService(JobStore store, JobContentStore content, JobAccess jobs, AgreementAccess agreements,
            ConversationAccess conversations, JobFinancialPreparationAccess preparation, SecurityProof proof) {
        this.store = store; this.content = content; this.jobs = jobs; this.agreements = agreements;
        this.conversations = conversations; this.preparation = preparation; this.proof = proof;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public JobFinancialFacts requireView(UUID jobId, SubjectId side) {
        var facts = protectedFacts(jobId, side, Optional.empty());
        preparation.prepareViewForUpdate(facts).requireExact(jobId, facts.agreement().agreement());
        return facts;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public JobFinancialFacts requireRelease(UUID jobId, UUID agreementRevisionId, SubjectId side, FinalReleaseRequest request) {
        var facts = protectedFacts(jobId, side, Optional.of(agreementRevisionId));
        if (facts.finalAcceptedBy().isEmpty()) { throw new IllegalStateException("Falta la aceptación final expresa de la parte cliente original"); }
        preparation.prepareReleaseForUpdate(facts, request).requireExact(jobId, facts.agreement().agreement());
        return facts;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public JobFinancialFacts requireSettlement(UUID jobId, UUID agreementRevisionId, SubjectId side, SettlementPreparation request) {
        var facts = protectedFacts(jobId, side, Optional.of(agreementRevisionId));
        preparation.prepareSettlementForUpdate(facts, request).requireExact(jobId, facts.agreement().agreement());
        return facts;
    }

    private JobFinancialFacts protectedFacts(UUID jobId, SubjectId side, Optional<UUID> expectedRevision) {
        var discovered = jobs.discover(jobId);
        var actor = new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Parte original autenticada requerida")));
        conversations.protect(discovered.conversation().id(), actor, side, Capability.ACCESS_EXISTING_OBLIGATION);
        var job = jobs.lock(jobId);
        var reference = job.agreement().orElseThrow();
        if (expectedRevision.filter(revision -> !reference.revisionId().equals(revision)).isPresent()) { throw new IllegalArgumentException("Revisión financiera desactualizada"); }
        AgreementSnapshot revision;
        Optional<ActorId> acceptedBy = Optional.empty();
        if (job.finalDelivery().isPresent()) {
            var finalReference = job.finalDelivery().orElseThrow();
            var canonical = content.lock(content.resource(finalReference.resourceId(), false));
            revision = canonical.terms();
            if (!canonical.resource().jobId().equals(jobId) || canonical.resource().kind() != JobContentStore.Kind.FINAL_DELIVERY
                    || !canonical.resource().terms().equals(reference)) { throw new IllegalStateException("Entrega final ajena a los términos originales"); }
            var actors = store.jdbc.query("select actor_id from jobs_event where job_id=? and kind='COMPLETED' and resource_id=? and revision_id=?",
                    (row, index) -> row.getObject(1, UUID.class), jobId, finalReference.resourceId(), finalReference.revisionId());
            if (actors.size() == 1 && job.conversation().customerActor().value().equals(actors.getFirst())
                    && job.legalPayer().orElseThrow().value().equals(actors.getFirst())) { acceptedBy = Optional.of(new ActorId(actors.getFirst())); }
        } else { revision = agreements.lock(reference); }
        return new JobFinancialFacts(JobFinancialMetadata.agreement(job, revision), job.state(), job.finalDelivery(), acceptedBy);
    }
}
