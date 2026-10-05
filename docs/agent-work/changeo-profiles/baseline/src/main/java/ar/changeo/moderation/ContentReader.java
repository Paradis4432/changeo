package ar.changeo.moderation;

import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentReader {
    private final WorkbenchStore workbench;
    private final ModerationStore moderation;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    ContentReader(WorkbenchStore workbench,ModerationStore moderation,IdentityAccess identity,SecurityProof proof) { this.workbench=workbench;this.moderation=moderation;this.identity=identity;this.proof=proof; }
    @Transactional public ContentView retrieve(UUID resourceId) {
        var discovered=workbench.resource(resourceId,false);
        if(discovered.approved()==null) { throw ModerationStore.unavailable(); }
        var expected=moderation.submission(discovered.reference(discovered.approved()),false);
        guardAudience(expected.label(),discovered.audience());
        var resource=workbench.resource(resourceId,true);
        if(!Objects.equals(discovered.approved(),resource.approved())) { throw ModerationStore.unavailable(); }
        moderation.barrier(resource.reference(resource.approved()),false);
        var submission=moderation.submission(resource.reference(resource.approved()),true);
        checkOrdinary(resource,submission,expected.label());return view(resource,submission);
    }
    @Transactional public ContentView evidence(ContentReference reference) {
        identity.requireForUpdate(operator(Capability.ADMIN_MODERATION_REVIEW));
        var resource=workbench.resource(reference.resourceId(),true);moderation.barrier(reference,false);var submission=moderation.submission(reference,true);
        exact(resource,submission,reference);return view(resource,submission);
    }
    @Transactional public AuthorView own(UUID resourceId) {
        UUID actor=proof.actor().orElseThrow(ModerationStore::unavailable);
        identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));
        var resource=workbench.resource(resourceId,true);
        if(!resource.actor().equals(actor) && !resource.subject().equals(actor)) { throw ModerationStore.unavailable(); }
        var ids=workbench.jdbc.query("select id from workbench_revision where resource_id=? order by created_at desc,id limit 20",(r,n)->r.getObject(1,UUID.class),resourceId);
        List<AuthorRevision> revisions=new ArrayList<>();
        for(UUID id:ids) {
            var found=moderation.find(resource.reference(id),false);
            String state=found.map(ModerationStore.Submission::state).orElse("DRAFT_PENDING_REVIEW");String label=found.map(ModerationStore.Submission::label).orElse("ADULT");String reason=found.map(ModerationStore.Submission::reason).orElse("SUBMIT_REQUIRED");
            boolean visible=(!label.equals("ADULT") || resource.actor().equals(resource.subject())) && identity.decide(PermissionRequest.self(new AccountId(actor),capability(label))).allowed();
            revisions.add(new AuthorRevision(resource.reference(id),state,label,reason,visible?workbench.input(resource,id).text():null,id.equals(resource.current()),workbench.attachments(resource.reference(id))));
        }
        return new AuthorView(resource.id(),resource.version(),resource.audience(),List.copyOf(revisions));
    }
    public void requireWorkbenchAttachment(FileReference file,FileAccess.Purpose purpose) {
        ModerationStore.requireTransaction();
        if(purpose==FileAccess.Purpose.REVIEW) {
            identity.requireForUpdate(operator(Capability.ADMIN_MODERATION_REVIEW));
            var resource=workbench.resource(file.content().resourceId(),true);moderation.barrier(file.content(),false);var submission=moderation.submission(file.content(),true);
            exact(resource,submission,file.content());attachment(resource,file);return;
        }
        var discovered=workbench.resource(file.content().resourceId(),false);
        if(!file.content().revisionId().equals(discovered.approved())) { throw ModerationStore.unavailable(); }
        var expected=moderation.submission(file.content(),false);guardAudience(expected.label(),discovered.audience());
        var resource=workbench.resource(file.content().resourceId(),true);moderation.barrier(file.content(),false);var submission=moderation.submission(file.content(),true);
        exact(resource,submission,file.content());checkOrdinary(resource,submission,expected.label());attachment(resource,file);
    }
    private void attachment(WorkbenchStore.Resource resource,FileReference file) { if(!workbench.attachments(file.content()).contains(file) || resource.kind()!=file.content().ownerKind()) { throw ModerationStore.unavailable(); } }
    private void guardAudience(String label,String audience) {
        var actor=proof.actor();
        if(audience.equals("PRIVATE")) {
            identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.orElseThrow(ModerationStore::unavailable)),Capability.SEND_MARKETPLACE_MESSAGE));
        }
        if(actor.isPresent()) { identity.requireForUpdate(PermissionRequest.self(new AccountId(actor.orElseThrow()),capability(label))); }
        else if(!identity.decide(new PermissionRequest(Optional.empty(),Optional.empty(),capability(label),Optional.empty())).allowed()) { throw ModerationStore.unavailable(); }
    }
    private void checkOrdinary(WorkbenchStore.Resource resource,ModerationStore.Submission submission,String expectedLabel) {
        if(!submission.reference().revisionId().equals(resource.approved()) || submission.recalled() || !submission.state().equals("APPROVED") || !submission.label().equals(expectedLabel)) { throw ModerationStore.unavailable(); }
        if(resource.audience().equals("PRIVATE") && (proof.actor().isEmpty() || !workbench.member(resource,proof.actor().orElseThrow()))) { throw ModerationStore.unavailable(); }
        exact(resource,submission,submission.reference());
    }
    private void exact(WorkbenchStore.Resource resource,ModerationStore.Submission submission,ContentReference reference) {
        if(resource.kind()!=reference.ownerKind() || !resource.id().equals(reference.resourceId()) || !resource.author().equals(submission.author())) { throw ModerationStore.unavailable(); }
        var input=workbench.input(resource,reference.revisionId());
        if(!input.digest().equals(submission.digest()) || !input.attachments().equals(moderation.attachments(submission))) { throw ModerationStore.unavailable(); }
    }
    private ContentView view(WorkbenchStore.Resource resource,ModerationStore.Submission submission) { var input=workbench.input(resource,submission.reference().revisionId());return new ContentView(input.reference(),input.text(),submission.label(),submission.state(),submission.reason(),input.attachments()); }
    private PermissionRequest operator(Capability capability) { return PermissionRequest.self(new AccountId(proof.actor().orElseThrow(ModerationStore::unavailable)),capability); }
    static Capability capability(String label) { return switch(label) { case "GENERAL"->Capability.BROWSE_GENERAL;case "SENSITIVE"->Capability.VIEW_SENSITIVE;default->Capability.VIEW_ADULT; }; }
    public record ContentView(ContentReference reference,String text,String label,String state,String reason,List<FileReference> attachments) {}
    public record AuthorRevision(ContentReference reference,String state,String label,String reason,String text,boolean current,List<FileReference> attachments) {}
    public record AuthorView(UUID id,long version,String audience,List<AuthorRevision> revisions) {}
}
