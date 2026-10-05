package ar.changeo.moderation;

import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentReader implements AttachmentAccessPolicy {
    private final ModerationStore store;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    ContentReader(ModerationStore store,IdentityAccess identity,SecurityProof proof) { this.store=store; this.identity=identity; this.proof=proof; }
    @Transactional public ContentView retrieve(UUID resourceId) {
        var discovered=store.resource(resourceId,false);
        if(discovered.approved()==null) { throw ModerationStore.unavailable(); }
        var expected=store.submission(discovered.approved(),false);
        guardAudience(expected.label());
        var resource=store.resource(resourceId,true);
        if(!Objects.equals(discovered.approved(),resource.approved())) { throw ModerationStore.unavailable(); }
        var submission=store.submission(resource.approved(),true);
        checkOrdinary(resource,submission,expected.label());
        return view(resource,submission);
    }
    @Transactional public ContentView evidence(ContentReference reference) {
        identity.requireForUpdate(operator(Capability.ADMIN_MODERATION_REVIEW));
        var resource=store.resource(reference.resourceId(),true); var submission=store.submission(reference.revisionId(),true);
        exact(resource,submission,reference);
        return view(resource,submission);
    }
    @Transactional public AuthorView own(UUID resourceId) {
        UUID actor=proof.actor().orElseThrow(ModerationStore::unavailable);
        identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));
        var resource=store.resource(resourceId,true);
        if(!resource.actor().equals(actor) && !resource.subject().equals(actor)) { throw ModerationStore.unavailable(); }
        var submissions=store.jdbc.query("select id from moderation_revision where resource_id=? order by created_at desc,id limit 20",(r,n)->r.getObject(1,UUID.class),resourceId);
        List<AuthorRevision> revisions=new ArrayList<>();
        for(UUID id:submissions) {
            var rows=store.jdbc.queryForList("select state,label,reason from moderation_submission where id=?",id);
            String state=rows.isEmpty()?"DRAFT_PENDING_REVIEW":(String)rows.getFirst().get("state");
            String label=rows.isEmpty()?"ADULT":(String)rows.getFirst().get("label");
            String reason=rows.isEmpty()?"SUBMIT_REQUIRED":(String)rows.getFirst().get("reason");
            boolean visible=identity.decide(PermissionRequest.self(new AccountId(actor),capability(label))).allowed();
            String text=visible?store.input(resource,id).text():null;
            revisions.add(new AuthorRevision(resource.reference(id),state,label,reason,text,id.equals(resource.current()),store.attachments(resource.reference(id))));
        }
        return new AuthorView(resource.id(),resource.version(),resource.audience(),List.copyOf(revisions));
    }
    @Override public void requireCurrentAccess(FileReference file,FileAccess.Purpose purpose) {
        ModerationStore.requireTransaction();
        if(purpose==FileAccess.Purpose.REVIEW) {
            identity.requireForUpdate(operator(Capability.ADMIN_MODERATION_REVIEW));
            var resource=store.resource(file.content().resourceId(),true); var submission=store.submission(file.content().revisionId(),true);
            exact(resource,submission,file.content());
            attachment(resource,file);
            return;
        }
        var discovered=store.resource(file.content().resourceId(),false);
        if(!file.content().revisionId().equals(discovered.approved())) { throw ModerationStore.unavailable(); }
        var expected=store.submission(file.content().revisionId(),false);
        guardAudience(expected.label());
        var resource=store.resource(file.content().resourceId(),true); var submission=store.submission(file.content().revisionId(),true);
        exact(resource,submission,file.content()); checkOrdinary(resource,submission,expected.label()); attachment(resource,file);
    }
    private void attachment(ModerationStore.Resource resource,FileReference file) {
        if(!store.attachments(resource.reference(file.content().revisionId())).contains(file)) { throw ModerationStore.unavailable(); }
    }
    private void guardAudience(String label) {
        var actor=proof.actor();
        if(actor.isPresent()) { identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.orElseThrow()),capability(label))); }
        else if(!identity.decide(new PermissionRequest(Optional.empty(),Optional.empty(),capability(label),Optional.empty())).allowed()) { throw ModerationStore.unavailable(); }
    }
    private void checkOrdinary(ModerationStore.Resource resource,ModerationStore.Submission submission,String expectedLabel) {
        if(!submission.id().equals(resource.approved()) || submission.recalled() || !submission.state().equals("APPROVED") || !submission.label().equals(expectedLabel)) { throw ModerationStore.unavailable(); }
        if(resource.audience().equals("PRIVATE") && (proof.actor().isEmpty() || !store.member(resource,proof.actor().orElseThrow()))) { throw ModerationStore.unavailable(); }
    }
    private void exact(ModerationStore.Resource resource,ModerationStore.Submission submission,ContentReference reference) {
        if(resource.kind()!=reference.ownerKind() || !resource.id().equals(submission.resource())) { throw ModerationStore.unavailable(); }
        var input=store.input(resource,submission.id());
        if(!input.digest().equals(submission.digest())) { throw ModerationStore.unavailable(); }
    }
    private ContentView view(ModerationStore.Resource resource,ModerationStore.Submission submission) {
        var input=store.input(resource,submission.id());
        return new ContentView(input.reference(),input.text(),submission.label(),submission.state(),submission.reason(),input.attachments());
    }
    private PermissionRequest operator(Capability capability) { return PermissionRequest.self(new AccountId(proof.actor().orElseThrow(ModerationStore::unavailable)),capability); }
    static Capability capability(String label) { return switch(label) { case "GENERAL" -> Capability.BROWSE_GENERAL; case "SENSITIVE" -> Capability.VIEW_SENSITIVE; default -> Capability.VIEW_ADULT; }; }
    public record ContentView(ContentReference reference,String text,String label,String state,String reason,List<FileReference> attachments) {}
    public record AuthorRevision(ContentReference reference,String state,String label,String reason,String text,boolean current,List<FileReference> attachments) {}
    public record AuthorView(UUID id,long version,String audience,List<AuthorRevision> revisions) {}
}
