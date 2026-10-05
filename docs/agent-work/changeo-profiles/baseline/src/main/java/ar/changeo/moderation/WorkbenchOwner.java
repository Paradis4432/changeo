package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import ar.changeo.files.api.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class WorkbenchOwner implements ContentOwner {
    private final WorkbenchStore store;
    private final ar.changeo.identity.SecurityProof proof;
    private final ar.changeo.identity.api.IdentityAccess identity;
    private final ObjectProvider<ContentReader> reader;
    WorkbenchOwner(WorkbenchStore store,ObjectProvider<ContentReader> reader,ar.changeo.identity.SecurityProof proof,ar.changeo.identity.api.IdentityAccess identity) { this.store=store; this.reader=reader; this.proof=proof; this.identity=identity; }
    @Override public Set<SurfaceKind> kinds() { return Set.of(SurfaceKind.REQUEST_BODY,SurfaceKind.PRIVATE_MESSAGE); }
    @Override public ReviewInput lockRevision(AuthorContext author,ContentReference reference) {
        var resource=store.resource(reference.resourceId(),true);
        if(resource.kind()!=reference.ownerKind() || !resource.author().equals(author) || !store.member(resource,author.actor().value())) { throw ModerationStore.unavailable(); }
        var input=store.input(resource,reference.revisionId());
        if(!ContentDigest.payload(input.text(),input.declaredLabel(),input.attachments()).equals(input.digest())) { throw ModerationStore.unavailable(); }
        return input;
    }
    @Override public boolean current(ContentReference reference) { return reference.revisionId().equals(store.resource(reference.resourceId(),false).current()); }
    @Override public boolean reportable(ContentReference reference) {
        var resource=store.resource(reference.resourceId(),false);
        return reference.revisionId().equals(resource.approved()) && (resource.audience().equals("PUBLIC") || proof.actor().filter(id->store.member(resource,id) && identity.decide(ar.changeo.identity.api.PermissionRequest.self(new ar.changeo.identity.api.AccountId(id),ar.changeo.identity.api.Capability.SEND_MARKETPLACE_MESSAGE)).allowed()).isPresent());
    }
    @Override public long activate(ContentReference reference) {
        var resource=store.resource(reference.resourceId(),false);
        if(!reference.revisionId().equals(resource.current())) { throw ModerationStore.unavailable(); }
        if(reference.revisionId().equals(resource.approved())) { return resource.version(); }
        store.jdbc.update("update workbench_resource set approved_revision=?,version=version+1 where id=?",reference.revisionId(),resource.id()); return resource.version()+1;
    }
    @Override public void recall(ContentReference reference) { store.jdbc.update("update workbench_resource set approved_revision=null,version=version+1 where id=? and approved_revision=?",reference.resourceId(),reference.revisionId()); }
    @Override public void requireAttachment(FileReference reference,FileAccess.Purpose purpose) { reader.getObject().requireWorkbenchAttachment(reference,purpose); }
}
