package ar.changeo.moderation;

import ar.changeo.files.api.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="changeo.moderation-preparation",havingValue="openai-wire")
final class PreparationFilePolicy implements ReviewFilePolicy {
    private final ModerationStore store;
    private final OwnerProtection protection;

    PreparationFilePolicy(ModerationStore store, OwnerProtection protection) { this.store=store; this.protection=protection; }

    @Override public void requireCurrentReview(ReviewFileAccess.Request request) {
        ModerationStore.requireTransaction();
        var discovered=store.submission(request.content(),false);
        var claim=new ModerationWorker.Claim(request.task(),discovered.barrier(),discovered.id(),"REVIEW",request.generation(),request.token(),null,false);
        var input=authorize(claim);
        if(!input.attachments().equals(request.attachments())) { throw ModerationStore.unavailable(); }
    }

    ReviewInput authorize(ModerationWorker.Claim claim) {
        ModerationStore.requireTransaction();
        var discovered=store.submission(claim.submission(),false);
        var protectedRevision=protection.protect(discovered.author().permission(),discovered.author(),discovered.reference());
        store.barrier(discovered.barrier());
        var submission=store.submission(claim.submission(),true);
        var input=store.input(submission);
        ContentDigest.validate(input.text(),input.declaredLabel(),input.attachments());
        if(!claim.kind().equals("REVIEW") || claim.decision()!=null || claim.exhausted() || !store.live(claim)
                || !submission.barrier().equals(claim.barrier()) || submission.generation()!=claim.generation() || submission.recalled()
                || !submission.state().equals("PENDING") || submission.decision()!=null || !protectedRevision.owner().current(submission.reference())
                || !input.equals(protectedRevision.canonical()) || !submission.digest().equals(ContentDigest.payload(input.text(),input.declaredLabel(),input.attachments()))) {
            throw ModerationStore.unavailable();
        }
        return input;
    }
}
