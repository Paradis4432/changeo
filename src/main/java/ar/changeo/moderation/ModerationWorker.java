package ar.changeo.moderation;

import ar.changeo.files.api.*;
import ar.changeo.identity.api.*;
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
    private final OwnerRegistry owners;
    private final FileAccess files;
    private final ReviewEngine engine;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final boolean enabled;
    public ModerationWorker(ModerationStore store,DecisionService decisions,IdentityAccess identity,OwnerRegistry owners,FileAccess files,ReviewEngine engine,
                            PlatformTransactionManager transactions,Clock clock,@Value("${changeo.synthetic-moderation:false}") boolean enabled) {
        this.store=store; this.decisions=decisions; this.identity=identity; this.owners=owners; this.files=files; this.engine=engine;
        this.transaction=new TransactionTemplate(transactions); this.clock=clock; this.enabled=enabled;
    }
    public Optional<Claim> claim() {
        standalone();
        if(!enabled) { return Optional.empty(); }
        return transaction.execute(status -> {
            var rows=store.jdbc.queryForList("select * from moderation_task where (state in ('PENDING','RETRY_WAIT') and available_at<=?) or (state='CLAIMED' and lease_until<=?) order by available_at,id for update skip locked limit 1",Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
            if(rows.isEmpty()) { return Optional.empty(); }
            var row=rows.getFirst(); UUID token=UUID.randomUUID(); UUID id=(UUID)row.get("id");
            int attempts=(Integer)row.get("attempts"); boolean exhausted=attempts>=3;
            store.jdbc.update("update moderation_task set state='CLAIMED',claim_token=?,lease_until=?,attempts=? where id=?",token,Timestamp.from(clock.instant().plusSeconds(30)),Math.min(3,attempts+1),id);
            return Optional.of(new Claim(id,(UUID)row.get("reference_id"),(UUID)row.get("submission_id"),(String)row.get("kind"),((Number)row.get("generation")).longValue(),token,(UUID)row.get("decision_id"),exhausted));
        });
    }
    public boolean processOne() {
        var next=claim(); if(next.isEmpty()) { return false; } process(next.orElseThrow()); return true;
    }
    public void process(Claim claim) {
        standalone();
        if(!enabled) { return; }
        if(claim.exhausted()) { failure(claim,"EXHAUSTED",true); return; }
        if(claim.kind().equals("ACTIVATE")) {
            try { activate(claim); }
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
            identity.requireForUpdate(discovered.author().permission());
            var owner=owners.require(discovered.reference());
            var canonical=owner.lockRevision(discovered.author(),discovered.reference());
            store.barrier(claim.barrier());var submission=store.submission(claim.submission(),true);
            if(!live(claim)) { return; }
            if(!owner.current(submission.reference()) || !canonical.digest().equals(submission.digest()) || !canonical.attachments().equals(store.attachments(submission)) || submission.recalled() || !submission.state().equals("APPROVED") || !Objects.equals(submission.decision(),claim.decision()) || submission.generation()!=claim.generation()) { complete(claim,"SUPERSEDED");return; }
            if(submission.label().equals("ADULT") && (!identity.decide(PermissionRequest.self(new AccountId(submission.author().subject().value()),Capability.VIEW_ADULT)).allowed() || !identity.decide(PermissionRequest.self(new AccountId(submission.author().actor().value()),Capability.VIEW_ADULT)).allowed())) { throw new ar.changeo.identity.IdentityFailure("Adult eligibility required"); }
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
            if(submission.generation()!=claim.generation() || submission.recalled()) { complete(claim,"SUPERSEDED"); return; }
            int attempts=store.jdbc.queryForObject("select attempts from moderation_task where id=?",Integer.class,claim.id());
            boolean stop=terminal || attempts>=3;
            store.jdbc.update("update moderation_task set state=?,reason=?,claim_token=null,lease_until=null,available_at=? where id=?",stop ? "FAILED" : "RETRY_WAIT",reason,Timestamp.from(clock.instant().plusSeconds(5*attempts)),claim.id());
            String state=stop ? reason.equals("ELIGIBILITY") ? "AWAITING_ELIGIBILITY" : "FAILED" : claim.kind().equals("ACTIVATE") ? "APPROVED" : "PENDING";
            store.jdbc.update("update moderation_submission set state=?,reason=?,version=version+1 where id=?",state,reason,submission.id());
            store.audit(null,submission,null,stop ? "TASK_FAILED" : "TASK_RETRY");
        });
    }
    private boolean live(Claim claim) {
        var rows=store.jdbc.queryForList("select claim_token,state,lease_until from moderation_task where id=? for update",claim.id());
        if(rows.isEmpty()) { return false; } var row=rows.getFirst();
        return "CLAIMED".equals(row.get("state")) && claim.token().equals(row.get("claim_token")) && ((Timestamp)row.get("lease_until")).toInstant().isAfter(clock.instant());
    }
    private void complete(Claim claim,String reason) { store.jdbc.update("update moderation_task set state='COMPLETE',reason=?,claim_token=null,lease_until=null where id=?",reason,claim.id()); }
    private static int rank(String label) { return switch(label) { case "GENERAL" -> 0; case "SENSITIVE" -> 1; default -> 2; }; }
    private static void standalone() { if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("El trabajador requiere una transacción independiente"); } }
    public record Claim(UUID id,UUID barrier,UUID submission,String kind,long generation,UUID token,UUID decision,boolean exhausted) {}
}
