package ar.changeo.jobs;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.*;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.ContentDigest;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name = "changeo.synthetic-subscriptions", havingValue = "true")
class JobLoyaltyService implements JobLoyaltyAccess {
    private final JobStore store;
    private final JobContentStore content;
    private final ConversationAccess conversations;
    private final ParticipantIdentityAccess identity;
    private final AgreementAccess agreements;
    private final JobLoyaltyMoneyAccess money;
    private final ModerationAccess moderation;

    JobLoyaltyService(JobStore store, JobContentStore content, ConversationAccess conversations, ParticipantIdentityAccess identity,
            AgreementAccess agreements, JobLoyaltyMoneyAccess money, ModerationAccess moderation) {
        this.store = store; this.content = content; this.conversations = conversations; this.identity = identity;
        this.agreements = agreements; this.money = money; this.moderation = moderation;
    }

    @Override public CandidatePage discover(SubjectId provider, Optional<Cursor> cursor) {
        long version = cohort(provider.value(), false).orElse(0L);
        if (cursor.filter(value -> value.cohortVersion() != version).isPresent()) { throw new IllegalStateException("Los trabajos cambiaron; volvé a empezar la página"); }
        var ids = cursor.isEmpty() ? store.jdbc.query("select id from jobs_job where provider_id=? and accepted_quote is not null order by id::text limit 20", (row, index) -> row.getObject(1, UUID.class), provider.value())
                : store.jdbc.query("select id from jobs_job where provider_id=? and accepted_quote is not null and id::text>? order by id::text limit 20", (row, index) -> row.getObject(1, UUID.class), provider.value(), cursor.orElseThrow().after().toString());
        return new CandidatePage(ids, version, ids.size() == 20 ? Optional.of(new Cursor(ids.getLast(), version)) : Optional.empty());
    }

    @Override public LoyaltyEvidenceBatch discoverEvidence(SubjectId provider, Set<UUID> jobIds) {
        if (jobIds.size() > 3) { throw new IllegalStateException("Máximo tres trabajos propios en una calificación completa"); }
        Map<UUID, LoyaltyEvidenceBatch.Evidence> evidence = new HashMap<>(); Map<UUID, Long> cohorts = new HashMap<>();
        Set<AccountId> accounts = new HashSet<>(Set.of(new AccountId(provider.value()))); Set<ContentReference> references = new HashSet<>();
        identity.controllers(Set.of(provider)).values().forEach(actor -> accounts.add(new AccountId(actor.value())));
        for (UUID id : jobIds.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var job = store.job(id, false);
            if (!job.conversation().provider().equals(provider) || job.agreement().isEmpty()) { throw new IllegalArgumentException("Trabajo ajeno a la evidencia propia seleccionada"); }
            var revision = agreements.discover(job.agreement().orElseThrow());
            var context = job.conversation();
            Set<AccountId> providerAccounts = new HashSet<>(List.of(new AccountId(provider.value()), new AccountId(context.providerActor().value())));
            Set<AccountId> customerAccounts = new HashSet<>(List.of(new AccountId(context.customer().value()), new AccountId(context.customerActor().value()), job.legalPayer().orElseThrow()));
            var controllers = identity.controllers(Set.of(context.customer(), provider));
            Optional.ofNullable(controllers.get(provider)).ifPresent(actor -> providerAccounts.add(new AccountId(actor.value())));
            Optional.ofNullable(controllers.get(context.customer())).ifPresent(actor -> customerAccounts.add(new AccountId(actor.value())));
            accounts.addAll(conversations.participants(context.id())); accounts.addAll(providerAccounts); accounts.addAll(customerAccounts);
            for (var subject : List.of(provider, context.customer())) { cohorts.put(subject.value(), cohort(subject.value(), false).orElse(0L)); }
            var completed = store.jdbc.query("select id,occurred_at from jobs_event where job_id=? and kind='COMPLETED' and actor_id=? and resource_id=? and revision_id=? order by id limit 2",
                    (row, index) -> Map.entry(row.getObject(1, UUID.class), row.getTimestamp(2).toInstant()), job.id(), job.legalPayer().orElseThrow().value(),
                    job.finalDelivery().map(ContentReference::resourceId).orElse(null), job.finalDelivery().map(ContentReference::revisionId).orElse(null));
            if (completed.size() > 1) { throw new IllegalStateException("Aceptación final ambigua"); }
            references.add(revision.reference()); job.finalDelivery().ifPresent(references::add);
            evidence.put(id, new LoyaltyEvidenceBatch.Evidence(id, context.id(), context.version(), job.version(), job.state(), JobFinancialMetadata.agreement(job, revision),
                    job.finalDelivery(), completed.stream().findFirst().map(Map.Entry::getKey), completed.stream().findFirst().map(Map.Entry::getValue), providerAccounts, customerAccounts));
        }
        var financial = money.discover(evidence.values().stream().map(LoyaltyEvidenceBatch.Evidence::agreement).toList());
        return new LoyaltyEvidenceBatch(provider, evidence, accounts, cohorts, references, financial);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, JobQualification> requireQualificationForUpdate(LoyaltyEvidenceBatch batch) {
        for (UUID thread : batch.jobs().values().stream().map(LoyaltyEvidenceBatch.Evidence::threadId).distinct().sorted(Comparator.comparing(UUID::toString)).toList()) { conversations.lock(thread); }
        for (UUID subject : batch.cohortVersions().keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            if (!cohort(subject, true).equals(Optional.of(batch.cohortVersions().get(subject)))) { throw new IllegalStateException("La cohorte cambió; volvé a descubrir la evidencia"); }
        }
        Map<UUID, JobSnapshot> protectedJobs = new HashMap<>();
        for (UUID id : batch.jobs().keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList()) { protectedJobs.put(id, store.job(id, true)); }
        if (!batch.equals(discoverEvidence(batch.provider(), batch.jobs().keySet()))) { throw new IllegalStateException("La evidencia completa cambió; volvé a observarla"); }
        Map<ContentReference, AgreementSnapshot> terms = new HashMap<>(); Map<UUID, JobContentStore.Resource> finals = new HashMap<>();
        for (var reference : batch.approvalReferences().stream().sorted(Comparator.comparing(value -> value.resourceId().toString())).toList()) {
            if (reference.ownerKind() == SurfaceKind.QUOTE_DESCRIPTION) { terms.put(reference, agreements.lock(reference)); }
            else {
                var resource = content.resource(reference.resourceId(), true); finals.put(resource.id(), resource);
                if (store.jdbc.queryForList("select id from jobs_revision where resource_id=? and id=? for key share", reference.resourceId(), reference.revisionId()).size() != 1) { throw new IllegalStateException("Revisión final no disponible"); }
            }
        }
        var financial = money.requireForUpdate(batch.jobs().values().stream().map(LoyaltyEvidenceBatch.Evidence::agreement).toList(), batch.financialDiscovery());
        var approvals = batch.approvalReferences().isEmpty() ? Map.<ContentReference, ModerationAccess.Approval>of() : moderation.approvalsForUpdate(batch.approvalReferences());
        Map<UUID, JobQualification> result = new HashMap<>();
        for (var evidence : batch.jobs().values()) {
            var job = protectedJobs.get(evidence.jobId()); var context = job.conversation(); var revision = terms.get(evidence.agreement().agreement());
            Set<String> labels = new HashSet<>(Set.of(approvals.get(revision.reference()).label()));
            evidence.finalDelivery().ifPresent(reference -> labels.add(approvals.get(reference).label()));
            boolean eligible = conversations.eligibleForLoyaltyFacts(context, Set.copyOf(labels));
            boolean valid = eligible && job.state() == JobSnapshot.State.COMPLETED && evidence.completedEvent().isPresent() && evidence.completedAt().isPresent()
                    && evidence.agreement().legalPayer().value().equals(context.customerActor().value()) && evidence.agreement().serviceMinor() >= 5000
                    && revision.approved() && exact(approvals.get(revision.reference()), revision.reference(), revision.digest()) && financial.get(job.id()).qualified();
            String finalDigest = "";
            if (evidence.finalDelivery().isPresent()) {
                var reference = evidence.finalDelivery().orElseThrow(); var resource = finals.get(reference.resourceId());
                finalDigest = eligible ? content.revision(resource, reference.revisionId()).input().digest() : approvals.get(reference).digest();
                valid &= resource.jobId().equals(job.id()) && resource.kind() == JobContentStore.Kind.FINAL_DELIVERY && resource.subject().equals(context.provider().value())
                        && resource.actor().equals(context.providerActor().value()) && resource.terms().equals(revision.reference())
                        && reference.revisionId().equals(resource.approved()) && exact(approvals.get(reference), reference, finalDigest);
            } else { valid = false; }
            Set<ContentReference> ownReferences = new HashSet<>(Set.of(revision.reference())); evidence.finalDelivery().ifPresent(ownReferences::add);
            String approvalBinding = ownReferences.stream().sorted(Comparator.comparing(reference -> reference.resourceId().toString()))
                    .map(reference -> approvals.get(reference).toString()).collect(java.util.stream.Collectors.joining("|"));
            String providerAccounts = evidence.originalProviderAccounts().stream().map(account -> account.value().toString()).sorted().collect(java.util.stream.Collectors.joining(","));
            String customerAccounts = evidence.originalCustomerAccounts().stream().map(account -> account.value().toString()).sorted().collect(java.util.stream.Collectors.joining(","));
            String reason = valid ? "QUALIFIED" : eligible ? "CURRENT_JOB_OR_FINANCIAL_APPROVAL_PENDING" : "CURRENT_PARTICIPATION_INELIGIBLE";
            String digest = ContentDigest.bytes((evidence.jobId() + ":" + evidence.threadId() + ":" + evidence.threadVersion() + ":" + evidence.jobVersion() + ":"
                    + evidence.completedEvent() + ":" + evidence.completedAt() + ":" + providerAccounts + ":" + customerAccounts + ":" + revision.digest() + ":" + finalDigest
                    + ":" + financial.get(job.id()) + ":" + approvalBinding + ":" + eligible + ":" + valid + ":" + reason).getBytes(StandardCharsets.UTF_8));
            result.put(job.id(), new JobQualification(job.id(), context.provider(), context.customer(), evidence.agreement().legalPayer(), context.providerActor(),
                    evidence.originalProviderAccounts(), evidence.originalCustomerAccounts(), evidence.completedEvent(), evidence.completedAt(), revision.reference(), evidence.finalDelivery(), evidence.agreement().serviceMinor(),
                    valid, reason, digest));
        }
        return Map.copyOf(result);
    }

    private static boolean exact(ModerationAccess.Approval approval, ContentReference reference, String digest) {
        return approval != null && approval.state().equals("APPROVED") && approval.reference().equals(reference) && approval.digest().equals(digest);
    }
    private Optional<Long> cohort(UUID subject, boolean lock) { return store.jdbc.query("select version from jobs_reputation_cohort where subject_id=?" + (lock ? " for update" : ""), (row, index) -> row.getLong(1), subject).stream().findFirst(); }
}
