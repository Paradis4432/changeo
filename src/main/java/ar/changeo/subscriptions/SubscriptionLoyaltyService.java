package ar.changeo.subscriptions;

import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
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
public class SubscriptionLoyaltyService implements LoyaltyPublicAccess {
    static final String CAMPAIGN = "SL1B-LOYALTY-2";
    private final SubscriptionStore store;
    private final ProviderEntitlementService entitlements;
    private final ParticipantIdentityAccess identity;
    private final IdentityAccess permissions;
    private final JobLoyaltyAccess jobs;
    private final PromotionBudgetAccess promotions;
    private final SubscriptionBillingAccess billing;
    private final SecurityProof proof;
    private final TransactionTemplate transaction;

    SubscriptionLoyaltyService(SubscriptionStore store, ProviderEntitlementService entitlements, ParticipantIdentityAccess identity, IdentityAccess permissions,
            JobLoyaltyAccess jobs, PromotionBudgetAccess promotions, SubscriptionBillingAccess billing, SecurityProof proof, PlatformTransactionManager transactions) {
        this.store = store; this.entitlements = entitlements; this.identity = identity; this.permissions = permissions; this.jobs = jobs;
        this.promotions = promotions; this.billing = billing; this.proof = proof; this.transaction = new TransactionTemplate(transactions);
    }

    Observation discover(EntitlementDiscovery entitlement, SubjectId provider, Optional<UUID> candidate) {
        var binding = entitlement.providers().get(provider).binding().orElseThrow(() -> new SubscriptionFailure("Iniciá primero tu inscripción de proveedor"));
        return discover(binding, candidate);
    }

    private Observation discover(BillingBinding binding, Optional<UUID> candidate) {
        var provider = binding.provider();
        var selected = selected(binding, false);
        Set<UUID> jobIds = new HashSet<>(selected.map(Selection::jobs).orElse(Set.of())); candidate.ifPresent(jobIds::add);
        var evidence = jobs.discoverEvidence(provider, Set.copyOf(jobIds));
        Set<Pair> keys = new HashSet<>();
        for (var job : evidence.jobs().values()) {
            UUID payer = job.agreement().legalPayer().value();
            if (!payer.equals(binding.originalPayer().value())) {
                keys.add(new Pair(binding.originalPayer().value(), payer)); keys.add(new Pair(payer, binding.originalPayer().value()));
            }
        }
        if (keys.size() > 6) { throw new SubscriptionFailure("Demasiadas parejas para una observación completa"); }
        Map<Pair, Optional<PairClaim>> pairs = new HashMap<>();
        keys.forEach(pair -> pairs.put(pair, pair(pair, false)));
        var appeals = store.jdbc.query("select id,version,state from subscriptions_appeal where enrollment_id=? and state<>'RESOLVED' order by id::text limit 21", (row, index) -> new AppealTarget(row.getObject(1, UUID.class), row.getLong(2), row.getString(3)), binding.enrollmentId());
        if (appeals.size() > 20) { throw new SubscriptionFailure("La revisión completa excede el límite técnico; pedí asistencia"); }
        return new Observation(binding, evidence, selected, pairs, campaignVersion(false), appeals);
    }

    void protectCampaign(Observation observation) {
        if (campaignVersion(true) != observation.campaignVersion()) { throw new SubscriptionFailure("La campaña cambió; volvé a observarla"); }
    }

    void protectEvidence(Observation observation) {
        if (!selected(observation.binding(), true).equals(observation.selection())) { throw new SubscriptionFailure("La selección de evidencia cambió; revisá el progreso"); }
        for (var key : observation.pairs().keySet().stream().sorted(Pair.ORDER).toList()) {
            if (!pair(key, true).equals(observation.pairs().get(key))) { throw new SubscriptionFailure("La regla de reciprocidad cambió; revisá el progreso"); }
        }
        for (var appeal : observation.appeals()) {
            var current = store.jdbc.query("select id,version,state from subscriptions_appeal where id=? for update", (row, index) -> new AppealTarget(row.getObject(1, UUID.class), row.getLong(2), row.getString(3)), appeal.id());
            if (!current.equals(List.of(appeal))) { throw new SubscriptionFailure("La revisión cambió; volvé a consultarla"); }
        }
    }

    Set<AccountId> requiredAccounts(EntitlementDiscovery entitlement, Observation observation, AccountId actor) {
        Set<AccountId> accounts = new HashSet<>(entitlement.requiredAccountIds()); accounts.add(actor); accounts.addAll(observation.evidence().requiredAccountIds());
        accounts.add(observation.binding().originalPayer()); accounts.add(new AccountId(observation.binding().originalActor().value()));
        accounts.add(new AccountId(observation.binding().provider().value()));
        if (accounts.size() > 64) { throw new SubscriptionFailure("La evidencia requiere más participantes que el límite de protección completo"); }
        return Set.copyOf(accounts);
    }

    Evaluation evaluate(Observation observation) {
        var facts = jobs.requireQualificationForUpdate(observation.evidence());
        List<JobQualification> valid = new ArrayList<>(); String reason = "QUALIFICATION_PENDING";
        for (var job : facts.values().stream().sorted(Comparator.comparing(value -> value.jobId().toString())).toList()) {
            if (!job.qualified() || !job.originalProviderActor().equals(observation.binding().originalActor())) { continue; }
            Set<AccountId> providerAccounts = new HashSet<>(job.providerAccounts()); providerAccounts.add(observation.binding().originalPayer());
            if (job.originalCustomerPayer().equals(observation.binding().originalPayer()) || !Collections.disjoint(providerAccounts, job.customerAccounts())) { reason = "KNOWN_SELF_CONTROL"; continue; }
            var reverse = observation.pairs().get(new Pair(job.originalCustomerPayer().value(), observation.binding().originalPayer().value()));
            boolean previouslyConfirmed = confirmed(observation.binding(), job);
            if (!previouslyConfirmed && reverse.filter(claim -> !claim.completedAt().isBefore(store.clock.instant().minus(Duration.ofDays(30)))).isPresent()) {
                reason = "RECIPROCAL_HOLD"; continue;
            }
            if (valid.stream().anyMatch(previous -> previous.originalCustomerPayer().equals(job.originalCustomerPayer())
                    || previous.completedEvent().equals(job.completedEvent()))) { reason = "DUPLICATE_PAYER"; continue; }
            valid.add(job);
        }
        List<JobQualification> chosen = valid.stream().limit(2).toList();
        String digest = digest(observation.binding().originalPayer() + ":" + CAMPAIGN + ":" + facts.values().stream().sorted(Comparator.comparing(value -> value.jobId().toString()))
                .map(value -> value.jobId() + ":" + value.evidenceDigest() + ":" + chosen.contains(value)).collect(java.util.stream.Collectors.joining("|")) + ":" + reason);
        return new Evaluation(chosen, chosen.size() == 2 ? "QUALIFIED" : reason, digest);
    }

    Evaluation apply(Observation observation) {
        var evaluation = evaluate(observation);
        for (var job : evaluation.jobs()) { confirm(observation.binding(), job); }
        var selectedJobs = evaluation.jobs().stream().map(JobQualification::jobId).collect(java.util.stream.Collectors.toSet());
        for (UUID id : observation.evidence().jobs().keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            if (selectedJobs.size() < 2) { selectedJobs.add(id); }
        }
        String reason = evaluation.reason();
        if (evaluation.jobs().size() == 2) {
            var awarded = promotions.awardForUpdate(observation.binding(), new PromotionClaim(CAMPAIGN, observation.binding().provider(), observation.binding().originalPayer(),
                    evaluation.jobs().stream().map(JobQualification::jobId).toList(), evaluation.digest(), CAMPAIGN));
            if (awarded == PromotionBudgetAccess.AwardResult.CAP_USED) { reason = "CAP_USED"; }
            if (awarded == PromotionBudgetAccess.AwardResult.BACKING_PENDING) { reason = "BACKING_PENDING"; }
        } else if (billing.requireStatusForUpdate(observation.binding()).pending().filter(value -> value.reservedPromotionMinor() > 0).isPresent()) {
            billing.cancelForUpdate(observation.binding(), UUID.nameUUIDFromBytes(("invalid-credit:" + evaluation.digest()).getBytes(StandardCharsets.UTF_8)));
        }
        promotions.adjustQualificationForUpdate(observation.binding(), evaluation.digest(), evaluation.jobs().size() == 2);
        if (evaluation.jobs().size() == 2 && promotions.allocationStatusForUpdate(observation.binding()) == PromotionBudgetAccess.AllocationStatus.BACKING_PENDING) { reason = "BACKING_PENDING"; }
        saveEvaluation(observation, evaluation, selectedJobs, reason);
        return new Evaluation(evaluation.jobs(), reason, evaluation.digest());
    }

    private Evaluation applyRetained(Observation observation) {
        var evaluation = evaluate(observation);
        var history = promotions.requireHistoryForUpdate(observation.binding(), Optional.empty());
        if (history.award().isPresent()) {
            if (evaluation.jobs().size() != 2 && billing.requireStatusForUpdate(observation.binding()).pending().filter(value -> value.reservedPromotionMinor() > 0).isPresent()) {
                billing.cancelForUpdate(observation.binding(), UUID.nameUUIDFromBytes(("invalid-credit:" + evaluation.digest()).getBytes(StandardCharsets.UTF_8)));
            }
            promotions.adjustQualificationForUpdate(observation.binding(), evaluation.digest(), evaluation.jobs().size() == 2);
        }
        if (observation.selection().isPresent()) { saveEvaluation(observation, evaluation, observation.selection().orElseThrow().jobs(), evaluation.reason()); }
        else { updateAppeals(observation, evaluation, evaluation.reason()); }
        return evaluation;
    }

    private void saveEvaluation(Observation observation, Evaluation evaluation, Set<UUID> selectedJobs, String reason) {
        var previous = observation.selection();
        String values = selectedJobs.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (previous.isEmpty()) {
            store.jdbc.update("insert into subscriptions_evidence(provider_id,billing_payer,job_ids,evidence_digest,valid,reason,milestone,checked_at) values(?,?,?::uuid[],?,?,?,?,?)",
                    observation.binding().provider().value(), observation.binding().originalPayer().value(), values, evaluation.digest(), evaluation.jobs().size() == 2, reason, evaluation.jobs().size(), store.now());
        } else if (!previous.orElseThrow().digest().equals(evaluation.digest()) || !previous.orElseThrow().reason().equals(reason) || !previous.orElseThrow().jobs().equals(selectedJobs)) {
            store.jdbc.update("update subscriptions_evidence set job_ids=?::uuid[],evidence_digest=?,valid=?,reason=?,milestone=?,version=version+1,checked_at=? where provider_id=? and billing_payer=?",
                    values, evaluation.digest(), evaluation.jobs().size() == 2, reason, evaluation.jobs().size(), store.now(), observation.binding().provider().value(), observation.binding().originalPayer().value());
        }
        updateAppeals(observation, evaluation, reason);
    }

    private void updateAppeals(Observation observation, Evaluation evaluation, String reason) {
        String appealState = evaluation.jobs().size() == 2 && !Set.of("CAP_USED", "BACKING_PENDING").contains(reason) ? "RESOLVED" : "RECHECKED";
        for (var appeal : observation.appeals()) {
            if (!appeal.state().equals(appealState)) {
                store.jdbc.update("update subscriptions_appeal set state=?,version=version+1 where id=?", appealState, appeal.id());
                store.jdbc.update("insert into subscriptions_appeal_event values(?,?,?,?,?)", UUID.randomUUID(), appeal.id(), appealState, reason, store.now());
            }
        }
    }

    public Progress progress(SubjectId provider, Optional<JobLoyaltyAccess.Cursor> cursor) {
        return progress(provider, Optional.empty(), cursor, Optional.empty(), Optional.empty());
    }

    public Progress progress(SubjectId provider, Optional<UUID> enrollment, Optional<JobLoyaltyAccess.Cursor> cursor,
            Optional<PromotionBudgetAccess.HistoryCursor> historyCursor, Optional<AppealCursor> appealCursor) {
        standalone();
        return transaction.execute(status -> {
            var own = protectOwn(provider, enrollment, Optional.empty(), cursor);
            var result = evaluate(own.observation());
            if (own.mode() == Mode.CURRENT) {
                validatePage(provider, cursor, own.page());
                if (result.jobs().size() == 2) {
                    String reason = switch (promotions.allocationStatusForUpdate(own.observation().binding())) {
                        case CAP_USED -> "CAP_USED";
                        case BACKING_PENDING -> "BACKING_PENDING";
                        case READY -> "QUALIFIED_PENDING_ALLOCATION";
                        case FUNDED -> own.money().promotionalDebtMinor() > 0 ? "REPLACEMENT_PENDING" : result.reason();
                    };
                    result = new Evaluation(result.jobs(), reason, result.digest());
                }
            }
            return progress(own, result, historyCursor, appealCursor);
        });
    }

    public Progress recheck(SubjectId provider, Optional<UUID> candidate, Optional<JobLoyaltyAccess.Cursor> cursor) {
        return recheck(provider, Optional.empty(), candidate, cursor);
    }

    public Progress recheck(SubjectId provider, Optional<UUID> enrollment, Optional<UUID> candidate, Optional<JobLoyaltyAccess.Cursor> cursor) {
        standalone();
        return transaction.execute(status -> {
            var own = protectOwn(provider, enrollment, candidate, cursor);
            var result = own.mode() == Mode.CURRENT ? apply(own.observation()) : applyRetained(own.observation());
            if (own.mode() == Mode.CURRENT) { validatePage(provider, cursor, own.page()); }
            return progress(own, result, Optional.empty(), Optional.empty());
        });
    }

    private OwnObservation protectOwn(SubjectId provider, Optional<UUID> enrollment, Optional<UUID> candidate, Optional<JobLoyaltyAccess.Cursor> cursor) {
        var actor = new AccountId(proof.actor().orElseThrow(() -> new SubscriptionFailure("Autenticá tu cuenta")));
        var selected = discoverSelected(provider, enrollment);
        boolean current = current(selected, actor);
        var observation = discover(selected.enrollment().binding(), current ? candidate : Optional.empty());
        var page = current ? jobs.discover(provider, cursor) : new JobLoyaltyAccess.CandidatePage(List.of(), 0, Optional.empty());
        var accounts = requiredAccounts(selected.entitlement(), observation, actor);
        protectSelected(selected, actor, accounts);
        if (current != current(selected, actor)) { throw new SubscriptionFailure("Tu autoridad actual cambió; volvé a consultar el progreso"); }
        if (!current && (candidate.isPresent() || cursor.isPresent())) { throw new SubscriptionFailure("La inscripción conservada permite revisar solo su evidencia existente"); }
        protectEvidence(observation);
        List<BillingBinding> bindings = new ArrayList<>(List.of(selected.enrollment().binding()));
        selected.entitlement().providers().get(provider).binding().filter(value -> !bindings.contains(value)).ifPresent(bindings::add);
        var statuses = billing.requireStatusesForUpdate(List.copyOf(bindings));
        entitlements.requireFinancialTargets(selected.entitlement());
        if (!selected.financial().sameTargets(billing.discoverTargets(selected.enrollment().binding()))) { throw new SubscriptionFailure("Las operaciones originales cambiaron; volvé a observarlas"); }
        return new OwnObservation(observation, page, statuses.get(selected.enrollment().binding()),
                current ? Mode.CURRENT : Mode.RETAINED, store.provider(provider, false).orElseThrow().publicAchievements());
    }

    private SelectedDiscovery discoverSelected(SubjectId provider, Optional<UUID> enrollmentId) {
        var entitlement = entitlements.discover(Set.of(provider));
        UUID id = enrollmentId.or(() -> entitlement.providers().get(provider).binding().map(BillingBinding::enrollmentId))
                .orElseThrow(() -> new SubscriptionFailure("Iniciá primero tu inscripción de proveedor"));
        var enrollment = store.enrollment(id, false);
        var financial = billing.discoverTargets(enrollment.binding());
        Set<String> policies = new HashSet<>(financial.policyVersions()); policies.add(enrollment.policyVersion());
        enrollment.benefitPolicyVersion().ifPresent(policies::add);
        enrollment.consent().map(store::consent).ifPresent(consent -> policies.add(consent.policyVersion()));
        return new SelectedDiscovery(provider, entitlement, enrollment, financial, Set.copyOf(policies), campaignVersion(false));
    }

    private void protectSelected(SelectedDiscovery selected, AccountId actor, Set<AccountId> accounts) {
        identity.requireForUpdate(PermissionRequest.self(actor, Capability.ACCESS_SUPPORT), accounts);
        var binding = selected.enrollment().binding();
        if (!binding.provider().equals(selected.provider()) || !binding.originalActor().value().equals(actor.value()) || !binding.originalPayer().equals(actor)) {
            throw new SubscriptionFailure("Solo el pagador original consulta esta inscripción");
        }
        entitlements.protectPolicies(selected.entitlement(), accounts, selected.policies());
        if (campaignVersion(true) != selected.campaignVersion()) { throw new SubscriptionFailure("La campaña cambió; volvé a observarla"); }
        entitlements.protectEnrollments(selected.entitlement(), Set.of(binding.enrollmentId()));
        if (!selected.enrollment().equals(store.enrollment(binding.enrollmentId(), false))) { throw new SubscriptionFailure("La inscripción original cambió; volvé a consultarla"); }
    }

    private boolean current(SelectedDiscovery selected, AccountId actor) {
        return selected.entitlement().providers().get(selected.provider()).binding().filter(selected.enrollment().binding()::equals).isPresent()
                && permissions.decide(new PermissionRequest(Optional.of(new ActorId(actor.value())), Optional.of(selected.provider()), Capability.NEW_PROVIDER_PARTICIPATION, Optional.empty())).allowed();
    }

    private void validatePage(SubjectId provider, Optional<JobLoyaltyAccess.Cursor> cursor, JobLoyaltyAccess.CandidatePage page) {
        if (!page.equals(jobs.discover(provider, cursor))) { throw new SubscriptionFailure("La página de candidatos cambió; revisá el progreso"); }
    }

    private Progress progress(OwnObservation own, Evaluation result, Optional<PromotionBudgetAccess.HistoryCursor> historyCursor, Optional<AppealCursor> appealCursor) {
        var history = promotions.requireHistoryForUpdate(own.observation().binding(), historyCursor);
        boolean usable = result.jobs().size() == 2 && !history.invalid() && history.debtMinor() == 0;
        boolean pendingCorrection = history.award().isPresent() && (history.invalid() == (result.jobs().size() == 2));
        return new Progress(result.jobs().size(), result.reason(), usable && own.mode() == Mode.CURRENT ? history.availableMinor() : 0, history.debtMinor(), own.page(),
                own.publicOptIn(), own.observation().binding().enrollmentId(), own.mode() == Mode.RETAINED, pendingCorrection, history,
                appealHistory(own.observation().binding().enrollmentId(), appealCursor));
    }

    public void publicOptIn(SubjectId provider, boolean enabled) {
        publicOptIn(provider, Optional.empty(), enabled);
    }

    public UUID publicOptIn(SubjectId provider, Optional<UUID> selectedEnrollment, boolean enabled) {
        return transaction.execute(status -> {
            var actor = new ActorId(proof.actor().orElseThrow(() -> new SubscriptionFailure("Autenticá tu cuenta")));
            var entitlement = entitlements.discover(Set.of(provider)); Set<AccountId> accounts = new HashSet<>(entitlement.requiredAccountIds()); accounts.add(new AccountId(actor.value()));
            identity.requireForUpdate(new PermissionRequest(Optional.of(actor), Optional.of(provider), Capability.NEW_PROVIDER_PARTICIPATION, Optional.empty()), Set.copyOf(accounts));
            var enrollment = entitlements.protectCommercial(entitlement, Set.copyOf(accounts)).get(provider);
            if (enrollment == null || !enrollment.binding().originalActor().equals(actor)
                    || selectedEnrollment.filter(id -> !id.equals(enrollment.binding().enrollmentId())).isPresent()) { throw new SubscriptionFailure("Inscripción actual propia requerida"); }
            store.jdbc.update("update subscriptions_provider set public_achievements=?,version=version+1 where provider_id=?", enabled, provider.value());
            return enrollment.binding().enrollmentId();
        });
    }

    @Override public Optional<Achievement> current(SubjectId provider) {
        standalone();
        return transaction.execute(status -> {
            var entitlement = entitlements.discover(Set.of(provider));
            if (entitlement.providers().get(provider).binding().isEmpty() || store.provider(provider, false).filter(SubscriptionStore.Provider::publicAchievements).isEmpty()) { return Optional.empty(); }
            var observation = discover(entitlement, provider, Optional.empty());
            var actor = proof.actor().map(AccountId::new).orElse(new AccountId(provider.value()));
            var accounts = requiredAccounts(entitlement, observation, actor);
            identity.requireForUpdate(PermissionRequest.self(actor, Capability.BROWSE_GENERAL), accounts);
            entitlements.protectPolicies(entitlement, accounts); protectCampaign(observation);
            var current = entitlements.protectEnrollments(entitlement, Set.of()).get(provider);
            if (!current.binding().equals(observation.binding()) || !store.provider(provider, false).orElseThrow().publicAchievements()) { return Optional.empty(); }
            protectEvidence(observation); billing.requireStatusesForUpdate(List.of(observation.binding())); entitlements.requireFinancialTargets(entitlement);
            var result = evaluate(observation);
            if (result.jobs().isEmpty()) { return Optional.empty(); }
            return Optional.of(new Achievement(result.jobs().size(), result.jobs().size() == 2 ? "Dos clientes con trabajos verificados" : "Primer cliente con trabajo verificado", CAMPAIGN, store.clock.instant()));
        });
    }

    public UUID appeal(SubjectId provider, String reason) { return appeal(provider, Optional.empty(), reason); }

    public UUID appeal(SubjectId provider, Optional<UUID> enrollmentId, String reason) {
        return appealReceipt(provider, enrollmentId, reason).appealId();
    }

    public AppealReceipt appealReceipt(SubjectId provider, Optional<UUID> enrollmentId, String reason) {
        if (!Set.of("QUALIFICATION", "RECIPROCAL_HOLD", "PROMOTION_DEBT", "BACKING_PENDING").contains(reason)) { throw new SubscriptionFailure("Elegí un motivo de revisión válido"); }
        return transaction.execute(status -> {
            var actor = new AccountId(proof.actor().orElseThrow(() -> new SubscriptionFailure("Autenticá tu cuenta")));
            var selected = discoverSelected(provider, enrollmentId);
            var accounts = new HashSet<>(selected.entitlement().requiredAccountIds()); accounts.add(actor);
            accounts.add(new AccountId(selected.enrollment().binding().provider().value()));
            accounts.add(new AccountId(selected.enrollment().binding().originalActor().value())); accounts.add(selected.enrollment().binding().originalPayer());
            protectSelected(selected, actor, Set.copyOf(accounts));
            UUID enrollment = selected.enrollment().binding().enrollmentId();
            var existing = store.jdbc.query("select id from subscriptions_appeal where enrollment_id=? and reason=? and state='OPEN' order by id limit 1 for update", (row, index) -> row.getObject(1, UUID.class), enrollment, reason);
            if (!existing.isEmpty()) { return new AppealReceipt(existing.getFirst(), enrollment); }
            UUID id = UUID.randomUUID();
            store.jdbc.update("insert into subscriptions_appeal values(?,?,?,'OPEN',0,?)", id, enrollment, reason, store.now());
            store.jdbc.update("insert into subscriptions_appeal_event values(?,?,'OPEN',?,?)", UUID.randomUUID(), id, reason, store.now());
            return new AppealReceipt(id, enrollment);
        });
    }

    private AppealHistory appealHistory(UUID enrollment, Optional<AppealCursor> cursor) {
        long count = store.jdbc.queryForObject("select count(*) from subscriptions_appeal_event e join subscriptions_appeal a on a.id=e.appeal_id where a.enrollment_id=?", Long.class, enrollment);
        var latest = store.jdbc.query("select e.id,e.occurred_at from subscriptions_appeal_event e join subscriptions_appeal a on a.id=e.appeal_id where a.enrollment_id=? order by e.occurred_at desc,e.id::text desc limit 1",
                (row, index) -> row.getObject(1, UUID.class) + ":" + row.getTimestamp(2).toInstant(), enrollment).stream().findFirst().orElse("");
        String observation = digest(enrollment + ":" + count + ":" + latest);
        cursor.ifPresent(value -> {
            if (!value.enrollmentId().equals(enrollment) || !value.observation().equals(observation)
                    || store.jdbc.queryForObject("select count(*) from subscriptions_appeal_event e join subscriptions_appeal a on a.id=e.appeal_id where a.enrollment_id=? and e.id=? and e.occurred_at=?", Integer.class,
                    enrollment, value.afterId(), Timestamp.from(value.afterTime())) != 1) { throw new SubscriptionFailure("Las revisiones cambiaron; volvé a la primera página"); }
        });
        List<Object> arguments = new ArrayList<>(List.of(enrollment));
        String continuation = cursor.map(value -> {
            arguments.add(Timestamp.from(value.afterTime())); arguments.add(Timestamp.from(value.afterTime())); arguments.add(value.afterId().toString());
            return " and (e.occurred_at<? or (e.occurred_at=? and e.id::text<?))";
        }).orElse("");
        var rows = store.jdbc.query("select e.id,e.state,e.reason,e.occurred_at from subscriptions_appeal_event e join subscriptions_appeal a on a.id=e.appeal_id where a.enrollment_id=?" + continuation + " order by e.occurred_at desc,e.id::text desc limit 21",
                (row, index) -> new AppealEvent(row.getObject(1, UUID.class), AppealState.valueOf(row.getString(2)), row.getString(3), row.getTimestamp(4).toInstant()), arguments.toArray());
        var page = rows.stream().limit(20).toList();
        var next = rows.size() > 20 ? Optional.of(new AppealCursor(enrollment, observation, page.getLast().occurredAt(), page.getLast().eventId())) : Optional.<AppealCursor>empty();
        return new AppealHistory(page, next);
    }

    private boolean confirmed(BillingBinding binding, JobQualification job) {
        return store.jdbc.queryForObject("select count(*) from subscriptions_qualification where campaign=? and provider_id=? and billing_payer=? and job_id=? and completed_event=? and rule_version=?", Integer.class,
                CAMPAIGN, binding.provider().value(), binding.originalPayer().value(), job.jobId(), job.completedEvent().orElseThrow(), CAMPAIGN) == 1;
    }

    private void confirm(BillingBinding binding, JobQualification job) {
        if (confirmed(binding, job)) { return; }
        int inserted = store.jdbc.update("insert into subscriptions_qualification values(?,?,?,?,?,?,?,?,?,?) on conflict do nothing", UUID.randomUUID(), CAMPAIGN,
                binding.provider().value(), binding.originalPayer().value(), job.jobId(), job.completedEvent().orElseThrow(), job.originalCustomerPayer().value(), job.evidenceDigest(), CAMPAIGN, store.now());
        if (inserted != 1) { throw new SubscriptionFailure("El trabajo ya fue ligado a otra evidencia de campaña"); }
        store.jdbc.update("insert into subscriptions_pair_claim(campaign,provider_payer,customer_payer,completed_event,completed_at) values(?,?,?,?,?) on conflict(campaign,provider_payer,customer_payer) do update set completed_event=excluded.completed_event,completed_at=excluded.completed_at,version=subscriptions_pair_claim.version+1 where subscriptions_pair_claim.completed_at<excluded.completed_at",
                CAMPAIGN, binding.originalPayer().value(), job.originalCustomerPayer().value(), job.completedEvent().orElseThrow(), Timestamp.from(job.completedAt().orElseThrow()));
    }

    private Optional<Selection> selected(BillingBinding binding, boolean lock) {
        return store.jdbc.query("select job_ids,evidence_digest,valid,reason,milestone,version from subscriptions_evidence where provider_id=? and billing_payer=?" + (lock ? " for update" : ""),
                (row, index) -> new Selection(Set.of((UUID[]) row.getArray(1).getArray()), row.getString(2), row.getBoolean(3), row.getString(4), row.getInt(5), row.getLong(6)), binding.provider().value(), binding.originalPayer().value()).stream().findFirst();
    }
    private long campaignVersion(boolean lock) { return store.jdbc.query("select version from subscriptions_campaign where code=?" + (lock ? " for update" : ""), (row, index) -> row.getLong(1), CAMPAIGN).stream().findFirst().orElseThrow(() -> new SubscriptionFailure("Campaña sintética pendiente")); }
    private Optional<PairClaim> pair(Pair pair, boolean lock) { return store.jdbc.query("select completed_event,completed_at,version from subscriptions_pair_claim where campaign=? and provider_payer=? and customer_payer=?" + (lock ? " for update" : ""), (row, index) -> new PairClaim(row.getObject(1, UUID.class), row.getTimestamp(2).toInstant(), row.getLong(3)), CAMPAIGN, pair.provider(), pair.customer()).stream().findFirst(); }
    private static String digest(String value) { return ContentDigest.bytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static void standalone() { if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new SubscriptionFailure("El beneficio requiere una observación independiente"); } }
    record Pair(UUID provider, UUID customer) { static final Comparator<Pair> ORDER = Comparator.comparing((Pair value) -> value.provider().toString()).thenComparing(value -> value.customer().toString()); }
    record PairClaim(UUID event, Instant completedAt, long version) {}
    record Selection(Set<UUID> jobs, String digest, boolean valid, String reason, int milestone, long version) { Selection { jobs = Set.copyOf(jobs); } }
    record AppealTarget(UUID id, long version, String state) {}
    record Observation(BillingBinding binding, LoyaltyEvidenceBatch evidence, Optional<Selection> selection, Map<Pair, Optional<PairClaim>> pairs, long campaignVersion, List<AppealTarget> appeals) { Observation { pairs = Map.copyOf(pairs); appeals = List.copyOf(appeals); } }
    private enum Mode { CURRENT, RETAINED }
    private record SelectedDiscovery(SubjectId provider, EntitlementDiscovery entitlement, SubscriptionStore.Enrollment enrollment,
            BillingTargets financial, Set<String> policies, long campaignVersion) { SelectedDiscovery { policies = Set.copyOf(policies); } }
    private record OwnObservation(Observation observation, JobLoyaltyAccess.CandidatePage page, BillingFinancialStatus money, Mode mode, boolean publicOptIn) {}
    public record AppealCursor(UUID enrollmentId, String observation, Instant afterTime, UUID afterId) {
        public AppealCursor {
            Objects.requireNonNull(enrollmentId); Objects.requireNonNull(afterTime); Objects.requireNonNull(afterId);
            if (!observation.matches("[0-9a-f]{64}")) { throw new SubscriptionFailure("Continuación de revisión incompatible"); }
        }
    }
    public enum AppealState { OPEN, RECHECKED, RESOLVED }
    public record AppealReceipt(UUID appealId, UUID enrollmentId) {}
    public record AppealEvent(UUID eventId, AppealState state, String reason, Instant occurredAt) {
        public String stateMessage() { return switch (state) { case OPEN -> "Revisión abierta"; case RECHECKED -> "Evidencia revalidada; resultado pendiente"; case RESOLVED -> "Revisión resuelta con la misma regla"; }; }
        public String reasonMessage() { return switch (reason) { case "PROMOTION_DEBT" -> "Corrección del beneficio consumido"; case "RECIPROCAL_HOLD" -> "Retención por reciprocidad"; case "BACKING_PENDING" -> "Respaldo promocional pendiente"; case "QUALIFIED" -> "Evidencia actualmente válida"; default -> "Calificación actual del beneficio"; }; }
    }
    public record AppealHistory(List<AppealEvent> events, Optional<AppealCursor> next) { public AppealHistory { events = List.copyOf(events); if (events.size() > 20) { throw new SubscriptionFailure("Página de revisión incompatible"); } } }
    record Evaluation(List<JobQualification> jobs, String reason, String digest) { Evaluation { jobs = List.copyOf(jobs); } }
    public record Progress(int verifiedMilestone, String reason, long availableCreditMinor, long debtMinor, JobLoyaltyAccess.CandidatePage candidates, boolean publicOptIn, UUID enrollmentId, boolean retained, boolean pendingCorrection,
            PromotionBudgetAccess.PromotionHistory history, AppealHistory appeals) {
        public String date(Instant value) { return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Argentina/Buenos_Aires")).format(value); }
        public String message() {
            return switch (reason) {
                case "QUALIFIED" -> "Dos clientes cumplen actualmente las reglas publicadas";
                case "KNOWN_SELF_CONTROL" -> "No cuenta un trabajo con controladores conocidos compartidos";
                case "RECIPROCAL_HOLD" -> "Una dirección inversa registrada conserva su retención de 30 días";
                case "DUPLICATE_PAYER" -> "Cada pagador cliente cuenta una sola vez";
                case "CAP_USED" -> "El proveedor o su pagador original ya usó la asignación de esta campaña";
                case "BACKING_PENDING" -> "La evidencia es válida; no hay respaldo promocional disponible para otra asignación";
                case "QUALIFIED_PENDING_ALLOCATION" -> "Dos clientes cumplen las reglas; revalidá para conservar la asignación respaldada";
                case "REPLACEMENT_PENDING" -> "El reemplazo cumple las reglas; revalidá para resolver la deuda del mismo premio sin crédito nuevo";
                default -> "Falta evidencia actual de aceptación final, financiación, liberación, pago o aprobación";
            };
        }
    }
}
