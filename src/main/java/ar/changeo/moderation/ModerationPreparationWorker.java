package ar.changeo.moderation;

import ar.changeo.files.api.ReviewFileAccess;
import ar.changeo.moderation.ModerationWorker.Claim;
import ar.changeo.moderation.ReviewObservation.Wire;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

public final class ModerationPreparationWorker {
    private final ModerationStore store;
    private final PreparationFilePolicy policy;
    private final ReviewFileAccess files;
    private final OpenAiReviewClient client;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Semaphore pipelines=new Semaphore(2);

    ModerationPreparationWorker(ModerationStore store,PreparationFilePolicy policy,ReviewFileAccess files,OpenAiReviewClient client,
                                PlatformTransactionManager transactions,Clock clock) {
        this.store=store; this.policy=policy; this.files=files; this.client=client; this.clock=clock;
        this.transaction=new TransactionTemplate(transactions); this.transaction.setTimeout(10);
    }

    public boolean processOne() {
        standalone();
        if(!pipelines.tryAcquire()) { return false; }
        try {
            var next=transaction.execute(status->{ deadlines(); return store.claim("REVIEW",90); });
            if(next.isEmpty()) { return false; }
            Claim claim=next.orElseThrow();
            if(claim.exhausted()) { localFailure(claim,"PREPARATION_EXHAUSTED",true); return true; }
            try { process(claim); }
            catch(ar.changeo.identity.IdentityFailure denied) { localFailure(claim,"PREPARATION_ELIGIBILITY",false); }
            catch(ModerationFailure | IllegalArgumentException denied) { localFailure(claim,"PREPARATION_UNINSPECTABLE",false); }
            catch(TransientDataAccessException unavailable) { localFailure(claim,"PREPARATION_OUTAGE",true); }
            return true;
        } finally { pipelines.release(); }
    }

    private void process(Claim claim) {
        ReviewInput input=transaction.execute(status->{ deadlines(); return policy.authorize(claim); });
        var request=new ReviewFileAccess.Request(input.reference(),claim.id(),claim.token(),claim.generation(),input.attachments());
        var material=files.materialize(request);
        for(var wire:Wire.values()) {
            if(Thread.currentThread().isInterrupted()) { localFailure(claim,"PREPARATION_INTERRUPTED",true); return; }
            if(!stage(claim,input,material,wire)) { return; }
        }
    }

    private boolean stage(Claim claim,ReviewInput input,ReviewFileAccess.Material material,Wire wire) {
        var encoded=client.encode(wire,input,material);
        Optional<UUID> dispatch=authorize(claim,input,encoded);
        if(dispatch.isEmpty()) { return false; }
        return observe(claim,dispatch.orElseThrow(),encoded,client.send(encoded));
    }

    private Optional<UUID> authorize(Claim claim,ReviewInput expected,OpenAiReviewClient.Encoded encoded) {
        return transaction.execute(status->{
            deadlines();
            if(!expected.equals(policy.authorize(claim))) { throw ModerationStore.unavailable(); }
            int attempt=store.jdbc.queryForObject("select attempts from moderation_task where id=?",Integer.class,claim.id());
            if(attempt<1 || attempt>3) { throw ModerationStore.unavailable(); }
            UUID id=UUID.randomUUID();
            int inserted=store.jdbc.update("insert into moderation_preparation_dispatch(id,task_id,submission_id,reference_id,revision_id,generation,attempt_ordinal,claim_fingerprint,content_digest,manifest,manifest_digest,request_bytes,request_digest,wire_kind,requested_model,schema_version,prompt_version,draft_policy_version,source,endpoint_kind,dispatched_at) values(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,'preparation-wire-v1','untrusted-content-v1','draft-observation-v1','LOCAL_PROTOCOL_FIXTURE','LITERAL_LOOPBACK',?) on conflict(task_id,attempt_ordinal,wire_kind) do nothing",
                    id,claim.id(),claim.submission(),claim.barrier(),expected.reference().revisionId(),claim.generation(),attempt,fingerprint(claim),expected.digest(),encoded.manifest(),encoded.manifestHash(),encoded.size(),encoded.hash(),encoded.wire().name(),encoded.wire().model(),Timestamp.from(clock.instant()));
            return inserted==1?Optional.of(id):Optional.empty();
        });
    }

    private boolean observe(Claim claim,UUID dispatch,OpenAiReviewClient.Encoded encoded,ReviewObservation observation) {
        return transaction.execute(status->{
            deadlines(); store.barrier(claim.barrier());
            var submission=store.submission(claim.submission(),true);
            var rows=store.jdbc.queryForList("select * from moderation_preparation_dispatch where id=?",dispatch);
            if(rows.size()!=1) { throw ModerationStore.unavailable(); }
            var binding=rows.getFirst();
            if(!claim.id().equals(binding.get("task_id")) || !claim.submission().equals(binding.get("submission_id")) || !claim.barrier().equals(binding.get("reference_id"))
                    || !submission.reference().revisionId().equals(binding.get("revision_id")) || claim.generation()!=((Number)binding.get("generation")).longValue()
                    || !fingerprint(claim).equals(binding.get("claim_fingerprint")) || !submission.digest().equals(binding.get("content_digest"))
                    || !encoded.hash().equals(binding.get("request_digest")) || !encoded.manifestHash().equals(binding.get("manifest_digest"))
                    || encoded.size()!=((Number)binding.get("request_bytes")).intValue() || !encoded.wire().name().equals(binding.get("wire_kind"))) { throw ModerationStore.unavailable(); }
            boolean live=store.live(claim) && current(submission,claim);
            boolean finalStage=encoded.wire()==Wire.RESPONSES || observation.outcome()!=ReviewObservation.Outcome.VALID;
            String disposition=live && finalStage && !observation.retryable()?"APPLIED_TO_HOLD":"HISTORICAL_ONLY";
            String normalized=OpenAiReviewClient.json(observation.normalized());
            if(normalized.getBytes(StandardCharsets.UTF_8).length>16384) { throw new IllegalStateException("Preparation observation bound violated"); }
            int inserted=store.jdbc.update("insert into moderation_preparation_observation(dispatch_id,outcome,reason,http_status,response_id,returned_model,response_digest,normalized,disposition,received_at) values(?,?,?,?,?,?,?,?::jsonb,?,?) on conflict(dispatch_id) do nothing",
                    dispatch,observation.outcome().name(),observation.reason().name(),observation.httpStatus(),observation.responseId(),observation.returnedModel(),observation.responseHash(),normalized,disposition,Timestamp.from(clock.instant()));
            if(inserted==0 || !live) { return false; }
            if(!finalStage) { return true; }
            if(observation.retryable()) { retry(submission,claim,"PREPARATION_UNKNOWN"); }
            else { store.preparationHold(submission,claim,observation.outcome()==ReviewObservation.Outcome.VALID?"PREPARATION_ONLY":"PREPARATION_"+observation.outcome().name()); }
            return false;
        });
    }

    private void localFailure(Claim claim,String reason,boolean retryable) {
        transaction.executeWithoutResult(status->{
            deadlines(); store.barrier(claim.barrier()); var submission=store.submission(claim.submission(),true);
            if(!store.live(claim) || !current(submission,claim)) { return; }
            if(retryable) { retry(submission,claim,reason); }
            else { store.preparationHold(submission,claim,reason); }
        });
    }
    private void retry(ModerationStore.Submission submission,Claim claim,String reason) {
        int attempts=store.jdbc.queryForObject("select attempts from moderation_task where id=?",Integer.class,claim.id());
        boolean exhausted=claim.exhausted() || attempts>=3;
        store.jdbc.update("update moderation_task set state=?,reason=?,claim_token=null,lease_until=null,available_at=? where id=?",exhausted?"FAILED":"RETRY_WAIT",reason,Timestamp.from(clock.instant().plusSeconds(5L*attempts)),claim.id());
        store.jdbc.update("update moderation_submission set state=?,reason=?,version=version+1 where id=?",exhausted?"FAILED":"PENDING",reason,submission.id());
        store.audit(null,submission,null,exhausted?"PREPARATION_FAILED":"PREPARATION_RETRY");
    }
    private static boolean current(ModerationStore.Submission submission,Claim claim) {
        return submission.barrier().equals(claim.barrier()) && submission.generation()==claim.generation() && !submission.recalled()
                && submission.state().equals("PENDING") && submission.decision()==null;
    }
    private void deadlines() { store.jdbc.execute("set local lock_timeout = '2s'"); store.jdbc.execute("set local statement_timeout = '5s'"); }
    private static String fingerprint(Claim claim) { return ContentDigest.bytes(claim.token().toString().getBytes(StandardCharsets.US_ASCII)); }
    private static void standalone() { if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("Preparation requires independent transaction"); } }
}
