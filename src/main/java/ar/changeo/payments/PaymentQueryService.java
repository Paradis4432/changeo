package ar.changeo.payments;

import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.payments.api.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
public class PaymentQueryService {
    private final PaymentStore store;
    private final JobFinancialAccess financial;
    private final IdentityAccess identity;
    private final SecurityProof proof;

    PaymentQueryService(PaymentStore store, JobFinancialAccess financial, IdentityAccess identity, SecurityProof proof) {
        this.store = store; this.financial = financial; this.identity = identity; this.proof = proof;
    }

    @Transactional
    public JobView job(UUID jobId, SubjectId side) {
        var facts = financial.requireView(jobId, side);
        var reference = facts.agreement().agreement();
        var root = store.root(PaymentStore.rootId(facts.agreement()), false);
        var history = store.operations(root.id(), false).stream().map(value -> new OperationView(value.id(), value.kind(), value.amountMinor(), value.creditMinor(),
                value.externalMinor(), value.state(), value.predecessor(), value.settlementId())).toList();
        var proposals = store.jdbc.query("select s.*, (select count(*) from payments_assent a where a.settlement_id=s.id) assent_count, exists(select 1 from payments_assent a where a.settlement_id=s.id and a.actor_id=?) own_assent from payments_settlement s where s.root_id=? order by s.created_at,s.id limit 1001",
                (row, index) -> new ProposalView(row.getObject("id", UUID.class), row.getLong("refund_minor"), RefundDestination.valueOf(row.getString("election")),
                        row.getBoolean("continue_job"), row.getLong("remaining_minor"), row.getLong("root_version"), row.getInt("assent_count"), row.getBoolean("own_assent")), actor().value(), root.id());
        if (proposals.size() > 1000) { throw new PaymentFailure("Soporte requerido: el historial de propuestas excede el límite técnico completo"); }
        return new JobView(jobId, reference.revisionId(), side.value(), facts.state(), facts.agreement().canonicalVersion(), root.version(),
                facts.agreement().serviceMinor(), facts.agreement().deliveryMinor(), facts.agreement().totalMinor(), root.fundedMinor(), root.refundedMinor(),
                root.remainingMinor(), root.releasedMinor(), root.paidMinor(), root.deficitMinor(), root.paused(), root.disputed(),
                actor().value().equals(facts.agreement().legalPayer().value()), facts.finalAcceptedBy().isPresent(), store.status(root), List.copyOf(history), List.copyOf(proposals));
    }

    @Transactional
    public BalanceView balance() {
        var account = actor(); identity.requireForUpdate(PermissionRequest.self(account, Capability.ACCESS_FINANCIAL_RIGHTS));
        if (store.jdbc.queryForList("select payer_id from payments_balance where payer_id=?", account.value()).isEmpty()) { return new BalanceView(0, 0, 0, List.of()); }
        store.payer(account, false);
        long available = 0; long reserved = 0; long frozen = 0;
        for (var source : store.sources(account, true)) {
            available = Math.addExact(available, source.availableMinor()); reserved = Math.addExact(reserved, source.reservedCreditMinor());
            if (source.frozen()) { frozen = Math.addExact(frozen, source.creditMinor()); }
        }
        var obligations = store.roots(account, false).stream().filter(value -> !value.lifecycle().equals("PROSPECTIVE"))
                .map(value -> new ObligationView(value.agreement().jobId(), value.agreement().customer().value(), value.agreement().totalMinor(), value.refundedMinor(), value.remainingMinor(), store.status(value))).toList();
        return new BalanceView(available, reserved, frozen, obligations);
    }

    @Transactional
    public List<AuditView> audit(long mfaEpoch, Optional<UUID> after) {
        var account = actor(); identity.requireForUpdate(PermissionRequest.self(account, Capability.ADMIN_AUDIT));
        if (!proof.mfa(account.value(), mfaEpoch) || !proof.freshPassword(account.value(), mfaEpoch)) { throw new PaymentFailure("La auditoría financiera requiere MFA y contraseña reciente del mismo contexto vigente"); }
        return store.jdbc.query("select id,kind,amount_minor,state,created_at from payments_operation where (?::uuid is null or id::text>?::text) order by id::text limit 51",
                (row, index) -> new AuditView(row.getObject("id", UUID.class), row.getString("kind"), row.getLong("amount_minor"), row.getString("state"), row.getTimestamp("created_at").toInstant()), after.orElse(null), after.orElse(null));
    }

    private AccountId actor() { return new AccountId(proof.actor().orElseThrow(() -> new PaymentFailure("Cuenta autenticada requerida"))); }
    public record OperationView(UUID id, String kind, long amountMinor, long creditMinor, long externalMinor, String state, Optional<UUID> predecessor, Optional<UUID> settlementId) {}
    public record ProposalView(UUID id, long refundMinor, RefundDestination election, boolean continueJob, long remainingMinor, long moneyVersion, int assents, boolean ownAssent) {}
    public record JobView(UUID jobId, UUID revisionId, UUID subject, JobSnapshot.State jobState, long jobVersion, long moneyVersion, long serviceMinor, long deliveryMinor,
            long totalMinor, long fundedMinor, long refundedMinor, long remainingMinor, long releasedMinor, long paidMinor, long deficitMinor, boolean paused, boolean disputed,
            boolean legalPayer, boolean finalAccepted, JobMoneyStatus money, List<OperationView> operations, List<ProposalView> proposals) {}
    public record ObligationView(UUID jobId, UUID subject, long originalMinor, long refundedMinor, long remainingMinor, JobMoneyStatus money) {}
    public record BalanceView(long availableMinor, long reservedMinor, long frozenMinor, List<ObligationView> obligations) {}
    public record AuditView(UUID operationId, String kind, long amountMinor, String state, Instant createdAt) {}
}
