package ar.changeo.subscriptions;

import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.ContentDigest;
import ar.changeo.payments.api.*;
import ar.changeo.subscriptions.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
public class SubscriptionService implements BillingDispatchAccess {
    private final SubscriptionStore store;
    private final ProviderEntitlementService entitlements;
    private final ParticipantIdentityAccess identity;
    private final IdentityAccess permissions;
    private final SubscriptionBillingAccess billing;
    private final PromotionBudgetAccess promotions;
    private final SubscriptionLoyaltyService loyalty;
    private final SecurityProof proof;
    private final TransactionTemplate transaction;
    private Optional<UUID> renewalAfter = Optional.empty();

    SubscriptionService(SubscriptionStore store, ProviderEntitlementService entitlements, ParticipantIdentityAccess identity, IdentityAccess permissions,
            SubscriptionBillingAccess billing, PromotionBudgetAccess promotions, SubscriptionLoyaltyService loyalty, SecurityProof proof, PlatformTransactionManager transactions) {
        this.store = store; this.entitlements = entitlements; this.identity = identity; this.permissions = permissions;
        this.billing = billing; this.promotions = promotions; this.loyalty = loyalty; this.proof = proof; this.transaction = new TransactionTemplate(transactions);
    }

    public void enroll(EnrollmentCommand command) {
        transaction.executeWithoutResult(status -> {
            var actor = actor(); var discovery = entitlements.discover(Set.of(command.provider()));
            var evidence = command.useCredit() ? Optional.of(loyalty.discover(discovery, command.provider(), Optional.empty())) : Optional.<SubscriptionLoyaltyService.Observation>empty();
            var accounts = evidence.map(value -> loyalty.requiredAccounts(discovery, value, new AccountId(actor.value()))).orElseGet(() -> accounts(discovery, actor));
            identity.requireForUpdate(providerPermission(actor, command.provider()), accounts);
            entitlements.protectPolicies(discovery, accounts); evidence.ifPresent(loyalty::protectCampaign);
            var protectedEnrollments = entitlements.protectEnrollments(discovery, Set.of()); evidence.ifPresent(loyalty::protectEvidence);
            var policy = store.policy(discovery.currentPolicyVersion(), false);
            if (!command.policyDigest().equals(policy.digest()) || !Set.of("FREE", "TRIAL", "PAID", "RESUME", "RETRY").contains(command.mode())
                    || (!command.mode().equals("FREE") && !command.recurringConsent())) { throw new SubscriptionFailure("Aceptá explícitamente los términos actuales y el importe neto"); }
            String digest = digest(command.toString());
            if (receipt(actor, command.command(), command.mode(), digest).isPresent()) { return; }
            var previous = protectedEnrollments.get(command.provider());
            boolean sameOwner = previous != null && previous.binding().originalActor().equals(actor);
            if (evidence.isPresent() && !sameOwner) { throw new SubscriptionFailure("El crédito no se transfiere a una nueva persona controladora"); }
            if (sameOwner && previous.version() != command.expectedVersion()) { throw new SubscriptionFailure("La inscripción cambió; revisá su estado"); }
            if (!sameOwner && command.expectedVersion() != 0) { throw new SubscriptionFailure("Inscripción ajena al actor actual"); }
            var provider = store.provider(command.provider(), true);
            if (provider.isEmpty()) {
                store.jdbc.update("insert into subscriptions_provider(provider_id) values(?)", command.provider().value());
                provider = store.provider(command.provider(), true);
            }
            var owner = provider.orElseThrow();
            if (command.mode().equals("FREE") && owner.foundingUsed()) { throw new SubscriptionFailure("El inicio gratuito se usa una sola vez por proveedor"); }
            if (command.mode().equals("TRIAL") && owner.trialUsed()) { throw new SubscriptionFailure("La prueba se usa una sola vez por proveedor"); }
            if (!sameOwner && Set.of("RETRY", "RESUME").contains(command.mode())) { throw new SubscriptionFailure("Una nueva persona controladora debe iniciar su propia inscripción y consentimiento"); }
            SubscriptionStore.Enrollment enrollment;
            if (sameOwner) { enrollment = previous; }
            else {
                UUID id = UUID.randomUUID(); long generation = Math.addExact(owner.generation(), 1);
                store.jdbc.update("insert into subscriptions_enrollment(id,provider_id,generation,original_actor,original_payer,policy_version,created_at) values(?,?,?,?,?,?,?)",
                        id, command.provider().value(), generation, actor.value(), actor.value(), policy.version(), store.now());
                store.jdbc.update("update subscriptions_provider set active_enrollment=?,generation=?,public_achievements=false,version=version+1 where provider_id=?", id, generation, command.provider().value());
                enrollment = store.enrollment(id, true);
            }
            promotions.protectForUpdate(enrollment.binding());
            var money = billing.requireStatusForUpdate(enrollment.binding());
            if (money.pending().isPresent()) { throw new SubscriptionFailure("El intento pendiente conserva su clave; conciliá antes de otro consentimiento"); }
            var validPaid = money.periods().stream().filter(period -> period.confirmedUnreversed() && period.end().isAfter(store.instant()))
                    .max(Comparator.comparing(BillingFinancialStatus.Period::end));
            if (command.mode().equals("TRIAL") && (validPaid.isPresent() || enrollment.benefitEnd().filter(value -> value.isAfter(store.instant())).isPresent())) {
                throw new SubscriptionFailure("Conservás tu período válido; la prueba comienza cuando ese período termina");
            }
            Instant start = store.instant(); Instant end;
            boolean futureConsent = false;
            Optional<UUID> consent = Optional.empty(); Optional<UUID> predecessor = Optional.empty();
            if (command.mode().equals("FREE")) {
                if (sameOwner && (!money.periods().isEmpty() || enrollment.consent().isPresent()
                        || enrollment.benefitEnd().filter(value -> value.isAfter(store.instant())).isPresent())) {
                    throw new SubscriptionFailure("El inicio gratuito no reemplaza un período válido, un consentimiento o un historial de cobros");
                }
                end = policy.foundingEnd(start);
                store.jdbc.update("update subscriptions_provider set founding_used=true,version=version+1 where provider_id=?", command.provider().value());
                store.jdbc.update("update subscriptions_enrollment set state='FREE_FOUNDING',benefit_start=?,benefit_end=?,benefit_kind='FREE_FOUNDING',benefit_policy_version=?,stop_renewal=false,version=version+1 where id=?", Timestamp.from(start), Timestamp.from(end), policy.version(), enrollment.binding().enrollmentId());
            } else {
                if (command.mode().equals("RETRY")) {
                    if (command.predecessor().isEmpty() || enrollment.consent().isEmpty()) { throw new SubscriptionFailure("Elegí el intento definitivo sin efecto"); }
                    var saved = store.consent(enrollment.consent().orElseThrow());
                    if (!saved.policyVersion().equals(policy.version()) || !saved.digest().equals(policy.digest()) || !store.clock.instant().isBefore(saved.periodEnd())) { throw new SubscriptionFailure("El período o sus términos vencieron; hacé una nueva compra actual"); }
                    start = saved.periodStart(); end = saved.periodEnd(); consent = Optional.of(saved.id()); predecessor = command.predecessor();
                } else {
                    if (money.frozen() && command.mode().equals("TRIAL")) { throw new SubscriptionFailure("La conciliación o devolución requiere una nueva compra explícita"); }
                    if (money.periods().stream().anyMatch(period -> period.state().equals("REVERSED")) && money.frozen() && command.predecessor().isEmpty()) { throw new SubscriptionFailure("Vinculá la nueva compra al intento devuelto"); }
                    futureConsent = validPaid.isPresent() && !command.mode().equals("TRIAL");
                    Instant chargeStart = futureConsent ? validPaid.orElseThrow().end() : command.mode().equals("TRIAL") ? policy.trialEnd(start) : start;
                    int anchor = futureConsent ? enrollment.anchorDay().orElseThrow() : chargeStart.atZone(policy.zone()).getDayOfMonth();
                    end = policy.nextEndpoint(chargeStart, anchor);
                    long consentVersion = store.jdbc.queryForObject("select coalesce(max(consent_version),0)+1 from subscriptions_consent where enrollment_id=?", Long.class, enrollment.binding().enrollmentId());
                    UUID consentId = UUID.randomUUID(); consent = Optional.of(consentId); predecessor = command.predecessor();
                    store.jdbc.update("insert into subscriptions_consent values(?,?,?,?,?,?,?,?,?,?,?)", consentId, enrollment.binding().enrollmentId(), actor.value(), policy.version(), policy.digest(),
                            command.mode().equals("RESUME") ? "RESUME" : command.mode(), command.useCredit(), Timestamp.from(chargeStart), Timestamp.from(end), consentVersion, store.now());
                    store.jdbc.update("update subscriptions_enrollment set consent_id=?,policy_version=?,cycle_start=?,anchor_day=?,stop_renewal=false,version=version+1 where id=?",
                            consentId, policy.version(), Timestamp.from(chargeStart), anchor, enrollment.binding().enrollmentId());
                    if (command.mode().equals("TRIAL")) {
                        store.jdbc.update("update subscriptions_provider set trial_used=true,version=version+1 where provider_id=?", command.provider().value());
                        store.jdbc.update("update subscriptions_enrollment set state='TRIAL',benefit_start=?,benefit_end=?,benefit_kind='TRIAL',benefit_policy_version=? where id=?", Timestamp.from(start), Timestamp.from(chargeStart), policy.version(), enrollment.binding().enrollmentId());
                    }
                    start = chargeStart;
                }
                if (!command.mode().equals("TRIAL") && !futureConsent) {
                    billing.prepareForUpdate(new BillingChargeRequest(command.command(), enrollment.binding(), consent.orElseThrow(), policy.version(), policy.digest(), start, end,
                            policy.grossMinor(), Currency.getInstance("ARS"), command.useCredit() ? 100 : 0, policy.feeMinor(), predecessor));
                }
            }
            if (evidence.isPresent() && loyalty.apply(evidence.orElseThrow()).jobs().size() != 2) { throw new SubscriptionFailure("La evidencia actual no habilita el crédito; revisá el progreso antes de consentir"); }
            record(actor, command.command(), command.mode(), digest, enrollment.binding().enrollmentId());
            store.event(store.enrollment(enrollment.binding().enrollmentId(), false), "CONSENT_" + command.mode());
        });
    }

    public View view(SubjectId provider, Optional<UUID> enrollmentId) {
        return transaction.execute(status -> {
            var actor = actor(); var discovery = entitlements.discover(Set.of(provider));
            var advisory = enrollmentId.map(id -> store.enrollment(id, false));
            var accounts = new HashSet<>(accounts(discovery, actor));
            advisory.ifPresent(enrollment -> { accounts.add(new AccountId(enrollment.binding().provider().value())); accounts.add(enrollment.binding().originalPayer()); });
            identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.value()), Capability.ACCESS_SUPPORT), Set.copyOf(accounts));
            entitlements.protectPolicies(discovery, Set.copyOf(accounts), advisory.map(this::retainedPolicies).orElse(Set.of()));
            var current = entitlements.protectEnrollments(discovery, advisory.map(value -> Set.of(value.binding().enrollmentId())).orElse(Set.of())).get(provider);
            var enrollment = advisory.map(value -> store.enrollment(value.binding().enrollmentId(), false)).orElse(current);
            if (enrollment != null && (!enrollment.binding().provider().equals(provider) || !enrollment.binding().originalActor().equals(actor))) {
                if (enrollmentId.isPresent()) { throw new SubscriptionFailure("Solo el pagador original consulta esta inscripción"); }
                enrollment = null;
            }
            var policy = store.policy(discovery.currentPolicyVersion(), false);
            boolean canEnroll = permissions.decide(providerPermission(actor, provider)).allowed();
            var providerState = store.provider(provider, false);
            boolean foundingAvailable = providerState.filter(SubscriptionStore.Provider::foundingUsed).isEmpty();
            boolean trialAvailable = providerState.filter(SubscriptionStore.Provider::trialUsed).isEmpty();
            if (enrollment == null) { return new View(provider, Optional.empty(), 0, "SIN_INSCRIPCIÓN", false, Optional.empty(), Optional.empty(), policy, canEnroll, List.of(), 0, 0, false,
                    foundingAvailable, trialAvailable, false, Optional.empty(), Optional.empty(), false, Optional.empty()); }
            canEnroll = canEnroll && current != null && current.binding().equals(enrollment.binding());
            var financial = billing.requireStatusForUpdate(enrollment.binding());
            var effective = entitlements.effective(enrollment, financial);
            String displayed = displayed(enrollment, financial, effective);
            if (Set.of("PAID", "PAST_DUE").contains(displayed) && !enrollment.state().filter(displayed::equals).isPresent()) {
                store.jdbc.update("update subscriptions_enrollment set state=?,version=version+1 where id=?", displayed, enrollment.binding().enrollmentId());
                store.event(enrollment, "PROJECTED_" + displayed); enrollment = store.enrollment(enrollment.binding().enrollmentId(), false);
            }
            var consent = enrollment.consent().map(store::consent);
            boolean currentConsent = consent.filter(value -> value.policyVersion().equals(policy.version()) && value.digest().equals(policy.digest())).isPresent();
            Optional<Instant> next = financial.pending().map(value -> value.request().periodStart());
            if (next.isEmpty() && currentConsent && !enrollment.stopRenewal() && !financial.frozen()) {
                next = financial.periods().stream().filter(BillingFinancialStatus.Period::confirmedUnreversed).map(BillingFinancialStatus.Period::end).max(Comparator.naturalOrder()).or(enrollment::cycleStart);
                int anchor = enrollment.anchorDay().orElseThrow();
                next = next.filter(value -> store.instant().isBefore(policy.nextEndpoint(value, anchor)));
            }
            var retry = financial.periods().stream().filter(period -> period.state().equals("FAILED_NO_EFFECT") && period.end().isAfter(store.instant())
                    && currentConsent && consent.filter(value -> value.id().equals(period.consentId())).isPresent()
                    && financial.periods().stream().noneMatch(nextPeriod -> nextPeriod.predecessor().filter(period.operationId()::equals).isPresent()))
                    .max(Comparator.comparing(BillingFinancialStatus.Period::start)).map(BillingFinancialStatus.Period::operationId);
            var returned = financial.periods().stream().filter(period -> period.state().equals("REVERSED")
                    && financial.periods().stream().noneMatch(nextPeriod -> nextPeriod.predecessor().filter(period.operationId()::equals).isPresent()))
                    .max(Comparator.comparing(BillingFinancialStatus.Period::start)).map(BillingFinancialStatus.Period::operationId);
            boolean validPaid = financial.periods().stream().anyMatch(period -> period.confirmedUnreversed() && period.end().isAfter(store.instant()));
            trialAvailable = trialAvailable && !validPaid && enrollment.benefitEnd().filter(value -> value.isAfter(store.instant())).isEmpty();
            foundingAvailable = foundingAvailable && financial.periods().isEmpty() && enrollment.consent().isEmpty()
                    && enrollment.benefitEnd().filter(value -> value.isAfter(store.instant())).isEmpty();
            return new View(provider, Optional.of(enrollment.binding().enrollmentId()), enrollment.version(), displayed, effective.allowed(), effective.validUntil(), next,
                    policy, canEnroll, financial.periods(), financial.availablePromotionMinor(), financial.promotionalDebtMinor(), financial.frozen(), foundingAvailable, trialAvailable,
                    currentConsent && !enrollment.stopRenewal(), retry, returned, validPaid, enrollment.benefitEnd());
        });
    }

    public void cancel(UUID command, UUID enrollmentId, long expectedVersion) {
        transaction.executeWithoutResult(status -> {
            var actor = actor(); var advisory = store.enrollment(enrollmentId, false);
            var discovery = entitlements.discover(Set.of(advisory.binding().provider()));
            var accounts = new HashSet<>(accounts(discovery, actor)); accounts.add(advisory.binding().originalPayer());
            identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.value()), Capability.ACCESS_SUPPORT), Set.copyOf(accounts));
            entitlements.protectPolicies(discovery, Set.copyOf(accounts), retainedPolicies(advisory)); entitlements.protectEnrollments(discovery, Set.of(enrollmentId));
            var enrollment = store.enrollment(enrollmentId, false);
            if (!enrollment.binding().originalActor().equals(actor)) { throw new SubscriptionFailure("Solo el pagador original cancela esta inscripción"); }
            String digest = digest(enrollmentId + ":" + expectedVersion);
            if (receipt(actor, command, "CANCEL", digest).isPresent()) { return; }
            if (enrollment.version() != expectedVersion) { throw new SubscriptionFailure("La inscripción cambió; revisá su estado"); }
            billing.cancelForUpdate(enrollment.binding(), command);
            store.jdbc.update("update subscriptions_enrollment set state='CANCELED',stop_renewal=true,version=version+1 where id=?", enrollmentId);
            record(actor, command, "CANCEL", digest, enrollmentId); store.event(enrollment, "CANCELED");
        });
    }

    @Override public void authorize(PreparedBillingCharge prepared) {
        standalone();
        transaction.executeWithoutResult(status -> {
            var binding = prepared.request().binding(); var discovery = entitlements.discover(Set.of(binding.provider()));
            var evidence = prepared.reservedPromotionMinor() > 0 ? Optional.of(loyalty.discover(discovery, binding.provider(), Optional.empty())) : Optional.<SubscriptionLoyaltyService.Observation>empty();
            var accounts = evidence.map(value -> loyalty.requiredAccounts(discovery, value, binding.originalPayer())).orElseGet(() -> accounts(discovery, binding.originalActor()));
            identity.requireForUpdate(providerPermission(binding.originalActor(), binding.provider()), accounts);
            entitlements.protectPolicies(discovery, accounts); evidence.ifPresent(loyalty::protectCampaign);
            var enrollment = entitlements.protectEnrollments(discovery, Set.of()).get(binding.provider()); evidence.ifPresent(loyalty::protectEvidence);
            var consent = store.consent(prepared.request().consentId());
            if (enrollment == null || !enrollment.binding().equals(binding) || enrollment.stopRenewal() || !enrollment.consent().filter(consent.id()::equals).isPresent()
                    || !consent.actor().equals(binding.originalActor()) || !consent.enrollment().equals(binding.enrollmentId())
                    || !consent.policyVersion().equals(discovery.currentPolicyVersion()) || !consent.digest().equals(discovery.currentPolicyDigest())
                    || !prepared.request().policyVersion().equals(consent.policyVersion()) || !prepared.request().policyDigest().equals(consent.digest())) {
                throw new SubscriptionFailure("El consentimiento actual ya no autoriza este cobro");
            }
            var financial = billing.requireStatusForUpdate(binding); entitlements.requireFinancialTargets(discovery);
            var policy = store.policy(consent.policyVersion(), false);
            var request = prepared.request();
            boolean exactPeriod = !request.periodStart().isBefore(consent.periodStart())
                    && request.periodEnd().equals(policy.nextEndpoint(request.periodStart(), enrollment.anchorDay().orElseThrow()))
                    && (request.periodStart().equals(consent.periodStart()) || financial.periods().stream().anyMatch(period -> period.confirmedUnreversed() && period.end().equals(request.periodStart())));
            if (!exactPeriod || request.grossMinor() != policy.grossMinor() || request.feeMinor() != policy.feeMinor()
                    || prepared.reservedPromotionMinor() > 0 && !consent.useCredit()) { throw new SubscriptionFailure("El importe o período no corresponde al consentimiento exacto"); }
            String evidenceDigest = digest("NO_PROMOTION");
            if (evidence.isPresent()) {
                var result = loyalty.apply(evidence.orElseThrow()); evidenceDigest = result.digest();
                if (result.jobs().size() != 2) { billing.cancelForUpdate(binding, request.command()); return; }
            }
            billing.authorizePreparedForUpdate(prepared, new QualifiedPromotionUse(prepared.reservedPromotionMinor(), evidenceDigest));
        });
    }

    public synchronized boolean renewOne() {
        standalone();
        var candidates = store.jdbc.query("select id from subscriptions_enrollment where not stop_renewal and consent_id is not null and id::text>? order by id::text limit 20",
                (row, index) -> row.getObject(1, UUID.class), renewalAfter.map(UUID::toString).orElse(""));
        if (candidates.isEmpty()) { renewalAfter = Optional.empty(); return false; }
        for (UUID candidate : candidates) {
            renewalAfter = Optional.of(candidate);
            boolean prepared;
            try { prepared = transaction.execute(status -> prepareRenewal(candidate)); }
            catch (ar.changeo.identity.IdentityFailure | IllegalStateException unavailable) { continue; }
            if (prepared) { return true; }
        }
        return false;
    }

    private boolean prepareRenewal(UUID id) {
        var advisory = store.enrollment(id, false); var binding = advisory.binding();
        var discovery = entitlements.discover(Set.of(binding.provider()));
        var savedConsent = advisory.consent().map(store::consent);
        var evidence = savedConsent.filter(SubscriptionStore.Consent::useCredit).map(value -> loyalty.discover(discovery, binding.provider(), Optional.empty()));
        var accounts = evidence.map(value -> loyalty.requiredAccounts(discovery, value, binding.originalPayer())).orElseGet(() -> accounts(discovery, binding.originalActor()));
        identity.requireForUpdate(providerPermission(binding.originalActor(), binding.provider()), accounts);
        entitlements.protectPolicies(discovery, accounts); evidence.ifPresent(loyalty::protectCampaign);
        var enrollment = entitlements.protectEnrollments(discovery, Set.of()).get(binding.provider()); evidence.ifPresent(loyalty::protectEvidence);
        if (enrollment == null || !enrollment.binding().equals(binding) || enrollment.stopRenewal() || enrollment.consent().isEmpty()) { return false; }
        var consent = store.consent(enrollment.consent().orElseThrow());
        if (!consent.policyVersion().equals(discovery.currentPolicyVersion()) || !consent.digest().equals(discovery.currentPolicyDigest())) { return false; }
        var financial = billing.requireStatusForUpdate(binding); entitlements.requireFinancialTargets(discovery);
        if (financial.pending().isPresent() || financial.frozen()) { return false; }
        var policy = store.policy(consent.policyVersion(), false);
        var prior = financial.periods().stream().filter(BillingFinancialStatus.Period::confirmedUnreversed).max(Comparator.comparing(BillingFinancialStatus.Period::end));
        if (prior.isEmpty() && !consent.mode().equals("TRIAL")) { return false; }
        Instant start = prior.map(BillingFinancialStatus.Period::end).orElse(consent.periodStart());
        Instant end = policy.nextEndpoint(start, enrollment.anchorDay().orElseThrow());
        if (store.clock.instant().isBefore(start) || !store.clock.instant().isBefore(end)) { return false; }
        if (financial.periods().stream().anyMatch(period -> period.start().equals(start))) { return false; }
        UUID command = UUID.nameUUIDFromBytes(("renew:" + id + ":" + start + ":" + consent.id()).getBytes(StandardCharsets.UTF_8));
        long credit = consent.useCredit() && financial.availablePromotionMinor() >= 100 ? 100 : 0;
        billing.prepareForUpdate(new BillingChargeRequest(command, binding, consent.id(), policy.version(), policy.digest(), start, end, policy.grossMinor(), Currency.getInstance("ARS"), credit, policy.feeMinor(), Optional.empty()));
        if (evidence.isPresent() && credit > 0 && loyalty.apply(evidence.orElseThrow()).jobs().size() != 2) { billing.cancelForUpdate(binding, command); return false; }
        store.event(enrollment, "RENEWAL_PREPARED"); return true;
    }

    private Set<AccountId> accounts(EntitlementDiscovery discovery, ActorId actor) {
        Set<AccountId> accounts = new HashSet<>(discovery.requiredAccountIds()); accounts.add(new AccountId(actor.value()));
        if (accounts.size() > 64) { throw new SubscriptionFailure("Demasiados participantes para una protección completa"); }
        return Set.copyOf(accounts);
    }
    private Set<String> retainedPolicies(SubscriptionStore.Enrollment enrollment) {
        Set<String> versions = new HashSet<>(billing.discoverTargets(enrollment.binding()).policyVersions());
        versions.add(enrollment.policyVersion()); enrollment.benefitPolicyVersion().ifPresent(versions::add);
        enrollment.consent().map(store::consent).ifPresent(consent -> versions.add(consent.policyVersion()));
        return Set.copyOf(versions);
    }
    private String displayed(SubscriptionStore.Enrollment enrollment, BillingFinancialStatus financial, ProviderEntitlementService.EffectiveEntitlement effective) {
        if (enrollment.stopRenewal()) { return "CANCELED"; }
        if (effective.allowed() && effective.reason().equals("PAID_THROUGH")) { return "PAID"; }
        if (effective.allowed() && enrollment.benefitKind().filter(effective.reason()::equals).isPresent()) { return effective.reason(); }
        if (financial.periods().stream().anyMatch(BillingFinancialStatus.Period::confirmedUnreversed) || enrollment.state().filter(value -> Set.of("PAID", "PAST_DUE").contains(value)).isPresent()) { return "PAST_DUE"; }
        if (financial.pending().isPresent()) { return "PAGO_PENDIENTE"; }
        if (!financial.periods().isEmpty()) { return financial.periods().getLast().state(); }
        if (enrollment.benefitEnd().filter(end -> !end.isAfter(store.instant())).isPresent()) { return "EXPIRED"; }
        return enrollment.state().orElse("SIN_PERÍODO_VIGENTE");
    }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new SubscriptionFailure("Autenticá tu cuenta para consultar facturación"))); }
    private static PermissionRequest providerPermission(ActorId actor, SubjectId provider) { return new PermissionRequest(Optional.of(actor), Optional.of(provider), Capability.NEW_PROVIDER_PARTICIPATION, Optional.empty()); }
    private Optional<UUID> receipt(ActorId actor, UUID command, String operation, String digest) {
        var rows = store.jdbc.queryForList("select operation,digest,enrollment_id from subscriptions_receipt where actor_id=? and command_id=?", actor.value(), command);
        if (rows.isEmpty()) { return Optional.empty(); }
        var row = rows.getFirst(); if (!operation.equals(row.get("operation")) || !digest.equals(row.get("digest"))) { throw new SubscriptionFailure("Comando ligado a otra inscripción"); }
        return Optional.of((UUID) row.get("enrollment_id"));
    }
    private void record(ActorId actor, UUID command, String operation, String digest, UUID enrollment) { store.jdbc.update("insert into subscriptions_receipt values(?,?,?,?,?,?)", actor.value(), command, operation, digest, enrollment, store.now()); }
    private static String digest(String value) { return ContentDigest.bytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static void standalone() { if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new SubscriptionFailure("El proceso de suscripción requiere una transacción independiente"); } }

    public record EnrollmentCommand(UUID command, SubjectId provider, long expectedVersion, String mode, String policyDigest,
            boolean recurringConsent, boolean useCredit, Optional<UUID> predecessor) {
        public EnrollmentCommand { Objects.requireNonNull(command); Objects.requireNonNull(provider); Objects.requireNonNull(mode); Objects.requireNonNull(policyDigest); Objects.requireNonNull(predecessor); }
    }
    public record View(SubjectId provider, Optional<UUID> enrollment, long version, String state, boolean entitlement, Optional<Instant> validUntil,
            Optional<Instant> nextDate, SubscriptionPolicy policy, boolean canEnroll, List<BillingFinancialStatus.Period> history,
            long creditMinor, long debtMinor, boolean frozen, boolean foundingAvailable, boolean trialAvailable, boolean recurringAuthorized,
            Optional<UUID> retry, Optional<UUID> returnedPurchase, boolean validPaid, Optional<Instant> benefitEnd) {
        public View { history = List.copyOf(history); }
        public String ars(long minor) { return SubscriptionPolicy.ars(minor); }
        public Optional<BillingFinancialStatus.Period> retryPeriod() { return retry.flatMap(id -> history.stream().filter(period -> period.operationId().equals(id)).findFirst()); }
        public String date(Instant instant) { return java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", Locale.forLanguageTag("es-AR")).withZone(policy.zone()).format(instant); }
        public String stateLabel() { return historyState(state); }
        public String historyState(String state) {
            return switch (state) {
                case "FREE_FOUNDING" -> "Inicio gratuito";
                case "TRIAL" -> "Prueba consentida";
                case "EXPIRED" -> "Período gratuito o de prueba vencido";
                case "PAID" -> "Período pagado";
                case "PAST_DUE" -> "Renovación pendiente o período vencido";
                case "CANCELED" -> "Renovación cancelada";
                case "PAGO_PENDIENTE", "QUEUED", "CLAIMED", "DISPATCHING" -> "Procesamiento pendiente";
                case "UNKNOWN", "RECOVERY" -> "Resultado en conciliación";
                case "FAILED_NO_EFFECT" -> "Intento definitivo sin efecto";
                case "REVERSED" -> "Cobro devuelto";
                case "CANCELLED" -> "Intento cancelado antes de despacho";
                default -> entitlement ? "Período válido" : "Sin período vigente";
            };
        }
    }
}
