package ar.changeo.payments;

import ar.changeo.identity.api.ActorId;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.api.ContentReference;
import ar.changeo.payments.api.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
public class PaymentFinancialAdapter implements JobMoneyStatusAccess, JobFinancialPreparationAccess,
        JobDisputeAccess, JobAgreementChangeAccess, JobSettlementStatusAccess {
    private final PaymentStore store;
    private final PaymentReservations reservations;

    PaymentFinancialAdapter(PaymentStore store, PaymentReservations reservations, SyntheticCapital capital) {
        this.store = store; this.reservations = reservations;
    }

    @Override public JobMoneyStatus currentForUpdate(UUID jobId, ContentReference agreement) {
        return store.find(jobId, agreement, true).map(store::status).orElseGet(() ->
                new JobMoneyStatus(jobId, agreement, JobMoneyStatus.Funding.UNFUNDED, JobMoneyStatus.Release.NOT_RELEASED, JobMoneyStatus.BankPayout.NOT_PAID));
    }

    @Override public JobMoneyStatus prepareFundingForUpdate(FundingAgreement agreement, FundingSelection selection) {
        var facts = PaymentStore.monetary(agreement);
        if (selection.creditMinor() > facts.totalMinor() || facts.totalMinor() <= 0 || selection.expectedJobVersion() != agreement.canonicalVersion()) {
            throw new PaymentFailure("Importe completo, crédito o versión incompatible");
        }
        var source = selection.creditMinor() < agreement.totalMinor() ? Optional.of(PaymentStore.sourceId(facts, selection.commandId())) : Optional.<UUID>empty();
        store.prepareActive(facts, source);
        var root = store.root(PaymentStore.rootId(facts), false);
        long available = 0;
        for (var lot : store.sources(facts.legalPayer(), false)) { available = Math.addExact(available, lot.availableMinor()); }
        if (selection.creditMinor() > available) { throw new PaymentFailure("El crédito propio disponible no alcanza; lo reservado o sin respaldo no es gastable"); }
        return store.status(root);
    }

    @Override public JobMoneyStatus prepareReleaseForUpdate(JobFinancialFacts facts, FinalReleaseRequest request) {
        store.prepareActive(facts.agreement(), Optional.empty());
        var root = store.root(PaymentStore.rootId(facts.agreement()), false);
        requireVersions(facts, root, request.expectedJobVersion(), request.expectedMoneyVersion());
        return store.status(root);
    }

    @Override public JobMoneyStatus prepareViewForUpdate(JobFinancialFacts facts) {
        store.prepareActive(facts.agreement(), Optional.empty());
        return store.status(store.root(PaymentStore.rootId(facts.agreement()), false));
    }

    @Override public JobMoneyStatus prepareSettlementForUpdate(JobFinancialFacts facts, SettlementPreparation request) {
        store.prepareActive(facts.agreement(), Optional.empty());
        var root = store.root(PaymentStore.rootId(facts.agreement()), false);
        requireVersions(facts, root, request.expectedJobVersion(), request.expectedMoneyVersion());
        return store.status(root);
    }

    @Override public void pauseForUpdate(JobFinancialFacts facts, UUID commandId, ActorId actor, boolean dispute) {
        var agreement = facts.agreement();
        if (!actor.equals(agreement.customerActor()) && !actor.equals(agreement.providerActor())) { throw new PaymentFailure("Parte histórica ajena a la obligación"); }
        store.prepareActive(agreement, Optional.empty());
        var root = store.root(PaymentStore.rootId(agreement), false);
        String digest = PaymentStore.digest(root.id() + ":" + dispute);
        if (store.receipt(actor, commandId, "PAUSE", digest).isPresent()) { return; }
        boolean reconcile = false;
        for (var operation : store.operations(root.id(), false)) {
            if (operation.uncertain() || (operation.pending() && operation.dispatchedAt().isPresent())) {
                reconcile = true;
                store.jdbc.update("update payments_operation set state='UNKNOWN',generation=generation+1,claim_token=null,lease_until=null,reason='PAUSED_RECONCILIATION' where id=?", operation.id());
            } else if (operation.pending()) {
                reservations.release(operation);
                store.jdbc.update("update payments_operation set state='CANCELLED',generation=generation+1,claim_token=null,lease_until=null,reason='SUPPORT_PAUSED' where id=?", operation.id());
            }
        }
        String disposition = root.releasedMinor() > 0 || root.paidMinor() > 0 ? "RECOVERY" : reconcile ? "RECONCILE" : "PAUSED";
        UUID pause = UUID.randomUUID();
        store.jdbc.update("insert into payments_pause values(?,?,?,?,?,?,?)", pause, root.id(), commandId, actor.value(), dispute, disposition, store.now());
        store.saveOutcome(root.agreement().legalPayer(), List.of(root.withPause(dispute))); store.receipt(actor, commandId, "PAUSE", digest, pause);
    }

    @Override public boolean prepareReplacementForUpdate(FundingAgreement original, FundingAgreement replacement) {
        var oldFacts = PaymentStore.monetary(original); var newFacts = PaymentStore.monetary(replacement);
        requireReplacementBinding(oldFacts, newFacts);
        store.prepareReplacement(oldFacts, newFacts);
        return store.neverFunded(PaymentStore.rootId(oldFacts)) && store.neverFunded(PaymentStore.rootId(newFacts));
    }

    @Override public void replaceForUpdate(FundingAgreement original, FundingAgreement replacement) {
        var oldFacts = PaymentStore.monetary(original); var newFacts = PaymentStore.monetary(replacement);
        requireReplacementBinding(oldFacts, newFacts);
        var oldRoot = store.root(PaymentStore.rootId(oldFacts), true); var newRoot = store.root(PaymentStore.rootId(newFacts), true);
        oldRoot.requireBinding(oldFacts); newRoot.requireBinding(newFacts);
        if (!store.neverFunded(oldRoot.id()) || !store.neverFunded(newRoot.id()) || !oldRoot.lifecycle().equals("ACTIVE") || !newRoot.lifecycle().equals("PROSPECTIVE")) {
            throw new PaymentFailure("Los términos con historia financiera deben liquidarse y renegociarse en otro trabajo");
        }
        store.jdbc.update("update payments_root set lifecycle='RETIRED',active_job=null,active_quote=null,active_revision=null,active_payer=null,version=version+1 where id=?", oldRoot.id());
        store.jdbc.update("update payments_root set lifecycle='ACTIVE',active_job=job_id,active_quote=original_quote,active_revision=original_revision,active_payer=payer_id,version=version+1 where id=?", newRoot.id());
    }

    @Override public JobSettlementStatus currentForUpdate(UUID jobId, ContentReference agreement, UUID settlementId) {
        var root = store.find(jobId, agreement, true).orElseThrow(() -> new PaymentFailure("Liquidación no disponible"));
        var proposals = store.jdbc.queryForList("select continue_job from payments_settlement where id=? and root_id=?", settlementId, root.id());
        if (proposals.isEmpty()) { throw new PaymentFailure("Liquidación ajena a la obligación"); }
        var operations = store.operations(root.id(), false);
        boolean successful = operations.stream().anyMatch(value -> value.kind().equals("REFUND") && value.settlementId().filter(settlementId::equals).isPresent() && value.applied() && value.state().equals("SUCCESS"));
        int assents = store.jdbc.queryForObject("select count(*) from payments_assent where settlement_id=?", Integer.class, settlementId);
        boolean continuation = assents == 2 && Boolean.TRUE.equals(proposals.getFirst().get("continue_job"));
        boolean unresolved = root.paused() || root.disputed() || root.deficitMinor() > 0 || operations.stream().anyMatch(value -> value.pending() || value.uncertain());
        return new JobSettlementStatus(successful, continuation, unresolved, agreement);
    }

    private static void requireVersions(JobFinancialFacts facts, PaymentStore.Root root, long jobVersion, long moneyVersion) {
        if (facts.agreement().canonicalVersion() != jobVersion || root.version() != moneyVersion) { throw new PaymentFailure("La obligación cambió; volvé a observar sus importes y estado"); }
    }
    private static void requireReplacementBinding(FinancialAgreement original, FinancialAgreement replacement) {
        if (!original.jobId().equals(replacement.jobId()) || original.agreement().equals(replacement.agreement())
                || !original.customer().equals(replacement.customer()) || !original.provider().equals(replacement.provider())
                || !original.customerActor().equals(replacement.customerActor()) || !original.providerActor().equals(replacement.providerActor())
                || !original.legalPayer().equals(replacement.legalPayer())) { throw new PaymentFailure("El cambio no puede sustituir participantes ni pagador original"); }
    }
}
