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
    static void requireTransaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("Se requiere una transacción del propietario"); } }
    static ModerationFailure unavailable() { return new ModerationFailure("Contenido no disponible para este acceso"); }
    record Submission(UUID id,UUID barrier,ContentReference reference,AuthorContext author,String digest,String state,String label,String reason,boolean recalled,UUID decision,long generation,long version) {}
}
