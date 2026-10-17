package ar.changeo.payments;

import ar.changeo.agreements.api.AgreementTerms;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.ContentDigest;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.api.JobMoneyStatus;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
class PaymentStore {
    static final int MAX_PAYER_SOURCES = 200;
    static final int MAX_PAYER_ROOTS = 200;
    static final int MAX_ROOT_OPERATIONS = 1000;
    final JdbcTemplate jdbc;
    final Clock clock;

    PaymentStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    void capital() {
        transaction();
        if (jdbc.queryForList("select currency from payments_capital where currency='ARS' for update").isEmpty()) {
            throw new PaymentFailure("Capital sintético pendiente; no se puede usar capital del cliente");
        }
    }

    void payer(AccountId payer, boolean bootstrap) {
        if (bootstrap) { jdbc.update("insert into payments_balance(payer_id) values(?) on conflict do nothing", payer.value()); }
        if (jdbc.queryForList("select payer_id from payments_balance where payer_id=? for update", payer.value()).isEmpty()) {
            throw new PaymentFailure("Saldo histórico no disponible");
        }
    }

    List<Source> sources(AccountId payer, boolean lock) {
        var sources = jdbc.query("select * from payments_source where payer_id=? order by id::text limit ?" + (lock ? " for update" : ""),
                sourceMapper(), payer.value(), MAX_PAYER_SOURCES + 1);
        if (sources.size() > MAX_PAYER_SOURCES) { throw new PaymentFailure("Soporte requerido: la exposición de fuentes excede el límite técnico completo"); }
        return List.copyOf(sources);
    }

    List<Allocation> allocations(AccountId payer, boolean lock) {
        var allocations = jdbc.query("select a.* from payments_allocation a where a.source_id in (select id from payments_source where payer_id=?) order by a.id::text limit ?" + (lock ? " for update of a" : ""),
                allocationMapper(), payer.value(), MAX_PAYER_SOURCES * MAX_PAYER_ROOTS + 1);
        if (allocations.size() > MAX_PAYER_SOURCES * MAX_PAYER_ROOTS) { throw new PaymentFailure("Soporte requerido: la exposición de asignaciones excede el límite técnico completo"); }
        return List.copyOf(allocations);
    }

    List<Root> roots(AccountId payer, boolean lock) {
        var roots = jdbc.query("select * from payments_root where payer_id=? order by id::text limit ?" + (lock ? " for update" : ""),
                rootMapper(), payer.value(), MAX_PAYER_ROOTS + 1);
        if (roots.size() > MAX_PAYER_ROOTS) { throw new PaymentFailure("Soporte requerido: los trabajos monetarios exceden el límite técnico completo"); }
        return List.copyOf(roots);
    }

    List<Operation> operations(UUID root, boolean lock) {
        var operations = jdbc.query("select * from payments_operation where root_id=? order by id::text limit ?" + (lock ? " for update" : ""),
                operationMapper(), root, MAX_ROOT_OPERATIONS + 1);
        if (operations.size() > MAX_ROOT_OPERATIONS) { throw new PaymentFailure("Soporte requerido: el historial de operaciones excede el límite técnico completo"); }
        return List.copyOf(operations);
    }

    void protectExisting(UUID rootId) {
        var discovered = root(rootId, false);
        capital(); payer(discovered.agreement().legalPayer(), false);
        sources(discovered.agreement().legalPayer(), true);
        allocations(discovered.agreement().legalPayer(), true);
        var protectedRoots = roots(discovered.agreement().legalPayer(), true);
        var protectedRoot = protectedRoots.stream().filter(value -> value.id().equals(rootId)).findFirst().orElseThrow();
        protectedRoot.requireBinding(discovered.agreement());
        protectedOperations(discovered.agreement().legalPayer(), rootId);
    }

    void prepareActive(FinancialAgreement agreement, Optional<UUID> externalSource) {
        capital(); payer(agreement.legalPayer(), true);
        externalSource.ifPresent(source -> jdbc.update("insert into payments_source(id,payer_id,origin_root) values(?,?,?) on conflict do nothing",
                source, agreement.legalPayer().value(), rootId(agreement)));
        sources(agreement.legalPayer(), true); allocations(agreement.legalPayer(), true);
        bootstrap(agreement, "ACTIVE");
        roots(agreement.legalPayer(), true);
        var root = root(rootId(agreement), false); root.requireBinding(agreement);
        if (!root.lifecycle().equals("ACTIVE")) { throw new PaymentFailure("La revisión monetaria ya no está seleccionada"); }
        protectedOperations(agreement.legalPayer(), root.id());
    }

    void prepareReplacement(FinancialAgreement original, FinancialAgreement replacement) {
        capital(); payer(original.legalPayer(), true);
        sources(original.legalPayer(), true); allocations(original.legalPayer(), true);
        bootstrap(original, "ACTIVE"); bootstrap(replacement, "PROSPECTIVE");
        roots(original.legalPayer(), true);
        protectedOperations(original.legalPayer(), rootId(original));
        root(rootId(original), false).requireBinding(original); root(rootId(replacement), false).requireBinding(replacement);
    }

    private void protectedOperations(AccountId payer, UUID selectedRoot) {
        var operations = jdbc.query("select o.* from payments_operation o where o.root_id in (select id from payments_root where payer_id=?) and (o.root_id=? or o.state in ('QUEUED','CLAIMED','DISPATCHING','UNKNOWN','RETRY_WAIT','RECOVERY')) order by o.id::text limit 2001 for update of o",
                operationMapper(), payer.value(), selectedRoot);
        if (operations.size() > 2000) { throw new PaymentFailure("Soporte requerido: las operaciones expuestas exceden el límite técnico completo"); }
    }

    List<Operation> loyaltyOperations(AccountId payer, Set<UUID> selectedRoots) {
        String selected = selectedRoots.isEmpty() ? "false" : "o.root_id in (" + String.join(",", Collections.nCopies(selectedRoots.size(), "?")) + ")";
        List<Object> arguments = new ArrayList<>(); arguments.add(payer.value()); arguments.addAll(selectedRoots.stream().sorted(Comparator.comparing(UUID::toString)).toList());
        var operations = jdbc.query("select o.* from payments_operation o where o.root_id in (select id from payments_root where payer_id=?) and (" + selected
                + " or o.state in ('QUEUED','CLAIMED','DISPATCHING','UNKNOWN','RETRY_WAIT','RECOVERY')) order by o.id::text limit 2001", operationMapper(), arguments.toArray());
        if (operations.size() > 2000 || selectedRoots.stream().anyMatch(root -> operations.stream().filter(operation -> operation.rootId().equals(root)).count() > MAX_ROOT_OPERATIONS)) {
            throw new PaymentFailure("Soporte requerido: las operaciones expuestas exceden el límite técnico completo");
        }
        return List.copyOf(operations);
    }

    private void bootstrap(FinancialAgreement agreement, String lifecycle) {
        boolean active = lifecycle.equals("ACTIVE");
        jdbc.update("insert into payments_root(id,job_id,original_quote,original_revision,payer_id,customer_id,provider_id,customer_actor,provider_actor,currency,service_minor,delivery_minor,total_minor,policy_scope,policy_version,final_rule,canonical_version,lifecycle,active_job,active_quote,active_revision,active_payer,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing",
                rootId(agreement), agreement.jobId(), agreement.agreement().resourceId(), agreement.agreement().revisionId(), agreement.legalPayer().value(),
                agreement.customer().value(), agreement.provider().value(), agreement.customerActor().value(), agreement.providerActor().value(), "ARS",
                agreement.serviceMinor(), agreement.deliveryMinor(), agreement.totalMinor(), agreement.policyScope().name(), agreement.policyVersion().name(),
                agreement.finalRule().name(), agreement.canonicalVersion(), lifecycle, active ? agreement.jobId() : null,
                active ? agreement.agreement().resourceId() : null, active ? agreement.agreement().revisionId() : null,
                active ? agreement.legalPayer().value() : null, now());
    }

    Optional<Root> find(UUID job, ContentReference agreement, boolean lock) {
        return jdbc.query("select * from payments_root where job_id=? and original_quote=? and original_revision=?" + (lock ? " for update" : ""),
                rootMapper(), job, agreement.resourceId(), agreement.revisionId()).stream().findFirst();
    }

    Root root(UUID id, boolean lock) {
        return jdbc.query("select * from payments_root where id=?" + (lock ? " for update" : ""), rootMapper(), id)
                .stream().findFirst().orElseThrow(() -> new PaymentFailure("Obligación monetaria no disponible"));
    }

    Operation operation(UUID id, boolean lock) {
        return jdbc.query("select * from payments_operation where id=?" + (lock ? " for update" : ""), operationMapper(), id)
                .stream().findFirst().orElseThrow(() -> new PaymentFailure("Operación monetaria no disponible"));
    }

    Optional<UUID> receipt(ActorId actor, UUID command, String operation, String digest) {
        var receipts = jdbc.queryForList("select operation,digest,outcome_id from payments_receipt where actor_id=? and command_id=?", actor.value(), command);
        if (receipts.isEmpty()) { return Optional.empty(); }
        var receipt = receipts.getFirst();
        if (!operation.equals(receipt.get("operation")) || !digest.equals(receipt.get("digest"))) { throw new PaymentFailure("El comando está ligado a otra operación o importe"); }
        return Optional.of((UUID) receipt.get("outcome_id"));
    }

    void receipt(ActorId actor, UUID command, String operation, String digest, UUID outcome) {
        jdbc.update("insert into payments_receipt values(?,?,?,?,?,?)", actor.value(), command, operation, digest, outcome, now());
    }

    boolean neverFunded(UUID root) {
        return jdbc.queryForObject("select not exists(select 1 from payments_operation where root_id=?) and not exists(select 1 from payments_allocation where root_id=?) and not exists(select 1 from payments_settlement where root_id=?) and not exists(select 1 from payments_pause where root_id=?) and not exists(select 1 from payments_ledger where root_id=?)",
                Boolean.class, root, root, root, root, root);
    }

    JobMoneyStatus status(Root root) {
        var operations = operations(root.id(), false);
        boolean unknown = operations.stream().anyMatch(value -> Set.of("UNKNOWN", "DISPATCHING", "RECOVERY").contains(value.state()));
        boolean pending = operations.stream().anyMatch(value -> Set.of("QUEUED", "CLAIMED", "RETRY_WAIT").contains(value.state()));
        JobMoneyStatus.Funding funding;
        if (root.deficitMinor() > 0) { funding = JobMoneyStatus.Funding.REVERSED; }
        else if (unknown || root.paused() || root.disputed()) { funding = JobMoneyStatus.Funding.UNKNOWN; }
        else if (pending) { funding = JobMoneyStatus.Funding.PENDING; }
        else if (root.fundedMinor() == root.agreement().totalMinor() && root.fundedMinor() > 0) {
            if (root.refundedMinor() == 0) { funding = JobMoneyStatus.Funding.CONFIRMED_FULL; }
            else if (root.remainingMinor() == 0) { funding = JobMoneyStatus.Funding.REFUNDED; }
            else { funding = root.bilateralContinuation() ? JobMoneyStatus.Funding.CONFIRMED_REMAINING : JobMoneyStatus.Funding.PARTIALLY_REFUNDED; }
        } else { funding = neverFunded(root.id()) ? JobMoneyStatus.Funding.UNFUNDED : JobMoneyStatus.Funding.FAILED; }
        return new JobMoneyStatus(root.agreement().jobId(), root.agreement().agreement(), funding,
                releaseStatus(root, operations), payoutStatus(root, operations));
    }

    private static JobMoneyStatus.Release releaseStatus(Root root, List<Operation> operations) {
        if (root.releasedMinor() > 0) { return JobMoneyStatus.Release.RELEASED; }
        var releases = operations.stream().filter(value -> value.kind().equals("RELEASE")).toList();
        if (releases.stream().anyMatch(Operation::uncertain)) { return JobMoneyStatus.Release.UNKNOWN; }
        if (releases.stream().anyMatch(Operation::pending)) { return JobMoneyStatus.Release.PENDING; }
        return releases.isEmpty() ? JobMoneyStatus.Release.NOT_RELEASED : JobMoneyStatus.Release.FAILED;
    }

    private static JobMoneyStatus.BankPayout payoutStatus(Root root, List<Operation> operations) {
        if (root.paidMinor() > 0) { return JobMoneyStatus.BankPayout.PAID; }
        var payouts = operations.stream().filter(value -> value.kind().equals("PAYOUT")).toList();
        if (payouts.stream().anyMatch(Operation::uncertain)) { return JobMoneyStatus.BankPayout.UNKNOWN; }
        if (payouts.stream().anyMatch(Operation::pending)) { return JobMoneyStatus.BankPayout.PENDING; }
        return payouts.isEmpty() ? JobMoneyStatus.BankPayout.NOT_PAID : JobMoneyStatus.BankPayout.FAILED;
    }

    void requireIdle(Root root) {
        if (operations(root.id(), false).stream().anyMatch(value -> value.pending() || value.uncertain())) {
            throw new PaymentFailure("Hay una operación pendiente o incierta; conciliá la misma clave antes de otra acción");
        }
    }

    boolean hasFrozenSource(Root root) {
        Set<UUID> sources = new HashSet<>();
        allocations(root.agreement().legalPayer(), false).stream().filter(value -> value.rootId().equals(root.id())).forEach(value -> sources.add(value.sourceId()));
        return sources(root.agreement().legalPayer(), false).stream().anyMatch(value -> sources.contains(value.id()) && value.frozen());
    }

    void saveOutcome(AccountId payer, List<Root> projections) {
        transaction();
        if (projections.isEmpty() || projections.stream().anyMatch(root -> !root.agreement().legalPayer().equals(payer))) {
            throw new PaymentFailure("Proyecciones ajenas al pagador protegido");
        }
        for (var root : projections) {
            if (jdbc.update("update payments_root set funded_minor=?,refunded_minor=?,released_minor=?,paid_minor=?,deficit_minor=?,paused=?,disputed=?,bilateral_continuation=?,version=? where id=? and version=?",
                    root.fundedMinor(), root.refundedMinor(), root.releasedMinor(), root.paidMinor(), root.deficitMinor(), root.paused(), root.disputed(),
                    root.bilateralContinuation(), Math.addExact(root.version(), 1), root.id(), root.version()) != 1) {
                throw new PaymentFailure("La proyección monetaria protegida cambió");
            }
        }
        if (jdbc.update("update payments_balance set version=version+1 where payer_id=?", payer.value()) != 1) {
            throw new PaymentFailure("El pagador protegido no tiene proyección disponible");
        }
    }

    void reserveCapital(long feeMinor, long recoveryMinor) {
        long amount = Math.addExact(feeMinor, recoveryMinor);
        if (amount == 0) { return; }
        if (jdbc.update("update payments_capital set available_minor=available_minor-?,reserved_minor=reserved_minor+? where currency='ARS' and available_minor>=?", amount, amount, amount) != 1) {
            throw new PaymentFailure("Capital independiente insuficiente; el respaldo del cliente queda protegido y el caso requiere soporte");
        }
    }

    void releaseCapital(Operation operation) {
        long reserved = Math.addExact(operation.feeMinor(), operation.recoveryMinor());
        long consumed = jdbc.queryForObject("select coalesce(sum(l.signed_minor),0)::bigint from payments_ledger l join payments_observation o on o.event_id=l.batch_id where o.operation_id=? and l.account in ('PLATFORM_EXPENSE','PLATFORM_RECOVERY_EXPENSE')", Long.class, operation.id());
        reserved = Math.subtractExact(reserved, consumed);
        if (reserved == 0) { return; }
        jdbc.update("update payments_capital set reserved_minor=reserved_minor-?,available_minor=available_minor+? where currency='ARS'", reserved, reserved);
    }

    void consumeCapital(long feeMinor, long recoveryMinor) {
        long consumed = Math.addExact(feeMinor, recoveryMinor);
        if (consumed == 0) { return; }
        jdbc.update("update payments_capital set reserved_minor=reserved_minor-?,fee_minor=fee_minor+?,recovery_minor=recovery_minor+? where currency='ARS'", consumed, feeMinor, recoveryMinor);
    }

    void ledger(UUID batch, Root root, List<Entry> entries) {
        long sum = 0;
        for (var entry : entries) { sum = Math.addExact(sum, entry.signedMinor()); }
        if (sum != 0) { throw new PaymentFailure("Asiento financiero desbalanceado"); }
        int line = 0;
        for (var entry : entries) {
            if (entry.signedMinor() == 0) { continue; }
            jdbc.update("insert into payments_ledger values(?,?,?,?,?,?,?,?)", batch, line++, root == null ? null : root.id(),
                    entry.sourceId().orElse(null), entry.account().name(), entry.signedMinor(), "ARS", now());
        }
    }

    Timestamp now() { return Timestamp.from(clock.instant()); }
    static UUID rootId(FinancialAgreement agreement) { return id("root:" + agreement.jobId() + ":" + agreement.agreement()); }
    static UUID sourceId(FinancialAgreement agreement, UUID command) { return id("source:" + rootId(agreement) + ":" + command); }
    static UUID id(String binding) { return UUID.nameUUIDFromBytes(binding.getBytes(StandardCharsets.UTF_8)); }
    static String digest(String value) { return ContentDigest.bytes(value.getBytes(StandardCharsets.UTF_8)); }
    static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new PaymentFailure("La operación requiere una transacción financiera protegida"); }
    }
    static FinancialAgreement monetary(FundingAgreement agreement) {
        var terms = agreement.terms();
        return new FinancialAgreement(agreement.jobId(), agreement.agreement(), agreement.customer(), agreement.provider(),
                agreement.customerActor(), agreement.providerActor(), agreement.legalPayer(), agreement.serviceMinor(), agreement.deliveryMinor(),
                agreement.totalMinor(), Currency.getInstance("ARS"), terms.policyScope(), terms.policyVersion(), terms.finalRule(), agreement.canonicalVersion());
    }

    private static RowMapper<Root> rootMapper() {
        return (row, index) -> new Root(row.getObject("id", UUID.class), new FinancialAgreement(row.getObject("job_id", UUID.class),
                new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, row.getObject("original_quote", UUID.class), row.getObject("original_revision", UUID.class)),
                new SubjectId(row.getObject("customer_id", UUID.class)), new SubjectId(row.getObject("provider_id", UUID.class)),
                new ActorId(row.getObject("customer_actor", UUID.class)), new ActorId(row.getObject("provider_actor", UUID.class)),
                new AccountId(row.getObject("payer_id", UUID.class)), row.getLong("service_minor"), row.getLong("delivery_minor"), row.getLong("total_minor"),
                Currency.getInstance(row.getString("currency")), AgreementTerms.PolicyScope.valueOf(row.getString("policy_scope")),
                AgreementTerms.PolicyVersion.valueOf(row.getString("policy_version")), AgreementTerms.FinalRule.valueOf(row.getString("final_rule")), row.getLong("canonical_version")),
                row.getString("lifecycle"), row.getLong("funded_minor"), row.getLong("refunded_minor"), row.getLong("released_minor"), row.getLong("paid_minor"),
                row.getLong("deficit_minor"), row.getBoolean("paused"), row.getBoolean("disputed"), row.getBoolean("bilateral_continuation"), row.getLong("version"));
    }
    private static RowMapper<Source> sourceMapper() {
        return (row, index) -> new Source(row.getObject("id", UUID.class), row.getObject("origin_root", UUID.class), row.getLong("original_minor"),
                row.getLong("confirmed_minor"), row.getLong("backing_minor"), row.getLong("reversed_minor"), row.getLong("cash_refunded_minor"),
                row.getLong("credit_minor"), row.getLong("reserved_credit_minor"), row.getBoolean("frozen"));
    }
    private static RowMapper<Allocation> allocationMapper() {
        return (row, index) -> new Allocation(row.getObject("id", UUID.class), row.getObject("root_id", UUID.class), row.getObject("source_id", UUID.class),
                row.getString("kind"), row.getLong("original_minor"), row.getLong("confirmed_minor"), row.getLong("refunded_minor"),
                row.getLong("reserved_refund_minor"), row.getLong("cash_refunded_minor"), row.getLong("credit_refunded_minor"), row.getLong("paid_minor"));
    }
    private static RowMapper<Operation> operationMapper() {
        return (row, index) -> new Operation(row.getObject("id", UUID.class), row.getObject("root_id", UUID.class), row.getObject("command_id", UUID.class),
                new ActorId(row.getObject("actor_id", UUID.class)), row.getString("kind"), row.getLong("amount_minor"), row.getLong("credit_minor"), row.getLong("external_minor"),
                row.getString("destination"), row.getObject("stable_key", UUID.class), Optional.ofNullable(row.getObject("predecessor", UUID.class)),
                Optional.ofNullable(row.getObject("settlement_id", UUID.class)), row.getLong("fee_minor"), row.getLong("recovery_minor"), row.getString("state"),
                row.getLong("generation"), row.getInt("attempts"), Optional.ofNullable(row.getObject("claim_token", UUID.class)),
                Optional.ofNullable(row.getTimestamp("lease_until")).map(Timestamp::toInstant), row.getTimestamp("available_at").toInstant(),
                Optional.ofNullable(row.getTimestamp("dispatched_at")).map(Timestamp::toInstant), row.getBoolean("applied"), row.getLong("observed_minor"), row.getLong("applied_minor"));
    }

    record Root(UUID id, FinancialAgreement agreement, String lifecycle, long fundedMinor, long refundedMinor,
            long releasedMinor, long paidMinor, long deficitMinor, boolean paused, boolean disputed, boolean bilateralContinuation, long version) {
        long remainingMinor() { return Math.subtractExact(fundedMinor, refundedMinor); }
        Root withFunding(long amount, boolean lostBacking) {
            return project(Math.addExact(fundedMinor, amount), refundedMinor, releasedMinor, paidMinor, deficitMinor, paused || lostBacking, disputed, bilateralContinuation);
        }
        Root withRelease(long amount) {
            return project(fundedMinor, refundedMinor, Math.addExact(releasedMinor, amount), paidMinor, deficitMinor, paused, disputed, bilateralContinuation);
        }
        Root withPayout(long amount) {
            return project(fundedMinor, refundedMinor, releasedMinor, Math.addExact(paidMinor, amount), deficitMinor, paused, disputed, bilateralContinuation);
        }
        Root withRefund(long amount, boolean complete, boolean continuation, boolean blocked) {
            return project(fundedMinor, Math.addExact(refundedMinor, amount), releasedMinor, paidMinor, deficitMinor,
                    complete ? blocked : paused, complete ? blocked : disputed, complete ? continuation : bilateralContinuation);
        }
        Root withPause(boolean dispute) {
            return project(fundedMinor, refundedMinor, releasedMinor, paidMinor, deficitMinor, true, disputed || dispute, bilateralContinuation);
        }
        Root withDeficit(long amount) {
            return project(fundedMinor, refundedMinor, releasedMinor, paidMinor, Math.addExact(deficitMinor, amount), paused, disputed, bilateralContinuation);
        }
        private Root project(long funded, long refunded, long released, long paid, long deficit, boolean pause, boolean dispute, boolean continuation) {
            return new Root(id, agreement, lifecycle, funded, refunded, released, paid, deficit, pause, dispute, continuation, version);
        }
        void requireBinding(FinancialAgreement facts) {
            if (!agreement.jobId().equals(facts.jobId()) || !agreement.agreement().equals(facts.agreement())
                    || !agreement.customer().equals(facts.customer()) || !agreement.provider().equals(facts.provider())
                    || !agreement.customerActor().equals(facts.customerActor()) || !agreement.providerActor().equals(facts.providerActor())
                    || !agreement.legalPayer().equals(facts.legalPayer()) || agreement.serviceMinor() != facts.serviceMinor()
                    || agreement.deliveryMinor() != facts.deliveryMinor() || agreement.totalMinor() != facts.totalMinor()
                    || !agreement.currency().equals(facts.currency()) || agreement.policyScope() != facts.policyScope()
                    || agreement.policyVersion() != facts.policyVersion() || agreement.finalRule() != facts.finalRule()) {
                throw new PaymentFailure("Los hechos no corresponden a la obligación financiera original");
            }
        }
    }
    record Source(UUID id, UUID originRoot, long originalMinor, long confirmedMinor, long backingMinor, long reversedMinor,
            long cashRefundedMinor, long creditMinor, long reservedCreditMinor, boolean frozen) {
        long availableMinor() { return frozen ? 0 : Math.subtractExact(creditMinor, reservedCreditMinor); }
    }
    record Allocation(UUID id, UUID rootId, UUID sourceId, String kind, long originalMinor, long confirmedMinor, long refundedMinor,
            long reservedRefundMinor, long cashRefundedMinor, long creditRefundedMinor, long paidMinor) {
        long refundableMinor() { return Math.subtractExact(Math.subtractExact(confirmedMinor, refundedMinor), reservedRefundMinor); }
        long recoveryMinor(long refundMinor) { return Math.max(0, Math.subtractExact(refundMinor, Math.max(0, Math.subtractExact(Math.subtractExact(confirmedMinor, refundedMinor), paidMinor)))); }
    }
    record Operation(UUID id, UUID rootId, UUID commandId, ActorId actor, String kind, long amountMinor, long creditMinor, long externalMinor,
            String destination, UUID stableKey, Optional<UUID> predecessor, Optional<UUID> settlementId, long feeMinor, long recoveryMinor,
            String state, long generation, int attempts, Optional<UUID> claimToken, Optional<Instant> leaseUntil, Instant availableAt,
            Optional<Instant> dispatchedAt, boolean applied, long observedMinor, long appliedMinor) {
        boolean uncertain() { return Set.of("UNKNOWN", "DISPATCHING", "RECOVERY").contains(state); }
        boolean pending() { return Set.of("QUEUED", "CLAIMED", "RETRY_WAIT").contains(state); }
    }
    enum LedgerAccount { CUSTODY_CASH, JOB_LIABILITY, CREDIT_LIABILITY, PROVIDER_LIABILITY, PLATFORM_CASH, PLATFORM_CAPITAL, PLATFORM_EXPENSE, PLATFORM_RECOVERY_EXPENSE, RECOVERY_RECEIVABLE }
    record Entry(LedgerAccount account, long signedMinor, Optional<UUID> sourceId) {
        Entry(LedgerAccount account, long signedMinor) { this(account, signedMinor, Optional.empty()); }
    }
}
