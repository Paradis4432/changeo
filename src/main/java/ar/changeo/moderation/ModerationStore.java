package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import ar.changeo.files.api.FileReference;
import ar.changeo.identity.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class ModerationStore {
    final JdbcTemplate jdbc;
    private final Clock clock;
    ModerationStore(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    UUID barrier(ContentReference reference,boolean create) {
        requireTransaction();
        if(create) { jdbc.update("insert into moderation_reference values(?,?,?) on conflict(owner_kind,resource_id) do nothing",UUID.randomUUID(),reference.ownerKind().name(),reference.resourceId()); }
        var ids=jdbc.query("select id from moderation_reference where owner_kind=? and resource_id=? for update",(r,n)->r.getObject(1,UUID.class),reference.ownerKind().name(),reference.resourceId());
        if(ids.isEmpty()) { throw unavailable(); } return ids.getFirst();
    }
    void barrier(UUID id) { requireTransaction(); if(jdbc.queryForList("select id from moderation_reference where id=? for update",id).isEmpty()) { throw unavailable(); } }
    Optional<Submission> find(ContentReference reference,boolean lock) {
        var rows=jdbc.query("select s.*,p.reference_id,p.revision_id,p.actor_id,p.subject_id,p.capability,p.job_id,p.job_mode,p.digest,r.owner_kind,r.resource_id from moderation_submission s join moderation_snapshot p on p.id=s.id join moderation_reference r on r.id=p.reference_id where r.owner_kind=? and r.resource_id=? and p.revision_id=?"+(lock?" for update of s":""),this::submission,reference.ownerKind().name(),reference.resourceId(),reference.revisionId());
        return rows.stream().findFirst();
    }
    Submission submission(ContentReference reference,boolean lock) { return find(reference,lock).orElseThrow(ModerationStore::unavailable); }
    Submission submission(UUID id,boolean lock) {
        var rows=jdbc.query("select s.*,p.reference_id,p.revision_id,p.actor_id,p.subject_id,p.capability,p.job_id,p.job_mode,p.digest,r.owner_kind,r.resource_id from moderation_submission s join moderation_snapshot p on p.id=s.id join moderation_reference r on r.id=p.reference_id where s.id=?"+(lock?" for update of s":""),this::submission,id);
        if(rows.isEmpty()) { throw unavailable(); } return rows.getFirst();
    }
    private Submission submission(ResultSet r,int index) throws SQLException {
        UUID job=r.getObject("job_id",UUID.class);
        var author=new AuthorContext(new ActorId(r.getObject("actor_id",UUID.class)),new SubjectId(r.getObject("subject_id",UUID.class)),Capability.valueOf(r.getString("capability")),job==null?Optional.empty():Optional.of(new JobContext(job,JobContext.FulfillmentMode.valueOf(r.getString("job_mode")))));
        return new Submission(r.getObject("id",UUID.class),r.getObject("reference_id",UUID.class),new ContentReference(SurfaceKind.valueOf(r.getString("owner_kind")),r.getObject("resource_id",UUID.class),r.getObject("revision_id",UUID.class)),author,r.getString("digest"),r.getString("state"),r.getString("label"),r.getString("reason"),r.getBoolean("recalled"),r.getObject("decision_id",UUID.class),r.getLong("generation"),r.getLong("version"));
    }
    ReviewInput input(Submission submission) {
        return jdbc.queryForObject("select text,declared_label,digest from moderation_snapshot where id=?",(r,n)->new ReviewInput(submission.reference(),r.getString("text"),r.getString("declared_label"),r.getString("digest"),attachments(submission)),submission.id());
    }
    List<FileReference> attachments(Submission submission) {
        return jdbc.query("select file_id,digest from moderation_snapshot_attachment where snapshot_id=? order by file_id",(r,n)->new FileReference(r.getObject("file_id",UUID.class),submission.reference(),r.getString("digest")),submission.id());
    }
    Optional<UUID> receipt(UUID actor,UUID command,String operation,String digest) {
        var rows=jdbc.queryForList("select operation,digest,outcome_id from moderation_receipt where actor_id=? and command_id=?",actor,command);
        if(rows.isEmpty()) { return Optional.empty(); } var row=rows.getFirst();
        if(!operation.equals(row.get("operation")) || !digest.equals(row.get("digest"))) { throw new ModerationFailure("El identificador del comando ya está ligado a otro contenido"); }
        return Optional.of((UUID)row.get("outcome_id"));
    }
    void receipt(UUID actor,UUID command,String operation,String digest,UUID outcome) { jdbc.update("insert into moderation_receipt values(?,?,?,?,?)",actor,command,operation,digest,outcome); }
    UUID task(Submission submission,String kind,UUID decision) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into moderation_task(id,submission_id,reference_id,kind,generation,state,available_at,reason,decision_id) values(?,?,?,?,?,'PENDING',?,'QUEUED',?) on conflict(submission_id,kind,generation) do nothing",id,submission.id(),submission.barrier(),kind,submission.generation(),Timestamp.from(clock.instant()),decision);
        return jdbc.queryForObject("select id from moderation_task where submission_id=? and kind=? and generation=?",UUID.class,submission.id(),kind,submission.generation());
    }
    void audit(UUID actor,Submission submission,UUID caseId,String action) { jdbc.update("insert into moderation_audit values(?,?,?,?,?,?,?)",UUID.randomUUID(),actor,submission.barrier(),submission.id(),caseId,action,Timestamp.from(clock.instant())); }
    Optional<ModerationWorker.Claim> claim(String kind, int leaseSeconds) {
        requireTransaction();
        var rows=jdbc.queryForList("select * from moderation_task where (cast(? as varchar) is null or kind=?) and ((state in ('PENDING','RETRY_WAIT') and available_at<=?) or (state='CLAIMED' and lease_until<=?)) order by available_at,id for update skip locked limit 1",kind,kind,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        if(rows.isEmpty()) { return Optional.empty(); }
        var row=rows.getFirst(); UUID token=UUID.randomUUID(); UUID id=(UUID)row.get("id");
        int attempts=(Integer)row.get("attempts");
        jdbc.update("update moderation_task set state='CLAIMED',claim_token=?,lease_until=?,attempts=? where id=?",token,Timestamp.from(clock.instant().plusSeconds(leaseSeconds)),Math.min(3,attempts+1),id);
        return Optional.of(new ModerationWorker.Claim(id,(UUID)row.get("reference_id"),(UUID)row.get("submission_id"),(String)row.get("kind"),((Number)row.get("generation")).longValue(),token,(UUID)row.get("decision_id"),attempts>=3));
    }
    boolean live(ModerationWorker.Claim claim) {
        var rows=jdbc.queryForList("select claim_token,state,lease_until,submission_id,reference_id,kind,generation,decision_id from moderation_task where id=? for update",claim.id());
        if(rows.isEmpty()) { return false; } var row=rows.getFirst();
        return "CLAIMED".equals(row.get("state")) && claim.token().equals(row.get("claim_token")) && ((Timestamp)row.get("lease_until")).toInstant().isAfter(clock.instant())
                && claim.submission().equals(row.get("submission_id")) && claim.barrier().equals(row.get("reference_id")) && claim.kind().equals(row.get("kind"))
                && claim.generation()==((Number)row.get("generation")).longValue() && Objects.equals(claim.decision(),row.get("decision_id"));
    }
    void complete(ModerationWorker.Claim claim,String reason) { jdbc.update("update moderation_task set state='COMPLETE',reason=?,claim_token=null,lease_until=null where id=?",reason,claim.id()); }
    void preparationHold(Submission submission,ModerationWorker.Claim claim,String reason) {
        requireTransaction();
        complete(claim,reason);
        jdbc.update("update moderation_submission set state='MANUAL_REVIEW',label='ADULT',reason=?,generation=generation+1,version=version+1 where id=?",reason,submission.id());
        audit(null,submission,null,"PREPARATION_HOLD");
    }
    static void requireTransaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("Se requiere una transacción del propietario"); } }
    static ModerationFailure unavailable() { return new ModerationFailure("Contenido no disponible para este acceso"); }
    record Submission(UUID id,UUID barrier,ContentReference reference,AuthorContext author,String digest,String state,String label,String reason,boolean recalled,UUID decision,long generation,long version) {}
}
