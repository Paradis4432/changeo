package ar.changeo.moderation;

import ar.changeo.moderation.api.ContentOwner;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class DecisionService {
    private final ModerationStore store;
    private final Clock clock;
    private final ar.changeo.files.api.FileAccess files;
    DecisionService(ModerationStore store,Clock clock,ar.changeo.files.api.FileAccess files) { this.store=store;this.clock=clock;this.files=files; }
    UUID record(ModerationStore.Submission submission,ReviewResult result,UUID actor,ContentOwner lockedOwner) {
        if(result.action().equals("APPROVE")) { requireApprovable(store.input(submission),result.label()); }
        UUID decision=UUID.randomUUID();
        store.jdbc.update("insert into moderation_decision values(?,?,?,?,?,?,?,?,?,?)",decision,submission.id(),actor,result.action(),result.label(),result.reason(),"synthetic-v1",actor==null?"local-fixture-v1":"human-local","LOCAL_SYNTHETIC",Timestamp.from(clock.instant()));
        String state=switch(result.action()) { case "APPROVE"->"APPROVED";case "REJECT"->"REJECTED";case "RECALL"->"RECALLED";default->"MANUAL_REVIEW"; };
        store.jdbc.update("update moderation_submission set state=?,label=?,reason=?,decision_id=?,recalled=?,generation=generation+1,version=version+1 where id=?",state,result.label(),result.reason(),decision,result.action().equals("RECALL"),submission.id());
        if(!result.action().equals("APPROVE") && lockedOwner!=null) { lockedOwner.recall(submission.reference()); }
        var updated=store.submission(submission.id(),false);
        if(result.action().equals("APPROVE")) { store.task(updated,"ACTIVATE",decision); }
        store.audit(actor,updated,null,"DECISION_"+result.action());return decision;
    }
    private void requireApprovable(ReviewInput input,String label) {
        if(SyntheticPolicy.prohibited(input.text()) || SyntheticPolicy.fixture(input.text()).filter(fixture->rank(fixture.label())>rank(label)).isPresent()) { throw ModerationStore.unavailable(); }
        for(var attachment:input.attachments()) {
            var metadata=files.metadata(attachment);
            if(!metadata.safe() || metadata.reason().equals("PROHIBITED") || rank(metadata.reason())>rank(label)) { throw ModerationStore.unavailable(); }
        }
    }
    private static int rank(String label) { return switch(label) { case "GENERAL"->0;case "SENSITIVE"->1;case "ADULT"->2;default->0; }; }
}
