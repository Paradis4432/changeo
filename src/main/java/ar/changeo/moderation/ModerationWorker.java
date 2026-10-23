package ar.changeo.moderation;

import ar.changeo.files.api.*;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ModerationAccess;
import ar.changeo.moderation.api.ContentOwner.ActivationLifecycle;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class ModerationWorker {
    private final ModerationStore store;
    private final DecisionService decisions;
    private final IdentityAccess identity;
    private final OwnerProtection protection;
    private final org.springframework.beans.factory.ObjectProvider<ModerationAccess> moderation;
    private final FileAccess files;
    private final ReviewEngine engine;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final boolean enabled;
    public ModerationWorker(ModerationStore store,DecisionService decisions,IdentityAccess identity,OwnerProtection protection,org.springframework.beans.factory.ObjectProvider<ModerationAccess> moderation,FileAccess files,ReviewEngine engine,
                            PlatformTransactionManager transactions,Clock clock,@Value("${changeo.synthetic-moderation:false}") boolean enabled) {
        this.store=store; this.decisions=decisions; this.identity=identity; this.protection=protection; this.moderation=moderation; this.files=files; this.engine=engine;
        this.transaction=new TransactionTemplate(transactions); this.clock=clock; this.enabled=enabled;
    }
    public Optional<Claim> claim() {
        standalone();
        if(!enabled) { return Optional.empty(); }
        return transaction.execute(status -> store.claim(null,30));
    }
    public boolean processOne() {
        var next=claim(); if(next.isEmpty()) { return false; } process(next.orElseThrow()); return true;
    }
    public void process(Claim claim) {
        standalone();
        if(!enabled) { return; }
        if(claim.exhausted()) { failure(claim,"EXHAUSTED",true); return; }
        if(claim.kind().equals("ACTIVATE")) {
            try { activateWithRetries(claim); }
            catch(ar.changeo.identity.IdentityFailure denied) { failure(claim,"ELIGIBILITY",true); }
            catch(ModerationFailure failure) { failure(claim,"BINDING",true); }
            catch(org.springframework.dao.TransientDataAccessException failure) { failure(claim,"OUTAGE",false); }
            return;
        }
        ReviewInput input=transaction.execute(status -> {
            store.barrier(claim.barrier());var submission=store.submission(claim.submission(),true);
            return store.input(submission);
        });
        ReviewResult result;
        try {
            result=ReviewResult.validate(input,engine.review(input));
            for(var attachment:input.attachments()) {
                var metadata=files.metadata(attachment);
                if(!metadata.safe()) { result=new ReviewResult("HOLD","ADULT","UNINSPECTABLE"); break; }
                if(metadata.reason().equals("PROHIBITED")) { result=new ReviewResult("REJECT","ADULT","PROHIBITED"); break; }
                if(metadata.reason().equals("DOUBT")) { result=new ReviewResult("HOLD","ADULT","DOUBT"); break; }
                if(result.action().equals("APPROVE") && rank(metadata.reason())>rank(result.label())) { result=new ReviewResult("APPROVE",metadata.reason(),"CLEAR"); }
            }
            if(result.action().equals("APPROVE") && rank(result.label())>rank(input.declaredLabel())) { result=new ReviewResult("HOLD","ADULT","DOUBT"); }
        } catch(RuntimeException outage) { failure(claim,"OUTAGE",false); return; }
        finishReview(claim,result);
    }
    private void activateWithRetries(Claim claim) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try { activate(claim); return; }
            catch (OwnerProtection.ActivationDiscoveryChanged changed) {
                if (attempt == 2) { deferActivation(claim); }
            }
        }
    }

    private void deferActivation(Claim claim) {
        transaction.executeWithoutResult(status -> {
            store.barrier(claim.barrier());
            var submission = store.submission(claim.submission(), true);
            if (!submission.barrier().equals(claim.barrier())) { throw ModerationStore.unavailable(); }
            if (!live(claim)) { return; }
            if (submission.generation() != claim.generation() || submission.recalled() || !Objects.equals(submission.decision(), claim.decision())
                    || !submission.state().equals("APPROVED")) { complete(claim, "SUPERSEDED"); return; }
            int attempts = store.jdbc.queryForObject("select attempts from moderation_task where id=?", Integer.class, claim.id());
            if (claim.exhausted() || attempts < 1) { throw ModerationStore.unavailable(); }
            store.jdbc.update("update moderation_task set state='RETRY_WAIT',reason='CHANGED',attempts=attempts-1,claim_token=null,lease_until=null,available_at=? where id=?",
                    Timestamp.from(clock.instant().plusSeconds(5)), claim.id());
        });
    }
    void finishReview(Claim claim,ReviewResult result) {
        transaction.executeWithoutResult(status -> {
            store.barrier(claim.barrier());var submission=store.submission(claim.submission(),true);
            if(!live(claim)) { return; }
            if(submission.recalled() || submission.generation()!=claim.generation()) { complete(claim,"SUPERSEDED"); return; }
            decisions.record(submission,result,null,null); complete(claim,"REVIEWED");
        });
    }
    private void activate(Claim claim) {
        transaction.executeWithoutResult(status -> {
            var discovered=store.submission(claim.submission(),false);
            var activation=protection.protectActivation(discovered.author(),discovered.reference());
            var protectedRevision=activation.revision();
            var owner=protectedRevision.owner();
            var canonical=protectedRevision.canonical();
            if (activation.lifecycle() == ActivationLifecycle.DELIVERY) { owner.prepareActivation(discovered.reference()); }
            var approvals=moderation.getObject().approvalsForUpdate(protectedRevision.deliveryReferences());
            for(var dependency:protectedRevision.dependencies()) {
                if(!"APPROVED".equals(approvals.get(dependency).state())) { throw ModerationStore.unavailable(); }
            }
            var submission=store.submission(claim.submission(),false);
            if(!submission.barrier().equals(claim.barrier()) || !submission.reference().equals(discovered.reference())) { throw ModerationStore.unavailable(); }
            if(!live(claim)) { return; }
            if(!owner.current(submission.reference()) || !canonical.digest().equals(submission.digest()) || !canonical.attachments().equals(store.attachments(submission)) || submission.recalled() || !submission.state().equals("APPROVED") || !Objects.equals(submission.decision(),claim.decision()) || submission.generation()!=claim.generation()) { complete(claim,"SUPERSEDED");return; }
            if(activation.lifecycle() == ActivationLifecycle.DELIVERY && submission.label().equals("ADULT") && (!identity.decide(PermissionRequest.self(new AccountId(submission.author().subject().value()),Capability.VIEW_ADULT)).allowed() || !identity.decide(PermissionRequest.self(new AccountId(submission.author().actor().value()),Capability.VIEW_ADULT)).allowed())) { throw new ar.changeo.identity.IdentityFailure("Adult eligibility required"); }
            for(var attachment:store.attachments(submission)) { if(!files.metadata(attachment).safe()) { complete(claim,"UNINSPECTABLE");return; } }
            long version=owner.activate(submission.reference());
            store.jdbc.update("insert into moderation_signal values(?,?,?,?,?,?,?) on conflict(decision_id) do nothing",UUID.randomUUID(),submission.barrier(),submission.id(),submission.decision(),"ACTIVATED",version,Timestamp.from(clock.instant()));
            complete(claim,"ACTIVATED");
        });
    }
    private void failure(Claim claim,String reason,boolean terminal) {
        transaction.executeWithoutResult(status -> {
            store.barrier(claim.barrier());var submission=store.submission(claim.submission(),true);
            if(!live(claim)) { return; }
            if(submission.generation()!=claim.generation() || submission.recalled() || !Objects.equals(submission.decision(),claim.decision())) { complete(claim,"SUPERSEDED"); return; }
            int attempts=store.jdbc.queryForObject("select attempts from moderation_task where id=?",Integer.class,claim.id());
            boolean stop=terminal || attempts>=3;
            store.jdbc.update("update moderation_task set state=?,reason=?,claim_token=null,lease_until=null,available_at=? where id=?",stop ? "FAILED" : "RETRY_WAIT",reason,Timestamp.from(clock.instant().plusSeconds(5*attempts)),claim.id());
            String state=stop ? reason.equals("ELIGIBILITY") ? "AWAITING_ELIGIBILITY" : "FAILED" : claim.kind().equals("ACTIVATE") ? "APPROVED" : "PENDING";
            store.jdbc.update("update moderation_submission set state=?,reason=?,version=version+1 where id=?",state,reason,submission.id());
            store.audit(null,submission,null,stop ? "TASK_FAILED" : "TASK_RETRY");
        });
    }
    private boolean live(Claim claim) {
        return store.live(claim);
    }
    private void complete(Claim claim,String reason) { store.complete(claim,reason); }
    private static int rank(String label) { return switch(label) { case "GENERAL" -> 0; case "SENSITIVE" -> 1; default -> 2; }; }
    private static void standalone() { if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("El trabajador requiere una transacción independiente"); } }
    public record Claim(UUID id,UUID barrier,UUID submission,String kind,long generation,UUID token,UUID decision,boolean exhausted) {}
}
