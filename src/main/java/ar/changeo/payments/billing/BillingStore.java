package ar.changeo.payments.billing;

import ar.changeo.identity.api.*;
import ar.changeo.moderation.ContentDigest;
import ar.changeo.payments.api.*;
import ar.changeo.payments.gateway.GatewayRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

@Component
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class BillingStore {
    final JdbcTemplate jdbc;
    final Clock clock;

    BillingStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    ProtectedBilling protect(BillingBinding binding, boolean createWallet) {
        protectAll(List.of(binding), createWallet);
        return context().protections.get(walletId(binding));
    }

    void protectAll(List<BillingBinding> bindings, boolean createWallet) {
        requireTransaction();
        if (bindings.isEmpty() || bindings.size() > 32 || new HashSet<>(bindings).size() != bindings.size()) { throw failure("Inscripciones financieras incompatibles"); }
        var declaredWallets = bindings.stream().map(BillingStore::walletId).collect(java.util.stream.Collectors.toSet());
        if (!context().protections.isEmpty()) {
            if (!context().protections.keySet().containsAll(declaredWallets)) { throw failure("El lote financiero no declaró todos sus créditos antes de protegerlos"); }
            return;
        }
        var budgets = jdbc.queryForList("select kind from payments_subscription_budget order by kind for update");
        if (budgets.size() != 2) { throw failure("Respaldo independiente de suscripciones pendiente"); }
        var ordered = bindings.stream().sorted(Comparator.comparing(binding -> walletId(binding).toString())).toList();
        for (var binding : ordered) {
            UUID walletId = walletId(binding);
            if (createWallet) { jdbc.update("insert into payments_promotion_wallet(id,campaign,provider_id,payer_id) values(?,'SL1B-LOYALTY-2',?,?) on conflict do nothing", walletId, binding.provider().value(), binding.originalPayer().value()); }
            wallet(walletId, true);
        }
        var wallets = ordered.stream().map(BillingStore::walletId).distinct().toList();
        String marks = String.join(",", Collections.nCopies(wallets.size(), "?"));
        List<Object> rootArguments = new ArrayList<>(wallets); rootArguments.add(Math.addExact(Math.multiplyExact(200, wallets.size()), 1));
        var roots = jdbc.query("select id,wallet_id from payments_billing_root where wallet_id in (" + marks + ") order by id::text limit ? for update",
                (row, index) -> Map.entry(row.getObject(1, UUID.class), row.getObject(2, UUID.class)), rootArguments.toArray());
        List<Object> operationArguments = new ArrayList<>(wallets); operationArguments.add(Math.addExact(Math.multiplyExact(2000, wallets.size()), 1));
        var operations = jdbc.query("select o.id,r.wallet_id from payments_billing_operation o join payments_billing_root r on r.id=o.root_id where r.wallet_id in (" + marks + ") order by o.id::text limit ? for update of o",
                (row, index) -> Map.entry(row.getObject(1, UUID.class), row.getObject(2, UUID.class)), operationArguments.toArray());
        var held = context().protections;
        for (UUID wallet : wallets) {
            var walletRoots = roots.stream().filter(entry -> entry.getValue().equals(wallet)).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            var walletOperations = operations.stream().filter(entry -> entry.getValue().equals(wallet)).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            if (walletRoots.size() > 200 || walletOperations.size() > 2000) { throw failure("Historial completo excede el límite técnico; solicitá ayuda"); }
            held.put(wallet, new ProtectedBilling(wallet, Set.copyOf(walletRoots), Set.copyOf(walletOperations)));
        }
    }

    void declareCreated(UUID wallet, UUID root, UUID operation) {
        var held = context().protections;
        var previous = held.get(wallet);
        if (previous == null) { throw failure("Facturación sin padres protegidos"); }
        Set<UUID> roots = new HashSet<>(previous.roots()); roots.add(root);
        Set<UUID> operations = new HashSet<>(previous.operations()); operations.add(operation);
        if (roots.size() > 200 || operations.size() > 2000) { throw failure("El nuevo intento excede el historial financiero completo permitido"); }
        held.put(wallet, new ProtectedBilling(wallet, Set.copyOf(roots), Set.copyOf(operations)));
    }

    void requireHeld(Charge charge) {
        var held = context().protections.get(walletId(charge.request().binding()));
        if (held == null || !held.roots().contains(charge.rootId()) || !held.operations().contains(charge.id())) {
            throw failure("Operación ajena a la protección financiera completa");
        }
    }

    void requireRoot(UUID rootId, BillingChargeRequest request) {
        var original = jdbc.query("select * from payments_billing_root where id=?", (row, index) -> new RootBinding(
                row.getObject("wallet_id", UUID.class), new BillingBinding(row.getObject("enrollment_id", UUID.class), row.getLong("generation"),
                new SubjectId(row.getObject("provider_id", UUID.class)), new ActorId(row.getObject("original_actor", UUID.class)), new AccountId(row.getObject("payer_id", UUID.class))),
                row.getObject("consent_id", UUID.class), row.getString("policy_version"), row.getString("policy_digest"), row.getTimestamp("period_start").toInstant(),
                row.getTimestamp("period_end").toInstant(), row.getLong("gross_minor"), Currency.getInstance(row.getString("currency"))), rootId).stream().findFirst()
                .orElseThrow(() -> failure("Período financiero original no disponible"));
        var expected = new RootBinding(walletId(request.binding()), request.binding(), request.consentId(), request.policyVersion(), request.policyDigest(),
                request.periodStart(), request.periodEnd(), request.grossMinor(), request.currency());
        if (!original.equals(expected)) { throw failure("La raíz conserva todos sus términos financieros originales"); }
    }

    private BillingContext context() {
        requireTransaction();
        var existing = TransactionSynchronizationManager.getResource(this);
        if (existing != null) { return (BillingContext) existing; }
        var context = new BillingContext();
        TransactionSynchronizationManager.bindResource(this, context);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                for (var wallet : context.projected.values().stream().sorted(Comparator.comparing(value -> value.id().toString())).toList()) {
                    if (jdbc.update("update payments_promotion_wallet set outstanding_minor=?,reserved_minor=?,redeemed_minor=?,returned_minor=?,debt_minor=?,invalid=?,evidence_digest=?,version=version+1 where id=? and version=?",
                            wallet.outstanding(), wallet.reserved(), wallet.redeemed(), wallet.returned(), wallet.debt(), wallet.invalid(), wallet.evidence().orElse(null), wallet.id(), wallet.version()) != 1) {
                        throw failure("El crédito protegido cambió");
                    }
                }
            }
            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(BillingStore.this); }
        });
        return context;
    }

    Wallet wallet(UUID id, boolean lock) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            var projected = context().projected.get(id);
            if (projected != null) { return projected; }
        }
        return jdbc.query("select * from payments_promotion_wallet where id=?" + (lock ? " for update" : ""), (row, index) ->
                new Wallet(id, row.getObject("provider_id", UUID.class), row.getObject("payer_id", UUID.class), row.getLong("outstanding_minor"),
                        row.getLong("reserved_minor"), row.getLong("redeemed_minor"), row.getLong("returned_minor"), row.getLong("debt_minor"),
                        row.getBoolean("invalid"), Optional.ofNullable(row.getString("evidence_digest")), row.getLong("version")), id)
                .stream().findFirst().orElseThrow(() -> failure("Crédito de la inscripción original no disponible"));
    }

    void saveWallet(Wallet wallet) {
        var context = context();
        if (!context.protections.containsKey(wallet.id())) { throw failure("Crédito sin protección financiera"); }
        var prior = context.projected.get(wallet.id());
        if (prior != null && prior.version() != wallet.version()) { throw failure("Versión de crédito incompatible con esta transacción"); }
        context.projected.put(wallet.id(), wallet);
    }

    long effectiveVersion(Wallet wallet) {
        return Math.addExact(wallet.version(), context().projected.containsKey(wallet.id()) ? 1 : 0);
    }

    Charge charge(UUID id) {
        return jdbc.query("select o.*,r.wallet_id,r.enrollment_id,r.generation enrollment_generation,r.provider_id,r.original_actor,r.payer_id,r.consent_id,r.policy_version,r.policy_digest,r.period_start,r.period_end,r.gross_minor from payments_billing_operation o join payments_billing_root r on r.id=o.root_id where o.id=?",
                (row, index) -> {
                    var binding = new BillingBinding(row.getObject("enrollment_id", UUID.class), row.getLong("enrollment_generation"),
                            new SubjectId(row.getObject("provider_id", UUID.class)), new ActorId(row.getObject("original_actor", UUID.class)), new AccountId(row.getObject("payer_id", UUID.class)));
                    var request = new BillingChargeRequest(row.getObject("command_id", UUID.class), binding, row.getObject("consent_id", UUID.class),
                            row.getString("policy_version"), row.getString("policy_digest"), row.getTimestamp("period_start").toInstant(), row.getTimestamp("period_end").toInstant(),
                            row.getLong("gross_minor"), Currency.getInstance("ARS"), row.getLong("permitted_promotion_minor"), row.getLong("fee_minor"), Optional.ofNullable(row.getObject("predecessor", UUID.class)));
                    if (row.getLong("promotion_minor") != request.permittedPromotionMinor() || row.getLong("external_minor") != Math.subtractExact(request.grossMinor(), row.getLong("promotion_minor"))) { throw failure("Vínculo de importe financiero incompatible"); }
                    return new Charge(id, row.getObject("root_id", UUID.class), row.getObject("stable_key", UUID.class), request,
                            row.getLong("promotion_minor"), row.getString("state"), row.getLong("collected_minor"), row.getLong("returned_minor"),
                            row.getBoolean("promotion_redeemed"), row.getBoolean("reservations_restored"), row.getBoolean("fee_spent"), row.getBoolean("success_applied"),
                            row.getLong("generation"), Optional.ofNullable(row.getObject("claim_token", UUID.class)), instant(row.getTimestamp("lease_until")),
                            instant(row.getTimestamp("dispatched_at")), row.getLong("version"));
                }, id).stream().findFirst().orElseThrow(() -> failure("Intento de facturación no disponible"));
    }

    List<Charge> charges(BillingBinding binding) {
        return jdbc.query("select o.id from payments_billing_operation o join payments_billing_root r on r.id=o.root_id where r.enrollment_id=? and r.generation=? order by r.period_start,o.created_at,o.id",
                (row, index) -> row.getObject(1, UUID.class), binding.enrollmentId(), binding.generation()).stream().map(this::charge).toList();
    }

    void budget(String kind, long availableDelta, long reservedDelta, long spentDelta) {
        if (availableDelta == 0 && reservedDelta == 0 && spentDelta == 0) { return; }
        var current = jdbc.queryForMap("select available_minor,reserved_minor,spent_minor from payments_subscription_budget where kind=?", kind);
        if (Math.addExact(((Number) current.get("available_minor")).longValue(), availableDelta) < 0
                || Math.addExact(((Number) current.get("reserved_minor")).longValue(), reservedDelta) < 0
                || Math.addExact(((Number) current.get("spent_minor")).longValue(), spentDelta) < 0) { throw failure("Respaldo independiente insuficiente"); }
        if (jdbc.update("update payments_subscription_budget set available_minor=available_minor+?,reserved_minor=reserved_minor+?,spent_minor=spent_minor+?,version=version+1 where kind=? and available_minor+?>=0 and reserved_minor+?>=0 and spent_minor+?>=0",
                availableDelta, reservedDelta, spentDelta, kind, availableDelta, reservedDelta, spentDelta) != 1) { throw failure("Respaldo independiente insuficiente"); }
    }

    void ledger(UUID batch, UUID wallet, UUID operation, Entry... entries) {
        long sum = 0;
        for (var entry : entries) { sum = Math.addExact(sum, entry.minor()); }
        if (sum != 0) { throw failure("Asiento de suscripción desbalanceado"); }
        int line = 0;
        for (var entry : entries) {
            if (entry.minor() != 0) { jdbc.update("insert into payments_subscription_ledger values(?,?,?,?,? ,?,'ARS',?)", batch, line++, wallet, operation, entry.account(), entry.minor(), now()); }
        }
    }

    Timestamp now() { return Timestamp.from(clock.instant()); }
    static UUID walletId(BillingBinding binding) { return id("SL1B-LOYALTY-2:" + binding.provider() + ":" + binding.originalPayer()); }
    static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    static String digest(String value) { return ContentDigest.bytes(value.getBytes(StandardCharsets.UTF_8)); }
    static IllegalStateException failure(String message) { return new IllegalStateException(message); }
    static void requireTransaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw failure("La operación requiere una transacción financiera protegida"); } }
    private static Optional<Instant> instant(Timestamp timestamp) { return Optional.ofNullable(timestamp).map(Timestamp::toInstant); }

    record ProtectedBilling(UUID wallet, Set<UUID> roots, Set<UUID> operations) {}
    record RootBinding(UUID wallet, BillingBinding binding, UUID consent, String policyVersion, String policyDigest, Instant start, Instant end, long gross, Currency currency) {}
    private static final class BillingContext {
        final Map<UUID, ProtectedBilling> protections = new HashMap<>();
        final Map<UUID, Wallet> projected = new HashMap<>();
    }
    record Entry(String account, long minor) {}
    record Wallet(UUID id, UUID provider, UUID payer, long outstanding, long reserved, long redeemed, long returned, long debt,
            boolean invalid, Optional<String> evidence, long version) {
        long available() { return invalid || debt > 0 ? 0 : Math.subtractExact(outstanding, reserved); }
        Wallet project(long outstanding, long reserved, long redeemed, long returned, long debt, boolean invalid, Optional<String> evidence) {
            return new Wallet(id, provider, payer, outstanding, reserved, redeemed, returned, debt, invalid, evidence, version);
        }
    }
    record Charge(UUID id, UUID rootId, UUID stableKey, BillingChargeRequest request, long promotion, String state,
            long collected, long returned, boolean redeemed, boolean restored, boolean feeSpent, boolean success,
            long generation, Optional<UUID> token, Optional<Instant> leaseUntil, Optional<Instant> dispatchedAt, long version) {
        PreparedBillingCharge prepared() { return new PreparedBillingCharge(rootId, id, stableKey, request, promotion, generation, token, leaseUntil); }
        GatewayRequest gatewayRequest() { return new GatewayRequest(id, rootId, stableKey, "SUBSCRIPTION", Math.subtractExact(request.grossMinor(), promotion),
                "subscription:" + request.binding().originalPayer().value(), Currency.getInstance("ARS"), digest(rootId + ":" + id + ":" + request + ":" + promotion)); }
        boolean pending() { return Set.of("QUEUED", "CLAIMED", "DISPATCHING", "UNKNOWN", "RECOVERY").contains(state); }
    }
}
