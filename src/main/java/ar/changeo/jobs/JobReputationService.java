package ar.changeo.jobs;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class JobReputationService implements JobReputationAccess {
    private final JobStore store;
    private final JobContentStore content;
    private final ConversationAccess conversations;
    private final ParticipantIdentityAccess identity;
    private final IdentityAccess permissions;
    private final AgreementAccess agreements;
    private final JobMoneyStatusAccess money;
    private final ModerationAccess moderation;
    private final SecurityProof proof;
    private final Clock clock;
    private final TransactionTemplate transaction;
    JobReputationService(JobStore store, JobContentStore content, ConversationAccess conversations, ParticipantIdentityAccess identity,
            IdentityAccess permissions, AgreementAccess agreements, JobMoneyStatusAccess money, ModerationAccess moderation, SecurityProof proof, Clock clock, PlatformTransactionManager transactions) {
        this.store = store; this.content = content; this.conversations = conversations; this.identity = identity; this.permissions = permissions;
        this.agreements = agreements; this.money = money; this.moderation = moderation; this.proof = proof; this.clock = clock; this.transaction = new TransactionTemplate(transactions);
    }
    @Override public ReputationPage project(SubjectId subject, Optional<ReputationCursor> cursor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("La reputación requiere una transacción independiente"); }
        return transaction.execute(status -> protectedPage(subject, cursor));
    }
    private ReputationPage protectedPage(SubjectId subject, Optional<ReputationCursor> cursor) {
        long offset = cursor.map(ReputationCursor::offset).orElse(0L);
        var initialVersion = version(subject, false);
        long discoveredVersion = initialVersion.orElse(0L);
        var discovered = page(subject, offset);
        Set<AccountId> accounts = participants(subject, discovered);
        AccountId guardActor = proof.actor().map(AccountId::new).orElse(new AccountId(subject.value()));
        identity.requireForUpdate(PermissionRequest.self(guardActor, Capability.BROWSE_GENERAL), accounts);
        Map<UUID, JobSnapshot> discoveredJobs = new HashMap<>();
        Set<UUID> cohortSubjects = new HashSet<>(Set.of(subject.value()));
        for (var review : discovered) {
            var job = store.job(review.jobId(), false); discoveredJobs.put(job.id(), job);
            cohortSubjects.add(job.conversation().customer().value()); cohortSubjects.add(job.conversation().provider().value());
        }
        for (UUID thread : discoveredJobs.values().stream().map(job -> job.conversation().id()).distinct().sorted(Comparator.comparing(UUID::toString)).toList()) { conversations.lock(thread); }
        for (var job : discoveredJobs.values()) { conversations.requireEligible(conversations.discover(job.conversation().id())); }
        if (initialVersion.isEmpty()) { store.jdbc.update("insert into jobs_reputation_cohort(subject_id) values(?) on conflict do nothing", subject.value()); }
        for (UUID cohort : cohortSubjects.stream().sorted(Comparator.comparing(UUID::toString)).toList()) { version(new SubjectId(cohort), true).orElseThrow(); }
        long protectedVersion = version(subject, false).orElseThrow();
        if (protectedVersion != discoveredVersion || cursor.filter(value -> value.cohortVersion() != protectedVersion).isPresent()
                || !discovered.equals(page(subject, offset)) || !accounts.equals(participants(subject, discovered))) {
            throw new IllegalStateException("La página cambió; volvé a observar la reputación desde el inicio");
        }
        Map<UUID, JobSnapshot> jobs = new HashMap<>();
        for (UUID id : discoveredJobs.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList()) { jobs.put(id, store.job(id, true)); }
        Map<ContentReference, AgreementSnapshot> terms = new HashMap<>();
        Map<UUID, JobContentStore.Resource> reviews = new HashMap<>();
        Set<ContentReference> references = new HashSet<>();
        for (var review : discovered) { references.add(review.terms()); references.add(review.reference(review.approved())); }
        if (references.size() > 32) { throw new IllegalStateException("Página demasiado grande"); }
        for (var reference : references.stream().sorted(Comparator.comparing(value -> value.resourceId().toString())).toList()) {
            if (reference.ownerKind() == SurfaceKind.QUOTE_DESCRIPTION) { terms.put(reference, agreements.lock(reference)); }
            else { reviews.put(reference.resourceId(), content.resource(reference.resourceId(), true)); }
        }
        Map<UUID, JobMoneyStatus> funds = new HashMap<>();
        for (UUID id : jobs.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var agreement = jobs.get(id).agreement().orElseThrow();
            funds.put(id, money.currentForUpdate(id, agreement).requireExact(id, agreement));
        }
        var approvals = references.isEmpty() ? Map.<ContentReference, ModerationAccess.Approval>of() : moderation.approvalsForUpdate(Set.copyOf(references));
        Map<Integer, Integer> histogram = new HashMap<>(); int eligible = 0;
        for (var discoveredReview : discovered) {
            var review = reviews.get(discoveredReview.id()); var job = jobs.get(review.jobId());
            var reference = review.reference(review.approved()); var approval = approvals.get(reference); var term = terms.get(review.terms());
            var revision = content.revision(review, review.approved());
            if (job.state() != JobSnapshot.State.COMPLETED || !job.agreement().filter(review.terms()::equals).isPresent()
                    || !subject.value().equals(review.target()) || !job.conversation().participant(new SubjectId(review.subject()))
                    || subject.value().equals(review.subject()) || !funds.get(job.id()).permitsFundedWork()
                    || !term.approved() || !approvals.get(term.reference()).state().equals("APPROVED") || !approvals.get(term.reference()).digest().equals(term.digest())
                    || !approval.state().equals("APPROVED") || !approval.digest().equals(revision.input().digest()) || !approval.reference().equals(reference)) { continue; }
            var actor = proof.actor().map(ActorId::new);
            if (!visible(actor, approval.label()) || !visible(actor, approvals.get(term.reference()).label())) { continue; }
            histogram.merge(revision.score(), 1, Integer::sum); eligible++;
        }
        var continuation = discovered.size() == 10 ? Optional.of(new ReputationCursor(Math.addExact(offset, discovered.size()), protectedVersion)) : Optional.<ReputationCursor>empty();
        return new ReputationPage(histogram, eligible, discovered.size(), clock.instant(), continuation);
    }
    private boolean visible(Optional<ActorId> actor, String label) {
        Capability audience = switch (label) {
            case "GENERAL" -> Capability.BROWSE_GENERAL;
            case "SENSITIVE" -> Capability.VIEW_SENSITIVE;
            case "ADULT" -> Capability.VIEW_ADULT;
            default -> throw new IllegalStateException("Clasificación desconocida");
        };
        return permissions.decide(new PermissionRequest(actor, actor.map(value -> new SubjectId(value.value())), audience, Optional.empty())).allowed();
    }
    private List<JobContentStore.Resource> page(SubjectId subject, long offset) {
        return content.jdbc.query("select id from jobs_authored where kind='REVIEW' and target_subject=? and approved_revision is not null order by id::text limit 10 offset ?",
                (row, index) -> content.resource(row.getObject(1, UUID.class), false), subject.value(), offset);
    }
    private Set<AccountId> participants(SubjectId subject, List<JobContentStore.Resource> reviews) {
        Set<AccountId> accounts = new HashSet<>(Set.of(new AccountId(subject.value())));
        identity.controllers(Set.of(subject)).values().forEach(actor -> accounts.add(new AccountId(actor.value())));
        proof.actor().ifPresent(actor -> accounts.add(new AccountId(actor)));
        for (var review : reviews) { accounts.addAll(conversations.participants(store.job(review.jobId(), false).conversation().id())); }
        return Set.copyOf(accounts);
    }
    private Optional<Long> version(SubjectId subject, boolean lock) {
        return store.jdbc.query("select version from jobs_reputation_cohort where subject_id=?" + (lock ? " for update" : ""), (row, index) -> row.getLong(1), subject.value()).stream().findFirst();
    }
}
