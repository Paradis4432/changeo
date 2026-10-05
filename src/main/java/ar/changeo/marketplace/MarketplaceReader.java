package ar.changeo.marketplace;

import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MarketplaceReader {
    private final MarketplaceStore store;
    private final IdentityAccess identity;
    private final PublicIdentityBadgeAccess badges;
    private final SecurityProof proof;
    private final ModerationAccess moderation;
    private final Clock clock;
    private final TransactionTemplate transaction;

    MarketplaceReader(MarketplaceStore store, IdentityAccess identity, PublicIdentityBadgeAccess badges,
                      SecurityProof proof, ModerationAccess moderation, Clock clock, PlatformTransactionManager transactions) {
        this.store=store; this.identity=identity; this.badges=badges; this.proof=proof; this.moderation=moderation;
        this.clock=clock; this.transaction=new TransactionTemplate(transactions);
    }

    public PublicView publicView(UUID id) { return transaction.execute(status -> currentInTransaction(id,Optional.empty())); }

    PublicView currentInTransaction(UUID id, Optional<Selection> selection) {
        MarketplaceStore.requireTransaction();
        var discovered=store.resource(id,false);
        if (discovered.approved()==null) { throw MarketplaceStore.unavailable(); }
        var reference=discovered.reference(discovered.approved());
        var expected=moderation.approval(reference);
        var projected=badges.findCurrent(Set.of(new AccountId(discovered.subject())));
        audience(expected.label());
        var resource=store.resource(id,true);
        if (resource.lifecycle()!=PublicationLifecycle.OPEN || !Objects.equals(resource.approved(),discovered.approved())
                || !resource.subject().equals(discovered.subject())) { throw MarketplaceStore.unavailable(); }
        var revision=store.revision(resource,resource.approved());
        var approval=moderation.approvalForUpdate(reference);
        exactApproval(revision,approval);
        if (!approval.label().equals(expected.label())) { throw MarketplaceStore.unavailable(); }
        selection.ifPresent(value -> {
            if (!value.reference().equals(reference) || !value.subject().equals(resource.subject())
                    || !value.digest().equals(revision.digest()) || !value.label().equals(approval.label())) { throw MarketplaceStore.unavailable(); }
        });
        var badge=revision.details().badgeOptIn() && resource.type().profile()
                ? Optional.ofNullable(projected.get(new AccountId(resource.subject()))).filter(value -> value.validUntil().isAfter(clock.instant()))
                : Optional.<PublicIdentityBadge>empty();
        return new PublicView(reference,new AccountId(resource.subject()),resource.type(),revision.details(),approval.label(),revision.attachments(),badge);
    }

    public PublicView publicProfile(UUID subject, PresenceType type) {
        if (!type.profile()) { throw MarketplaceStore.unavailable(); }
        var ids=store.jdbc.query("select id from marketplace_resource where subject_id=? and type=?", (row,index) -> row.getObject(1,UUID.class),subject,type.name());
        if (ids.isEmpty()) { throw MarketplaceStore.unavailable(); }
        return publicView(ids.getFirst());
    }

    public OwnView own(UUID id) {
        return transaction.execute(status -> {
            UUID actor=proof.actor().orElseThrow(MarketplaceStore::unavailable);
            var discovered=store.resource(id,false);
            badges.findCurrent(Set.of(new AccountId(discovered.subject())));
            identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));
            var permission=new AuthorContext(new ActorId(actor),new SubjectId(discovered.subject()),discovered.type().publishCapability()).permission();
            boolean currentManager=identity.decide(permission).allowed();
            var resource=store.resource(id,true);
            if (!resource.subject().equals(discovered.subject()) || (!store.historicalParticipant(resource,actor) && !currentManager)) { throw MarketplaceStore.unavailable(); }
            var ids=store.jdbc.query("select id from marketplace_revision where resource_id=? order by created_at desc,id desc limit 20", (row,index) -> row.getObject(1,UUID.class),id);
            List<OwnRevision> revisions=new ArrayList<>();
            for (UUID revisionId:ids) {
                var revision=store.revision(resource,revisionId);
                var approval=moderation.findApprovalForUpdate(revision.reference());
                approval.ifPresent(value -> {
                    if (!value.reference().equals(revision.reference()) || !value.digest().equals(revision.digest())
                            || !ContentDigest.payload(revision.text(),revision.label(),revision.attachments()).equals(value.digest())) { throw MarketplaceStore.unavailable(); }
                });
                String label=approval.map(ModerationAccess.Approval::label).orElse("ADULT");
                boolean visible=identity.decide(PermissionRequest.self(new AccountId(actor),capability(label))).allowed()
                        && (!label.equals("ADULT") || resource.subject().equals(actor));
                String state=approval.map(ModerationAccess.Approval::state).orElse(revision.author().capability()==resource.type().publishCapability() ? "PREPARED_UNSUBMITTED" : "DRAFT_PENDING_REVIEW");
                revisions.add(new OwnRevision(revision.reference(),state,label,approval.map(ModerationAccess.Approval::reason).orElse("SUBMIT_REQUIRED"),
                        visible ? Optional.of(revision.details()) : Optional.empty(),revision.author(),revision.attachments().size(),revisionId.equals(resource.current()),revisionId.equals(resource.approved())));
            }
            return new OwnView(resource.id(),resource.subject(),resource.type(),resource.lifecycle(),resource.version(),currentManager,List.copyOf(revisions));
        });
    }

    public List<UUID> ownResources() {
        return transaction.execute(status -> {
            UUID actor=proof.actor().orElseThrow(MarketplaceStore::unavailable);
            identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));
            return store.jdbc.query("select distinct r.id from marketplace_resource r left join marketplace_revision v on v.resource_id=r.id where r.subject_id=? or r.creator_id=? or v.actor_id=? order by r.id limit 50", (row,index) -> row.getObject(1,UUID.class),actor,actor,actor);
        });
    }

    PublicView historicalInTransaction(ContentReference reference) {
        MarketplaceStore.requireTransaction();
        UUID actor=proof.actor().orElseThrow(MarketplaceStore::unavailable);
        var discovered=store.resource(reference.resourceId(),false);
        var expected=moderation.approval(reference);
        badges.findCurrent(Set.of(new AccountId(discovered.subject())));
        identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));
        audience(expected.label());
        boolean currentManager=identity.decide(new AuthorContext(new ActorId(actor),new SubjectId(discovered.subject()),discovered.type().publishCapability()).permission()).allowed();
        var resource=store.resource(discovered.id(),true);
        if (!resource.subject().equals(discovered.subject()) || (!store.historicalParticipant(resource,actor) && !currentManager)) { throw MarketplaceStore.unavailable(); }
        var revision=store.revision(resource,reference.revisionId());
        if (!revision.reference().equals(reference) || resource.type().profile()) { throw MarketplaceStore.unavailable(); }
        var approval=moderation.approvalForUpdate(reference);
        exactApproval(revision,approval);
        if (!expected.label().equals(approval.label())) { throw MarketplaceStore.unavailable(); }
        return new PublicView(reference,new AccountId(resource.subject()),resource.type(),revision.details(),approval.label(),revision.attachments(),Optional.empty());
    }

    void requireAttachment(FileReference file, FileAccess.Purpose purpose) {
        MarketplaceStore.requireTransaction();
        var discovered=store.resource(file.content().resourceId(),false);
        MarketplaceStore.Revision revision;
        if (purpose==FileAccess.Purpose.REVIEW) {
            UUID actor=proof.actor().orElseThrow(MarketplaceStore::unavailable);
            identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ADMIN_MODERATION_REVIEW));
            var resource=store.resource(discovered.id(),true);
            revision=store.revision(resource,file.content().revisionId());
            var approval=moderation.approvalForUpdate(file.content());
            if (!approval.digest().equals(revision.digest())) { throw MarketplaceStore.unavailable(); }
        } else {
            var visible=currentInTransaction(discovered.id(),Optional.empty());
            if (!visible.reference().equals(file.content())) { throw MarketplaceStore.unavailable(); }
            revision=store.revision(store.resource(discovered.id(),false),file.content().revisionId());
        }
        if (!revision.reference().equals(file.content()) || !revision.attachments().contains(file)) { throw MarketplaceStore.unavailable(); }
    }

    static void exactApproval(MarketplaceStore.Revision revision, ModerationAccess.Approval approval) {
        if (!revision.reference().equals(approval.reference()) || !"APPROVED".equals(approval.state())
                || !revision.digest().equals(approval.digest())
                || !ContentDigest.payload(revision.text(),revision.label(),revision.attachments()).equals(approval.digest())) { throw MarketplaceStore.unavailable(); }
    }
    private void audience(String label) {
        var actor=proof.actor();
        if (actor.isPresent()) { identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.orElseThrow()),capability(label))); }
        else if (!identity.decide(new PermissionRequest(Optional.empty(),Optional.empty(),capability(label),Optional.empty())).allowed()) { throw MarketplaceStore.unavailable(); }
    }
    static Capability capability(String label) { return switch(label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; default -> Capability.VIEW_ADULT; }; }

    record Selection(ContentReference reference, UUID subject, String digest, String label) {}
    public record PublicView(ContentReference reference, AccountId subject, PresenceType type, MarketplaceDetails details,
                             String label, List<FileReference> attachments, Optional<PublicIdentityBadge> badge) {
        public PublicView { attachments=List.copyOf(attachments); }
    }
    public record OwnRevision(ContentReference reference, String state, String label, String reason, Optional<MarketplaceDetails> details,
                              AuthorContext author, int fileCount, boolean current, boolean published) {}
    public record OwnView(UUID id, UUID subject, PresenceType type, PublicationLifecycle lifecycle, long version, boolean canPublish, List<OwnRevision> revisions) {
        public OwnRevision current() { return revisions.stream().filter(OwnRevision::current).findFirst().orElseThrow(MarketplaceStore::unavailable); }
    }
}
