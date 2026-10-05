package ar.changeo.moderation;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class DecisionService {
    private final ModerationStore store;
    private final Clock clock;
    DecisionService(ModerationStore store,Clock clock) { this.store=store; this.clock=clock; }
    UUID record(ModerationStore.Resource resource, ModerationStore.Submission submission, ReviewResult result, UUID actor) {
        UUID decision=UUID.randomUUID();
        store.jdbc.update("insert into moderation_decision values(?,?,?,?,?,?,?,?,?,?)",decision,submission.id(),actor,result.action(),result.label(),result.reason(),"synthetic-v1",actor==null ? "local-fixture-v1" : "human-local","LOCAL_SYNTHETIC",Timestamp.from(clock.instant()));
        String state=switch(result.action()) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; case "RECALL" -> "RECALLED"; default -> "MANUAL_REVIEW"; };
        boolean recalled=result.action().equals("RECALL");
        store.jdbc.update("update moderation_submission set state=?,label=?,reason=?,decision_id=?,recalled=?,generation=generation+1,version=version+1 where id=?",state,result.label(),result.reason(),decision,recalled,submission.id());
        if(!result.action().equals("APPROVE") && submission.id().equals(resource.approved())) { store.jdbc.update("update moderation_resource set approved_revision=null,version=version+1 where id=?",resource.id()); }
        if(result.action().equals("APPROVE")) { store.task(store.submission(submission.id(),false),"ACTIVATE",decision); }
        store.audit(actor,resource,submission.id(),null,"DECISION_"+result.action());
        return decision;
    }
}
