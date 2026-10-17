package ar.changeo.marketplace;

import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.listings.api.*;
import ar.changeo.moderation.ContentDigest;
import ar.changeo.moderation.api.*;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ListingResponseService implements ListingResponseAccess {
    private final MarketplaceStore store;
    private final ModerationAccess moderation;
    private final IdentityAccess identity;
    private final SecurityProof proof;

    ListingResponseService(MarketplaceStore store, ModerationAccess moderation, IdentityAccess identity, SecurityProof proof) {
        this.store = store;
        this.moderation = moderation;
        this.identity = identity;
        this.proof = proof;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ListingResponse prepareResponse(ContentReference expectedReference) {
        var resource = store.resource(expectedReference.resourceId(), true);
        var revision = store.revision(resource, expectedReference.revisionId());
        if (resource.type().profile() || !revision.reference().equals(expectedReference)
                || revision.author().capability() != resource.type().publishCapability()
                || !revision.author().subject().value().equals(resource.subject())
                || !revision.text().equals(revision.details().reviewText(resource.type()))
                || !ContentDigest.payload(revision.text(), revision.label(), revision.attachments()).equals(revision.digest())) {
            throw MarketplaceStore.unavailable();
        }
        return new ListingResponse(new ListingSnapshot(expectedReference, new AccountId(resource.subject()),
                resource.type(), revision.details(), revision.label()), revision.author().actor(), revision.digest(), resource.version());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ListingResponse requireOpen(ContentReference expectedReference) {
        ListingResponse prepared = prepareResponse(expectedReference);
        var resource = store.resource(expectedReference.resourceId(), false);
        if (resource.lifecycle() != PublicationLifecycle.OPEN || !Objects.equals(resource.approved(), expectedReference.revisionId())) {
            throw MarketplaceStore.unavailable();
        }
        var revision = store.revision(resource, expectedReference.revisionId());
        var approval = moderation.approvalForUpdate(expectedReference);
        MarketplaceReader.exactApproval(revision, approval);
        var actor = proof.actor();
        var audience = new PermissionRequest(actor.map(ActorId::new), actor.map(SubjectId::new),
                MarketplaceReader.capability(approval.label()), java.util.Optional.empty());
        if (!identity.decide(audience).allowed()) { throw MarketplaceStore.unavailable(); }
        return new ListingResponse(new ListingSnapshot(expectedReference, prepared.listing().subject(),
                prepared.listing().type(), prepared.listing().details(), approval.label()), prepared.publisher(), prepared.digest(), prepared.version());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void selectRequestProvider(ListingSelection selection) {
        var open = requireOpen(selection.request());
        if (open.listing().type() != PresenceType.REQUEST
                || !open.listing().subject().value().equals(selection.customer().value())
                || selection.customer().value().equals(selection.provider().value())
                || proof.actor().isEmpty()
                || !identity.decide(new AuthorContext(selection.selectingCustomer(), selection.customer(), Capability.NEW_REQUEST).permission()).allowed()) {
            throw MarketplaceStore.unavailable();
        }
        var rows = store.jdbc.queryForList("select * from marketplace_request_selection where listing_id=?", selection.request().resourceId());
        if (!rows.isEmpty()) {
            var previous = rows.getFirst();
            if (!selection.jobId().equals(previous.get("job_id"))
                    || !selection.request().revisionId().equals(previous.get("revision_id"))
                    || !selection.customer().value().equals(previous.get("customer_id"))
                    || !selection.provider().value().equals(previous.get("provider_id"))) {
                throw MarketplaceStore.unavailable();
            }
            return;
        }
        store.jdbc.update("insert into marketplace_request_selection values(?,?,?,?,?,?)", selection.request().resourceId(),
                selection.request().revisionId(), selection.jobId(), selection.customer().value(),
                selection.provider().value(), selection.selectingCustomer().value());
        store.jdbc.update("update marketplace_resource set lifecycle='CLOSED',version=version+1 where id=?", selection.request().resourceId());
    }
}
