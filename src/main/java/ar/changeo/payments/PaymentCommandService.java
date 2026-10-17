package ar.changeo.payments;

import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.payments.api.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
public class PaymentCommandService {
    private final PaymentStore store;
    private final PaymentOperations operations;
    private final JobFundingAccess funding;
    private final JobFinancialAccess financial;
    private final JobAccess jobs;
    private final SecurityProof proof;
    private final TransactionTemplate transaction;

    PaymentCommandService(PaymentStore store, PaymentOperations operations, JobFundingAccess funding,
            JobFinancialAccess financial, JobAccess jobs, SecurityProof proof, PlatformTransactionManager transactions) {
        this.store = store; this.operations = operations; this.funding = funding; this.financial = financial;
        this.jobs = jobs; this.proof = proof; this.transaction = new TransactionTemplate(transactions);
    }

    public UUID fund(UUID jobId, UUID revisionId, FundingSelection selection) {
        return command(() -> {
            var actor = actor();
            String digest = PaymentStore.digest(jobId + ":" + revisionId + ":" + selection);
            var replay = replay(actor, selection.commandId(), "FUND", digest, jobId, revisionId);
            if (replay.isPresent()) { return replay.orElseThrow(); }
            var agreement = funding.requireFundable(jobId, revisionId, selection);
            var root = store.root(PaymentStore.rootId(PaymentStore.monetary(agreement)), false);
            store.requireIdle(root);
            if (root.fundedMinor() != 0 || root.paused() || root.disputed()) { throw new PaymentFailure("Esta obligación ya tiene financiación o está pausada"); }
            var previous = operations.failedPredecessor(root, "FUND");
            var lots = store.sources(agreement.legalPayer(), false);
            List<CreditChoice> credits = creditChoices(root, selection.creditMinor(), lots, previous);
            long external = Math.subtractExact(agreement.totalMinor(), selection.creditMinor());
            var intent = new PaymentOperations.Intent(selection.commandId(), actor, "FUND", agreement.totalMinor(), selection.creditMinor(), external,
                    "PAYER:" + agreement.legalPayer().value(), operations.fundingFeeMinor, 0, Optional.empty(), previous);
            var operation = operations.create(root, intent);
            for (var credit : credits) {
                var allocation = operations.allocation(root, credit.sourceId(), "CREDIT", credit.amountMinor());
                if (store.jdbc.update("update payments_source set reserved_credit_minor=reserved_credit_minor+?,version=version+1 where id=? and not frozen and credit_minor-reserved_credit_minor>=?",
                        credit.amountMinor(), credit.sourceId(), credit.amountMinor()) != 1) { throw new PaymentFailure("El crédito seleccionado dejó de estar disponible"); }
                operations.share(operation, allocation, credit.amountMinor(), "CREDIT");
            }
            if (external > 0) {
                UUID source = PaymentStore.sourceId(PaymentStore.monetary(agreement), selection.commandId());
                store.jdbc.update("update payments_source set original_minor=?,version=version+1 where id=? and original_minor=0", external, source);
                var allocation = operations.allocation(root, source, "EXTERNAL", external);
                operations.share(operation, allocation, external, "ORIGINAL_SOURCE");
            }
            store.saveOutcome(root.agreement().legalPayer(), List.of(root)); store.receipt(actor, selection.commandId(), "FUND", digest, operation.id());
            return operation.id();
        });
    }

    public UUID release(UUID jobId, UUID revisionId, SubjectId side, FinalReleaseRequest request) {
        return command(() -> {
            var actor = actor();
            String digest = PaymentStore.digest(jobId + ":" + revisionId + ":" + side + ":" + request);
            var replay = replay(actor, request.commandId(), "RELEASE", digest, jobId, revisionId);
            if (replay.isPresent()) { return replay.orElseThrow(); }
            var facts = financial.requireRelease(jobId, revisionId, side, request);
            var root = store.root(PaymentStore.rootId(facts.agreement()), false);
            store.requireIdle(root); store.status(root).requireFunded();
            if (root.releasedMinor() != 0 || root.paused() || root.disputed() || root.deficitMinor() > 0 || root.remainingMinor() <= 0) { throw new PaymentFailure("La liberación está pausada, ya existe o carece de respaldo vigente"); }
            var operation = operations.create(root, new PaymentOperations.Intent(request.commandId(), actor, "RELEASE", root.remainingMinor(), 0, 0,
                    "PROVIDER:" + root.agreement().providerActor().value(), 0, 0, Optional.empty(), operations.failedPredecessor(root, "RELEASE")));
            var allocations = refundableAllocations(root);
            for (var allocation : allocations) { operations.share(operation, allocation, allocation.refundableMinor(), "ORIGINAL_SOURCE"); }
            store.saveOutcome(root.agreement().legalPayer(), List.of(root)); store.receipt(actor, request.commandId(), "RELEASE", digest, operation.id());
            return operation.id();
        });
    }

    public UUID propose(SettlementDraft draft) {
        return command(() -> {
            var actor = actor(); String digest = PaymentStore.digest(draft.toString());
            var replay = replay(actor, draft.commandId(), "PROPOSE", digest, draft.jobId(), draft.agreementRevisionId());
            if (replay.isPresent()) { return replay.orElseThrow(); }
            var facts = financial.requireSettlement(draft.jobId(), draft.agreementRevisionId(), draft.side(), preparation(draft));
            var root = store.root(PaymentStore.rootId(facts.agreement()), false);
            store.requireIdle(root); requireProposal(root, draft);
            UUID proposal = UUID.randomUUID();
            store.jdbc.update("insert into payments_settlement values(?,?,?,?,?,?,?,?,?)", proposal, root.id(), actor.value(), draft.refundMinor(),
                    draft.election().name(), draft.continueJob(), draft.remainingMinor(), root.version(), store.now());
            store.receipt(actor, draft.commandId(), "PROPOSE", digest, proposal); return proposal;
        });
    }

    public UUID retryPayout(UUID jobId, UUID revisionId, SubjectId side, FinalReleaseRequest request) {
        return command(() -> {
            var actor = actor(); String digest = PaymentStore.digest(jobId + ":" + revisionId + ":" + side + ":" + request);
            var replay = replay(actor, request.commandId(), "PAYOUT_RETRY", digest, jobId, revisionId);
            if (replay.isPresent()) { return replay.orElseThrow(); }
            var facts = financial.requireRelease(jobId, revisionId, side, request);
            var root = store.root(PaymentStore.rootId(facts.agreement()), false);
            if (!actor.equals(root.agreement().providerActor())) { throw new PaymentFailure("Solo el proveedor original solicita el reintento bancario a su destino conservado"); }
            store.requireIdle(root); store.status(root).requireFunded();
            long amount = Math.subtractExact(Math.min(root.releasedMinor(), root.remainingMinor()), root.paidMinor());
            if (amount <= 0 || root.paused() || root.disputed() || root.deficitMinor() > 0) { throw new PaymentFailure("No hay un derecho liberado y respaldado pendiente de pago bancario"); }
            UUID predecessor = operations.failedPredecessor(root, "PAYOUT").orElseThrow(() -> new PaymentFailure("Primero debe conciliarse un intento bancario definitivamente sin efecto"));
            var previous = store.operation(predecessor, false);
            if (previous.appliedMinor() != 0) { throw new PaymentFailure("Un efecto bancario parcial debe conciliarse con la misma clave"); }
            UUID release = store.jdbc.queryForObject("select release_id from payments_payout_origin where payout_id=?", UUID.class, predecessor);
            var operation = operations.create(root, new PaymentOperations.Intent(request.commandId(), actor, "PAYOUT", amount, 0, 0,
                    previous.destination(), operations.payoutFeeMinor, 0, Optional.empty(), Optional.of(predecessor)));
            store.jdbc.update("insert into payments_payout_origin values(?,?)", operation.id(), release);
            for (var allocation : refundableAllocations(root)) {
                long unpaid = Math.max(0, Math.subtractExact(allocation.refundableMinor(), allocation.paidMinor()));
                if (unpaid > 0) { operations.share(operation, allocation, unpaid, "ORIGINAL_SOURCE"); }
            }
            store.saveOutcome(root.agreement().legalPayer(), List.of(root)); store.receipt(actor, request.commandId(), "PAYOUT_RETRY", digest, operation.id()); return operation.id();
        });
    }

    public UUID assent(SettlementAssent assent) {
        return command(() -> {
            var draft = assent.expectedProposal(); var actor = actor();
            String digest = PaymentStore.digest(assent.toString());
            var replay = replay(actor, draft.commandId(), "ASSENT", digest, draft.jobId(), draft.agreementRevisionId());
            if (replay.isPresent()) { return replay.orElseThrow(); }
            var facts = financial.requireSettlement(draft.jobId(), draft.agreementRevisionId(), draft.side(), preparation(draft));
            var root = store.root(PaymentStore.rootId(facts.agreement()), false);
            store.requireIdle(root); requireProposal(root, draft);
            var proposal = proposal(assent.settlementId(), root);
            if (proposal.refundMinor() != draft.refundMinor() || proposal.election() != draft.election() || proposal.continueJob() != draft.continueJob()
                    || proposal.remainingMinor() != draft.remainingMinor() || proposal.rootVersion() != root.version()) {
                throw new PaymentFailure("Las dos partes deben aceptar la misma propuesta e importes sin cambios financieros intermedios");
            }
            String side = actor.equals(root.agreement().customerActor()) ? "CUSTOMER" : "PROVIDER";
            store.jdbc.update("insert into payments_assent values(?,?,?,?) on conflict(settlement_id,side) do nothing", proposal.id(), actor.value(), side, store.now());
            int assents = store.jdbc.queryForObject("select count(*) from payments_assent where settlement_id=?", Integer.class, proposal.id());
            UUID outcome = proposal.id();
            if (assents == 2) { outcome = refund(root, proposal, draft.commandId(), actor).id(); }
            store.receipt(actor, draft.commandId(), "ASSENT", digest, outcome); return outcome;
        });
    }

    private PaymentStore.Operation refund(PaymentStore.Root root, Proposal proposal, UUID command, ActorId actor) {
        var allocations = refundableAllocations(root);
        var weights = allocations.stream().map(value -> new RefundAllocator.Weight(value.sourceId(), value.refundableMinor())).toList();
        var shares = RefundAllocator.allocate(proposal.refundMinor(), weights);
        Map<UUID, PaymentStore.Allocation> bySource = new HashMap<>(); allocations.forEach(value -> bySource.put(value.sourceId(), value));
        long credit = 0; long cash = 0; long recovery = 0;
        for (var share : shares) {
            var allocation = bySource.get(share.sourceId());
            if (allocation.kind().equals("CREDIT") || proposal.election() == RefundDestination.CREDIT) { credit = Math.addExact(credit, share.amountMinor()); }
            else { cash = Math.addExact(cash, share.amountMinor()); }
            recovery = Math.addExact(recovery, allocation.recoveryMinor(share.amountMinor()));
        }
        var operation = operations.create(root, new PaymentOperations.Intent(command, actor, "REFUND", proposal.refundMinor(), credit, cash,
                "PAYER:" + root.agreement().legalPayer().value(), 0, recovery, Optional.of(proposal.id()), operations.failedPredecessor(root, "REFUND")));
        for (var share : shares) {
            var allocation = bySource.get(share.sourceId());
            String destination = allocation.kind().equals("CREDIT") || proposal.election() == RefundDestination.CREDIT ? "CREDIT" : "ORIGINAL_SOURCE";
            store.jdbc.update("update payments_allocation set reserved_refund_minor=reserved_refund_minor+? where id=?", share.amountMinor(), allocation.id());
            operations.share(operation, allocation, share.amountMinor(), destination);
        }
        store.saveOutcome(root.agreement().legalPayer(), List.of(root)); return operation;
    }

    private List<CreditChoice> creditChoices(PaymentStore.Root root, long amount, List<PaymentStore.Source> lots, Optional<UUID> predecessor) {
        if (predecessor.isPresent()) {
            var previous = store.operation(predecessor.orElseThrow(), false);
            if (previous.creditMinor() != amount) { throw new PaymentFailure("Un reintento debe conservar la elección de financiación original"); }
            var choices = store.jdbc.query("select a.source_id,s.amount_minor from payments_operation_share s join payments_allocation a on a.id=s.allocation_id where s.operation_id=? and a.kind='CREDIT' order by a.source_id::text",
                    (row, index) -> new CreditChoice(row.getObject(1, UUID.class), row.getLong(2)), previous.id());
            for (var choice : choices) {
                var source = lots.stream().filter(value -> value.id().equals(choice.sourceId())).findFirst().orElseThrow();
                if (source.availableMinor() < choice.amountMinor()) { throw new PaymentFailure("El respaldo original del reintento ya no está disponible"); }
            }
            return choices;
        }
        long remaining = amount; List<CreditChoice> choices = new ArrayList<>();
        for (var lot : lots) {
            long chosen = Math.min(remaining, lot.availableMinor());
            if (chosen > 0) { choices.add(new CreditChoice(lot.id(), chosen)); remaining = Math.subtractExact(remaining, chosen); }
        }
        if (remaining != 0) { throw new PaymentFailure("Saldo propio insuficiente"); }
        return List.copyOf(choices);
    }

    private Optional<UUID> replay(ActorId actor, UUID command, String operation, String digest, UUID job, UUID revision) {
        var candidate = store.receipt(actor, command, operation, digest);
        if (candidate.isEmpty()) { return Optional.empty(); }
        var discovered = jobs.discover(job);
        var root = store.find(job, discovered.agreement().orElseThrow(), false).orElseThrow();
        SubjectId side = actor.equals(discovered.conversation().customerActor()) ? discovered.conversation().customer() : discovered.conversation().provider();
        financial.requireSettlement(job, revision, side, new SettlementPreparation(command, discovered.version(), root.version()));
        return store.receipt(actor, command, operation, digest);
    }

    private static SettlementPreparation preparation(SettlementDraft draft) { return new SettlementPreparation(draft.commandId(), draft.expectedJobVersion(), draft.expectedMoneyVersion()); }
    private void requireProposal(PaymentStore.Root root, SettlementDraft draft) {
        if (root.fundedMinor() != root.agreement().totalMinor() || root.deficitMinor() != 0 || draft.refundMinor() > root.remainingMinor()
                || draft.remainingMinor() != Math.subtractExact(root.remainingMinor(), draft.refundMinor())) { throw new PaymentFailure("Reembolso o derecho restante incompatible con el respaldo confirmado"); }
        if (store.hasFrozenSource(root)) { throw new PaymentFailure("La fuente está congelada; primero debe conciliarse su respaldo mediante ayuda"); }
    }
    private List<PaymentStore.Allocation> refundableAllocations(PaymentStore.Root root) {
        return store.allocations(root.agreement().legalPayer(), false).stream().filter(value -> value.rootId().equals(root.id()) && value.refundableMinor() > 0).toList();
    }
    private Proposal proposal(UUID id, PaymentStore.Root root) {
        return store.jdbc.query("select * from payments_settlement where id=? and root_id=? for update", (row, index) ->
                new Proposal(id, row.getLong("refund_minor"), RefundDestination.valueOf(row.getString("election")), row.getBoolean("continue_job"), row.getLong("remaining_minor"), row.getLong("root_version")), id, root.id())
                .stream().findFirst().orElseThrow(() -> new PaymentFailure("Propuesta ajena a la obligación"));
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new PaymentFailure("Cuenta autenticada requerida"))); }
    private <T> T command(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new PaymentFailure("El comando requiere una transacción nueva con sus propietarios"); }
        return transaction.execute(status -> action.get());
    }
    record Proposal(UUID id, long refundMinor, RefundDestination election, boolean continueJob, long remainingMinor, long rootVersion) {}
    private record CreditChoice(UUID sourceId, long amountMinor) {}
}
