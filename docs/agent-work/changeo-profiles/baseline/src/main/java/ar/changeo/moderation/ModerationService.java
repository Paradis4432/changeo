package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ModerationService implements ModerationAccess {
    private final ModerationStore store;
    private final OwnerRegistry owners;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final FileAccess files;
    private final Admission admission;
    private final Clock clock;
    private final TransactionTemplate participating;
    public ModerationService(ModerationStore store,OwnerRegistry owners,IdentityAccess identity,SecurityProof proof,FileAccess files,Admission admission,Clock clock,PlatformTransactionManager transactions) {
        this.store=store;this.owners=owners;this.identity=identity;this.proof=proof;this.files=files;this.admission=admission;this.clock=clock;
        this.participating=new TransactionTemplate(transactions);this.participating.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }
    @Override public Approval submitForReview(AuthorContext author,ContentReference reference,String text,String declaredLabel,List<FileReference> attachments) {
        ModerationStore.requireTransaction();
        return participating.execute(status -> {
            if(!proof.actor().filter(author.actor().value()::equals).isPresent()) { throw ModerationStore.unavailable(); }
            var owner=owners.require(reference);
            identity.requireForUpdate(author.permission());
            var canonical=owner.lockRevision(author,reference);
            ContentDigest.validate(text,declaredLabel,attachments);
            String digest=ContentDigest.payload(text,declaredLabel,attachments);
            if(!owner.current(reference) || !canonical.reference().equals(reference) || !canonical.text().equals(text) || !canonical.declaredLabel().equals(declaredLabel) || !canonical.digest().equals(digest) || !new HashSet<>(canonical.attachments()).equals(new HashSet<>(attachments))) { throw ModerationStore.unavailable(); }
            for(var file:attachments) { if(!file.content().equals(reference)) { throw ModerationStore.unavailable(); } files.metadata(file); }
            UUID barrier=store.barrier(reference,true);
            var existing=store.find(reference,true);
            if(existing.isPresent()) {
                var submission=existing.orElseThrow();
                if(!submission.digest().equals(digest) || !submission.author().equals(author)) { throw ModerationStore.unavailable(); }
                return approval(submission);
            }
            admission.actor(author.actor().value(),"review");
            UUID id=UUID.randomUUID();var job=author.jobContext();
            store.jdbc.update("insert into moderation_snapshot values(?,?,?,?,?,?,?,?,?,?,?,?)",id,barrier,reference.revisionId(),author.actor().value(),author.subject().value(),author.capability().name(),job.map(JobContext::jobId).orElse(null),job.map(j->j.fulfillmentMode().name()).orElse(null),text,declaredLabel,digest,Timestamp.from(clock.instant()));
            for(var file:attachments) { store.jdbc.update("insert into moderation_snapshot_attachment values(?,?,?)",id,file.id(),file.digest()); }
            store.jdbc.update("insert into moderation_submission(id,state,label,reason) values(?,'PENDING','ADULT','QUEUED')",id);
            var submission=store.submission(id,false);store.task(submission,"REVIEW",null);store.audit(author.actor().value(),submission,null,"SUBMITTED");
            return approval(submission);
        });
    }
    @Override @Transactional(readOnly=true) public Approval approval(ContentReference reference) { return approval(store.submission(reference,false)); }
    @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY) public Approval approvalForUpdate(ContentReference reference) {
        owners.require(reference);store.barrier(reference,false);return approval(store.submission(reference,true));
    }
    @Transactional public void retry(UUID command,ContentReference reference,long generation) {
        var discovered=store.submission(reference,false);
        if(!proof.actor().filter(discovered.author().actor().value()::equals).isPresent()) { throw ModerationStore.unavailable(); }
        identity.requireForUpdate(discovered.author().permission());
        var owner=owners.require(reference);var canonical=owner.lockRevision(discovered.author(),reference);
        store.barrier(reference,false);var submission=store.submission(reference,true);
        String digest=ContentDigest.bytes((reference+":"+generation).getBytes(StandardCharsets.UTF_8));
        if(store.receipt(discovered.author().actor().value(),command,"RETRY",digest).isPresent()) { return; }
        admission.actor(discovered.author().actor().value(),"retry");
        if(!owner.current(reference) || submission.recalled() || submission.generation()!=generation || !canonical.digest().equals(submission.digest()) || !Set.of("FAILED","AWAITING_ELIGIBILITY").contains(submission.state())) { throw new ModerationFailure("Esta revisión no admite un reintento técnico nuevo"); }
        String kind=submission.decision()!=null && "APPROVE".equals(store.jdbc.queryForObject("select action from moderation_decision where id=?",String.class,submission.decision()))?"ACTIVATE":"REVIEW";
        store.jdbc.update("update moderation_submission set generation=generation+1,state=?,reason='QUEUED',version=version+1 where id=?",kind.equals("ACTIVATE")?"APPROVED":"PENDING",submission.id());
        var updated=store.submission(submission.id(),false);store.task(updated,kind,kind.equals("ACTIVATE")?updated.decision():null);
        store.receipt(discovered.author().actor().value(),command,"RETRY",digest,updated.id());store.audit(discovered.author().actor().value(),updated,null,"RETRY_"+kind);
    }
    @Transactional(readOnly=true) public long generation(ContentReference reference) { return store.find(reference,false).map(ModerationStore.Submission::generation).orElse(0L); }
    static Approval approval(ModerationStore.Submission submission) { return new Approval(submission.reference(),submission.digest(),submission.state(),submission.label(),submission.reason()); }
}
