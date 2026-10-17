package ar.changeo.agreements;

import ar.changeo.agreements.api.*;
import ar.changeo.conversations.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.jobs.api.*;
import ar.changeo.listings.api.*;
import ar.changeo.marketplace.MarketplaceService.Upload;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.payments.api.JobAgreementChangeAccess;
import ar.changeo.subscriptions.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgreementService {
    private final AgreementStore store;
    private final AgreementAccess agreements;
    private final ConversationAccess conversations;
    private final JobAccess jobs;
    private final ListingResponseAccess listings;
    private final ModerationAccess moderation;
    private final JobAgreementChangeAccess financialChanges;
    private final FileAccess files;
    private final SecurityProof proof;
    private final IdentityAccess identity;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ParticipantIdentityAccess participants;
    private final ProviderEntitlementAccess entitlements;
    AgreementService(AgreementStore store, AgreementAccess agreements, ConversationAccess conversations, JobAccess jobs,
            ListingResponseAccess listings, ModerationAccess moderation, FileAccess files, SecurityProof proof, IdentityAccess identity, Clock clock,
            PlatformTransactionManager transactions, JobAgreementChangeAccess financialChanges, ParticipantIdentityAccess participants, ProviderEntitlementAccess entitlements) {
        this.store = store; this.agreements = agreements; this.conversations = conversations; this.jobs = jobs; this.listings = listings;
        this.moderation = moderation; this.files = files; this.proof = proof; this.identity = identity; this.clock = clock; this.transaction = new TransactionTemplate(transactions);
        this.financialChanges = financialChanges;
        this.participants = participants; this.entitlements = entitlements;
    }
    public ContentReference save(Draft draft) {
        return transaction.execute(status -> {
            var discovered = jobs.discover(draft.jobId());
            ActorId actor = actor();
            String digest = digest(draft.jobId() + ":" + draft.quoteId() + ":" + draft.version() + ":" + draft.subject() + ":" + draft.kind() + ":" + draft.label()
                    + ":" + store.encode(draft.terms()) + ":" + draft.uploads().stream().map(upload -> upload.mediaType() + ":" + ContentDigest.bytes(upload.bytes())).toList());
            var replay = receipt(actor, draft.command(), "SAVE", digest);
            protectProspective(discovered, actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE, !discovered.accepted() && draft.kind().equals("INITIAL") && replay.isEmpty());
            conversations.protect(discovered.conversation().id(), actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE);
            var job = jobs.lock(draft.jobId());
            if (job.accepted() != discovered.accepted()) { throw new IllegalArgumentException("La selección cambió; volvé a observar el trabajo"); }
            boolean change = draft.kind().equals("CHANGE");
            if ((!change && job.accepted()) || (change && !job.accepted()) || !Set.of("INITIAL", "CHANGE").contains(draft.kind())
                    || draft.uploads().size() > 4 || !Set.of("GENERAL", "SENSITIVE", "ADULT").contains(draft.label())) { throw new IllegalArgumentException("Presupuesto incompatible con el trabajo"); }
            if (replay.isPresent()) {
                UUID quote = store.jdbc.queryForObject("select quote_id from agreements_revision where id=?", UUID.class, replay.orElseThrow());
                return new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, quote, replay.orElseThrow());
            }
            UUID quoteId = draft.quoteId().orElseGet(UUID::randomUUID);
            if (draft.quoteId().isPresent()) {
                var quote = store.quote(quoteId, true);
                if (!quote.jobId().equals(job.id()) || quote.version() != draft.version() || !quote.kind().equals(draft.kind())
                        || job.agreement().filter(reference -> reference.resourceId().equals(quoteId)).isPresent()) { throw new IllegalArgumentException("Presupuesto desactualizado o aceptado"); }
                var previous = agreements.discover(new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, quoteId, quote.current()));
                if (!actor.equals(previous.author().actor()) || !draft.subject().equals(previous.author().subject())) { throw new IllegalArgumentException("Solo el autor edita su presupuesto"); }
            } else { store.jdbc.update("insert into agreements_quote(id,job_id,kind) values(?,?,?)", quoteId, job.id(), draft.kind()); }
            var reference = new ContentReference(SurfaceKind.QUOTE_DESCRIPTION, quoteId, UUID.randomUUID());
            var author = new AuthorContext(actor, draft.subject(), Capability.SEND_MARKETPLACE_MESSAGE, Optional.of(job.conversation().jobContext()));
            List<FileReference> attachments = new ArrayList<>();
            for (int index = 0; index < draft.uploads().size(); index++) {
                var upload = draft.uploads().get(index);
                attachments.add(files.intake(UUID.nameUUIDFromBytes((draft.command() + ":" + index).getBytes(StandardCharsets.UTF_8)), author, reference, upload.mediaType(), upload.bytes()));
            }
            attachments.sort(Comparator.comparing(file -> file.id().toString()));
            store.jdbc.update("insert into agreements_revision values(?,?,?,?,?,?,?,?,?)", reference.revisionId(), quoteId, actor.value(), draft.subject().value(),
                    store.encode(draft.terms()), draft.terms().reviewText(), draft.label(), ContentDigest.payload(draft.terms().reviewText(), draft.label(), attachments), Timestamp.from(clock.instant()));
            for (var file : attachments) { store.jdbc.update("insert into agreements_attachment values(?,?,?)", reference.revisionId(), file.id(), file.digest()); }
            store.jdbc.update("update agreements_quote set current_revision=?,version=version+1 where id=?", reference.revisionId(), quoteId);
            record(actor, draft.command(), "SAVE", digest, reference.revisionId());
            return reference;
        });
    }
    public ModerationAccess.Approval submit(ContentReference reference) {
        return transaction.execute(status -> {
            var revision = agreements.discover(reference);
            if (!actor().equals(revision.author().actor())) { throw new IllegalArgumentException("Solo el autor puede enviar esta revisión"); }
            return moderation.submitForReview(revision.author(), reference, revision.terms().reviewText(), revision.declaredLabel(), revision.attachments());
        });
    }
    public JobSnapshot accept(UUID command, ContentReference reference, long expectedVersion, SubjectId subject) {
        return transaction.execute(status -> {
            var discovered = agreements.discover(reference);
            var before = jobs.discover(discovered.jobId());
            var context = before.conversation();
            ActorId actor = actor();
            String digest = digest(reference + ":" + expectedVersion + ":" + subject);
            boolean replay = receipt(actor, command, "ACCEPT", digest).isPresent();
            protectProspective(before, actor, subject, Capability.ACCEPT_AGREEMENT, !before.accepted() && discovered.kind().equals("INITIAL") && !replay);
            var decision = conversations.authorize(context.id(), actor, subject, Capability.ACCEPT_AGREEMENT);
            var customerDecision = conversations.authorize(context.id(), context.customerActor(), context.customer(), Capability.ACCEPT_AGREEMENT);
            conversations.authorize(context.id(), context.providerActor(), context.provider(), Capability.ACCEPT_AGREEMENT);
            boolean openRequest = !before.accepted() && context.listing().ownerKind() == SurfaceKind.REQUEST_BODY;
            if (openRequest) { listings.prepareResponse(context.listing()); }
            var job = jobs.lock(before.id());
            if (job.accepted() != before.accepted()) { throw new IllegalStateException("La selección cambió; volvé a observar el trabajo"); }
            conversations.requireCurrent(job.conversation());
            for (var sideSubject : List.of(context.customer(), context.provider())) {
                if (!identity.decide(new PermissionRequest(Optional.of(context.historicalActor(sideSubject)), Optional.of(sideSubject), Capability.ACCEPT_AGREEMENT, Optional.of(context.jobContext()))).allowed()) {
                    throw new IllegalStateException("Autoridad actual para aceptar esta revisión requerida");
                }
            }
            AgreementSnapshot revision;
            AgreementSnapshot original = null;
            if (discovered.kind().equals("CHANGE") && job.agreement().orElseThrow().resourceId().toString().compareTo(reference.resourceId().toString()) < 0) {
                original = agreements.lock(job.agreement().orElseThrow()); revision = agreements.lock(reference);
            } else {
                revision = agreements.lock(reference);
                if (discovered.kind().equals("CHANGE")) { original = agreements.lock(job.agreement().orElseThrow()); }
            }
            if (replay) { return job; }
            if (!revision.current() || revision.version() != expectedVersion || !job.conversation().equals(context)
                    || (!revision.kind().equals("CHANGE") && job.accepted())) { throw new IllegalArgumentException("Acuerdo desactualizado o ya seleccionado"); }
            revision.terms().requireComplete(context.mode());
            FundingAgreement originalFunding = null; FundingAgreement replacementFunding = null; boolean replace = false;
            if (revision.kind().equals("CHANGE")) {
                if (!customerDecision.legalPayerId().equals(job.legalPayer())) { throw new IllegalStateException("El cambio conserva al pagador original"); }
                originalFunding = funding(job, original); replacementFunding = funding(job, revision);
                replace = financialChanges.prepareReplacementForUpdate(originalFunding, replacementFunding)
                        && job.state() == JobSnapshot.State.AGREED_AWAITING_FUNDING;
            }
            Set<ContentReference> references = original != null ? Set.of(reference, original.reference()) : openRequest ? Set.of(reference, context.listing()) : Set.of(reference);
            moderation.approvalsForUpdate(references);
            agreements.requireApproved(revision);
            if (original != null) { agreements.requireApproved(original); }
            var approval = moderation.approvalForUpdate(reference);
            Capability audience = switch (approval.label()) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; case "ADULT" -> Capability.VIEW_ADULT; default -> throw new IllegalArgumentException("Clasificación desconocida"); };
            for (var sideSubject : List.of(context.customer(), context.provider())) {
                var sideActor = context.historicalActor(sideSubject);
                if ((approval.label().equals("ADULT") && !sideActor.value().equals(sideSubject.value()))
                        || !identity.decide(PermissionRequest.self(new AccountId(sideActor.value()), audience)).allowed()) { throw new IllegalStateException("Las dos partes deben poder consultar los términos exactos"); }
            }
            if (original != null) { requireAudience(context, moderation.approvalForUpdate(original.reference()).label()); }
            if (openRequest) { listings.requireOpen(context.listing()); }
            String side = subject.equals(context.customer()) ? "CUSTOMER" : "PROVIDER";
            var existing = store.jdbc.queryForList("select actor_id,subject_id,legal_payer from agreements_acceptance where quote_id=? and revision_id=? and side=?", reference.resourceId(), reference.revisionId(), side);
            AccountId payer = customerDecision.legalPayerId().orElseThrow();
            if (existing.isEmpty()) {
                store.jdbc.update("insert into agreements_acceptance values(?,?,?,?,?,?,?)", reference.resourceId(), reference.revisionId(), side, actor.value(), subject.value(),
                        side.equals("CUSTOMER") ? decision.legalPayerId().orElseThrow().value() : null, Timestamp.from(clock.instant()));
            } else if (!actor.value().equals(existing.getFirst().get("actor_id")) || !subject.value().equals(existing.getFirst().get("subject_id"))) {
                throw new IllegalArgumentException("Aceptación ajena");
            }
            int count = store.jdbc.queryForObject("select count(*) from agreements_acceptance where quote_id=? and revision_id=?", Integer.class, reference.resourceId(), reference.revisionId());
            if (count == 2) {
                if (revision.kind().equals("CHANGE")) {
                    if (replace) {
                        financialChanges.replaceForUpdate(originalFunding, replacementFunding);
                        jobs.replaceUnfundedAgreement(job.id(), original.reference(), reference, job.legalPayer().orElseThrow());
                    } else { jobs.changePending(job.id(), reference); }
                }
                else {
                    if (openRequest) { listings.selectRequestProvider(new ListingSelection(context.listing(), job.id(), context.customer(), context.provider(), context.customerActor())); }
                    jobs.agree(job.id(), reference, payer);
                }
            }
            record(actor, command, "ACCEPT", digest, reference.revisionId());
            return jobs.discover(job.id());
        });
    }
    private static FundingAgreement funding(JobSnapshot job, AgreementSnapshot revision) {
        var context = job.conversation(); var terms = revision.terms().requireComplete(context.mode());
        if (!revision.jobId().equals(job.id())) { throw new IllegalArgumentException("Presupuesto ajeno al trabajo"); }
        return new FundingAgreement(job.id(), revision.reference(), context.customer(), context.provider(), context.customerActor(), context.providerActor(),
                job.legalPayer().orElseThrow(), terms.serviceMinor(), terms.deliveryMinor(), terms.totalMinor(), terms, job.version());
    }
    private void protectProspective(JobSnapshot job, ActorId actor, SubjectId subject, Capability capability, boolean required) {
        if (!required) { return; }
        var discovery = entitlements.discover(Set.of(job.conversation().provider()));
        Set<AccountId> accounts = new HashSet<>(conversations.participants(job.conversation().id()));
        accounts.addAll(discovery.requiredAccountIds()); accounts.add(new AccountId(actor.value())); accounts.add(new AccountId(subject.value()));
        var declared = Set.copyOf(accounts);
        participants.requireForUpdate(new PermissionRequest(Optional.of(actor), Optional.of(subject), capability,
                capability == Capability.ACCEPT_AGREEMENT ? Optional.of(job.conversation().jobContext()) : Optional.empty()), declared);
        entitlements.requireNewParticipationForUpdate(discovery, declared);
    }
    private void requireAudience(ConversationContext context, String label) {
        Capability audience = switch (label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; case "ADULT" -> Capability.VIEW_ADULT; default -> throw new IllegalStateException("Clasificación desconocida"); };
        for (var subject : List.of(context.customer(), context.provider())) {
            var actor = context.historicalActor(subject);
            if ((label.equals("ADULT") && !actor.value().equals(subject.value())) || !identity.decide(PermissionRequest.self(new AccountId(actor.value()), audience)).allowed()) {
                throw new IllegalStateException("Las dos partes deben poder consultar también los términos originales exactos");
            }
        }
    }
    private Optional<UUID> receipt(ActorId actor, UUID command, String operation, String digest) {
        var rows = store.jdbc.queryForList("select operation,digest,outcome_id from agreements_receipt where actor_id=? and command_id=?", actor.value(), command);
        if (rows.isEmpty()) { return Optional.empty(); }
        var row = rows.getFirst();
        if (!operation.equals(row.get("operation")) || !digest.equals(row.get("digest"))) { throw new IllegalArgumentException("Comando ligado a otro acuerdo"); }
        return Optional.of((UUID) row.get("outcome_id"));
    }
    private void record(ActorId actor, UUID command, String operation, String digest, UUID outcome) { store.jdbc.update("insert into agreements_receipt values(?,?,?,?,?)", actor.value(), command, operation, digest, outcome); }
    private static String digest(String text) { return ContentDigest.bytes(text.getBytes(StandardCharsets.UTF_8)); }
    private ActorId actor() { return new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta autenticada requerida"))); }
    public record Draft(UUID command, UUID jobId, Optional<UUID> quoteId, long version, SubjectId subject, String kind,
            AgreementTerms terms, String label, List<Upload> uploads) {
        public Draft { Objects.requireNonNull(command); Objects.requireNonNull(jobId); Objects.requireNonNull(quoteId); Objects.requireNonNull(subject); Objects.requireNonNull(kind); Objects.requireNonNull(terms); Objects.requireNonNull(label); uploads = List.copyOf(uploads); }
    }
}
