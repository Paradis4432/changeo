package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import ar.changeo.files.api.FileReference;
import ar.changeo.identity.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class ModerationStore {
    final JdbcTemplate jdbc;
    private final Clock clock;
    ModerationStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    Resource resource(UUID id, boolean lock) {
        requireTransaction();
        var rows = jdbc.query("select * from moderation_resource where id=?" + (lock ? " for update" : ""), (r, n) -> new Resource(r.getObject("id", UUID.class), SurfaceKind.valueOf(r.getString("kind")), r.getObject("actor_id", UUID.class), r.getObject("subject_id", UUID.class), Capability.valueOf(r.getString("capability")), r.getString("audience"), r.getObject("current_revision", UUID.class), r.getObject("approved_revision", UUID.class), r.getLong("version")), id);
        if (rows.isEmpty()) { throw unavailable(); } return rows.getFirst();
    }
    Submission submission(UUID id, boolean lock) {
        var rows = jdbc.query("select * from moderation_submission where id=?" + (lock ? " for update" : ""), (r, n) -> new Submission(r.getObject("id",UUID.class),r.getObject("resource_id",UUID.class),r.getString("digest"),r.getString("state"),r.getString("label"),r.getString("reason"),r.getBoolean("recalled"),r.getObject("decision_id",UUID.class),r.getLong("generation"),r.getLong("version")),id);
        if (rows.isEmpty()) { throw unavailable(); } return rows.getFirst();
    }
    ReviewInput input(Resource resource, UUID revision) {
        return jdbc.queryForObject("select * from moderation_revision where id=? and resource_id=?", (r,n) -> new ReviewInput(resource.reference(revision), r.getString("text"),r.getString("declared_label"),r.getString("digest"),attachments(resource.reference(revision))),revision,resource.id());
    }
    List<FileReference> attachments(ContentReference ref) {
        return jdbc.query("select file_id,digest from moderation_attachment where revision_id=? order by file_id", (r,n) -> new FileReference(r.getObject("file_id",UUID.class),ref,r.getString("digest")),ref.revisionId());
    }
    boolean member(Resource resource, UUID actor) {
        return resource.actor().equals(actor) || resource.subject().equals(actor) || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from moderation_member where resource_id=? and account_id=?)",Boolean.class,resource.id(),actor));
    }
    void owner(Resource resource, UUID actor) {
        if (!resource.actor().equals(actor)) { throw unavailable(); }
    }
    Optional<UUID> receipt(UUID actor, UUID command, String operation, String digest) {
        var rows = jdbc.queryForList("select operation,digest,outcome_id from moderation_receipt where actor_id=? and command_id=?",actor,command);
        if (rows.isEmpty()) { return Optional.empty(); }
        var row=rows.getFirst();
        if (!operation.equals(row.get("operation")) || !digest.equals(row.get("digest"))) { throw new ModerationFailure("El identificador del comando ya está ligado a otro contenido"); }
        return Optional.of((UUID)row.get("outcome_id"));
    }
    void receipt(UUID actor, UUID command, String operation, String digest, UUID outcome) { jdbc.update("insert into moderation_receipt values(?,?,?,?,?)",actor,command,operation,digest,outcome); }
    UUID task(Submission submission, String kind, UUID decision) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into moderation_task(id,submission_id,resource_id,kind,generation,state,available_at,reason,decision_id) values(?,?,?,?,?,'PENDING',?,'QUEUED',?) on conflict(submission_id,kind,generation) do nothing",id,submission.id(),submission.resource(),kind,submission.generation(),Timestamp.from(clock.instant()),decision);
        return jdbc.queryForObject("select id from moderation_task where submission_id=? and kind=? and generation=?",UUID.class,submission.id(),kind,submission.generation());
    }
    void audit(UUID actor, Resource resource, UUID revision, UUID caseId, String action) { jdbc.update("insert into moderation_audit values(?,?,?,?,?,?,?)",UUID.randomUUID(),actor,resource.id(),revision,caseId,action,Timestamp.from(clock.instant())); }
    static void requireTransaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("Se requiere una transacción del propietario"); } }
    static ModerationFailure unavailable() { return new ModerationFailure("Contenido no disponible para este acceso"); }
    record Resource(UUID id, SurfaceKind kind, UUID actor, UUID subject, Capability capability, String audience, UUID current, UUID approved, long version) {
        ContentReference reference(UUID revision) { return new ContentReference(kind,id,revision); }
        AuthorContext author() { return new AuthorContext(new ActorId(actor),new SubjectId(subject),capability); }
    }
    record Submission(UUID id, UUID resource, String digest, String state, String label, String reason, boolean recalled, UUID decision, long generation, long version) {}
}
