package ar.changeo.marketplace;

import ar.changeo.files.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class MarketplaceOwner implements ContentOwner {
    private final MarketplaceStore store;
    private final ObjectProvider<MarketplaceReader> readers;

    MarketplaceOwner(MarketplaceStore store, ObjectProvider<MarketplaceReader> readers) { this.store=store; this.readers=readers; }
    @Override public Set<SurfaceKind> kinds() { return Set.of(SurfaceKind.PROFILE_BIO,SurfaceKind.OFFER_BODY,SurfaceKind.REQUEST_BODY); }
    @Override public boolean owns(ContentReference reference) { return store.owns(reference); }

    @Override public ReviewInput lockRevision(AuthorContext author, ContentReference reference) {
        var resource=store.resource(reference.resourceId(),true);
        var revision=store.revision(resource,reference.revisionId());
        if (resource.type().surface()!=reference.ownerKind() || !revision.author().equals(author)
                || !resource.subject().equals(author.subject().value()) || author.jobContext().isPresent()
                || (author.capability()!=resource.type().draftCapability() && author.capability()!=resource.type().publishCapability())
                || !revision.text().equals(revision.details().reviewText(resource.type()))
                || !ContentDigest.payload(revision.text(),revision.label(),revision.attachments()).equals(revision.digest())) { throw MarketplaceStore.unavailable(); }
        return revision.input();
    }

    @Override public boolean current(ContentReference reference) {
        var resource=store.resource(reference.resourceId(),false);
        var revision=store.revision(resource,reference.revisionId());
        return resource.lifecycle()==PublicationLifecycle.OPEN && reference.revisionId().equals(resource.current())
                && revision.author().capability()==resource.type().publishCapability();
    }
    @Override public boolean reportable(ContentReference reference) {
        var resource=store.resource(reference.resourceId(),false);
        return resource.lifecycle()==PublicationLifecycle.OPEN && reference.revisionId().equals(resource.approved());
    }
    @Override public long activate(ContentReference reference) {
        if (!current(reference)) { throw MarketplaceStore.unavailable(); }
        var resource=store.resource(reference.resourceId(),false);
        if (reference.revisionId().equals(resource.approved())) { return resource.version(); }
        store.jdbc.update("update marketplace_resource set approved_revision=?,version=version+1 where id=?",reference.revisionId(),resource.id());
        return resource.version()+1;
    }
    @Override public void recall(ContentReference reference) {
        store.jdbc.update("update marketplace_resource set approved_revision=null,version=version+1 where id=? and approved_revision=?",reference.resourceId(),reference.revisionId());
    }
    @Override public void requireAttachment(FileReference reference, FileAccess.Purpose purpose) { readers.getObject().requireAttachment(reference,purpose); }
}
