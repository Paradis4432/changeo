package ar.changeo.moderation;

import ar.changeo.files.api.FileAccess;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseService {
    private static final Set<String> REASONS=Set.of("MISLABEL","SAFETY","FALSE_POSITIVE","OTHER");
    private final ModerationStore store;
    private final OwnerRegistry owners;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final Admission admission;
    private final DecisionService decisions;
    private final FileAccess files;
    private final Clock clock;
    CaseService(ModerationStore store,OwnerRegistry owners,IdentityAccess identity,SecurityProof proof,Admission admission,DecisionService decisions,FileAccess files,Clock clock) {
        this.store=store;this.owners=owners;this.identity=identity;this.proof=proof;this.admission=admission;this.decisions=decisions;this.files=files;this.clock=clock;
    }
    @Transactional public UUID open(UUID command,ContentReference ref,String kind,String reason,String note) {
        UUID actor=actor();identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));
        if(!Set.of("REPORT","APPEAL").contains(kind) || !REASONS.contains(reason) || note==null || note.length()>2000) { throw new ModerationFailure("Motivo o texto de caso inválido"); }
        String digest=hash(ref+":"+kind+":"+reason+":"+note);
        var replay=store.receipt(actor,command,"CASE",digest);if(replay.isPresent()) { return replay.orElseThrow(); }
        admission.actor(actor,"case");
        var discovered=store.submission(ref,false);var owner=owners.require(ref);owner.lockRevision(discovered.author(),ref);
        store.barrier(ref,false);var submission=store.submission(ref,true);
        if(kind.equals("APPEAL")) {
            if(!submission.author().actor().value().equals(actor) && !submission.author().subject().value().equals(actor)) { throw ModerationStore.unavailable(); }
        } else if(!owner.reportable(ref) || submission.recalled() || !submission.state().equals("APPROVED") || !identity.decide(PermissionRequest.self(new AccountId(actor),ContentReader.capability(submission.label()))).allowed()) { throw ModerationStore.unavailable(); }
        UUID id=UUID.randomUUID();
        store.jdbc.update("insert into moderation_case(id,reference_id,submission_id,reporter_id,kind,reason,note,state,created_at) values(?,?,?,?,?,?,?,'OPEN',?)",id,submission.barrier(),submission.id(),actor,kind,reason,note,Timestamp.from(clock.instant()));
        store.audit(actor,submission,id,"CASE_OPENED");store.receipt(actor,command,"CASE",digest,id);return id;
    }
    @Transactional public UUID reviewCase(UUID command,ContentReference ref) {
        UUID actor=actor();guard(actor,false);
        String digest=hash(ref.toString());var replay=store.receipt(actor,command,"REVIEW_CASE",digest);if(replay.isPresent()) { return replay.orElseThrow(); }
        admission.actor(actor,"operator");var discovered=store.submission(ref,false);owners.require(ref).lockRevision(discovered.author(),ref);
        store.barrier(ref,false);var submission=store.submission(ref,true);UUID id=UUID.randomUUID();
        store.jdbc.update("insert into moderation_case(id,reference_id,submission_id,reporter_id,kind,reason,note,state,owner_id,created_at) values(?,?,?,?,'REPORT','SAFETY','','OWNED',?,?)",id,submission.barrier(),submission.id(),actor,actor,Timestamp.from(clock.instant()));
        store.audit(actor,submission,id,"REVIEW_CASE_OPENED");store.receipt(actor,command,"REVIEW_CASE",digest,id);return id;
    }
    @Transactional public void act(UUID command,UUID caseId,long version,String action,String label,String reason) {
        UUID actor=actor();guard(actor,false);var discovered=caseRow(caseId,false);var preliminary=store.submission((UUID)discovered.get("submission_id"),false);
        var owner=owners.require(preliminary.reference());var canonical=owner.lockRevision(preliminary.author(),preliminary.reference());
        store.barrier(preliminary.barrier());var submission=store.submission(preliminary.id(),true);var row=caseRow(caseId,true);
        String digest=hash(caseId+":"+version+":"+action+":"+label+":"+reason);
        if(store.receipt(actor,command,"CASE_ACTION",digest).isPresent()) { return; }
        admission.actor(actor,"operator");
        if(((Number)row.get("version")).longValue()!=version || "RESOLVED".equals(row.get("state")) || !REASONS.contains(reason)) { throw new ModerationFailure("El caso cambió o el motivo es inválido"); }
        if(action.equals("TAKE")) {
            if(row.get("owner_id")!=null && !actor.equals(row.get("owner_id"))) { throw new ModerationFailure("Caso asignado a otro operador"); }
            store.jdbc.update("update moderation_case set state='OWNED',owner_id=?,version=version+1 where id=?",actor,caseId);
        } else {
            if(!actor.equals(row.get("owner_id"))) { throw new ModerationFailure("Tomá el caso antes de decidir"); }
            switch(action) {
                case "APPROVE","CORRECT_LABEL","HOLD","REJECT","RECALL" -> {
                    if(Set.of("APPROVE","CORRECT_LABEL").contains(action)) {
                        if(actor.equals(submission.author().actor().value()) || actor.equals(submission.author().subject().value())) { throw new ModerationFailure("La aprobación requiere revisión independiente"); }
                        if(!canonical.digest().equals(submission.digest()) || !canonical.attachments().equals(store.attachments(submission)) || SyntheticPolicy.prohibited(canonical.text())) { throw new ModerationFailure("Contenido prohibido o vínculo inmutable cambiado"); }
                        for(var file:canonical.attachments()) { var metadata=files.metadata(file);if(!metadata.safe() || metadata.reason().equals("PROHIBITED")) { throw new ModerationFailure("Adjunto no aprobable en este ejercicio"); } }
                        if(submission.recalled() && !row.get("kind").equals("APPEAL")) { throw new ModerationFailure("Un retiro requiere una apelación explícita"); }
                    }
                    if(!Set.of("GENERAL","SENSITIVE","ADULT").contains(label)) { throw new ModerationFailure("Etiqueta inválida"); }
                    String decisionAction=action.equals("CORRECT_LABEL")?"APPROVE":action;
                    String decisionReason=action.equals("CORRECT_LABEL")?"LABEL_CORRECTION":row.get("kind").equals("APPEAL")?"APPEAL":"MANUAL";
                    decisions.record(submission,new ReviewResult(decisionAction,label,decisionReason),actor,owner);
                    store.jdbc.update("update moderation_case set state='RESOLVED',version=version+1 where id=?",caseId);
                }
                case "WARNING","STRIKE" -> {
                    store.jdbc.update("insert into moderation_evidence(id,reference_id,submission_id,kind,actor_id,reason,created_at) values(?,?,?,?,?,?,?)",UUID.randomUUID(),submission.barrier(),submission.id(),action,actor,reason,Timestamp.from(clock.instant()));
                    store.jdbc.update("update moderation_case set version=version+1 where id=?",caseId);
                }
                case "RESOLVE" -> store.jdbc.update("update moderation_case set state='RESOLVED',version=version+1 where id=?",caseId);
                default -> throw new ModerationFailure("Acción inválida");
            }
        }
        store.audit(actor,submission,caseId,"CASE_"+action);store.receipt(actor,command,"CASE_ACTION",digest,caseId);
    }
    @Transactional public void withdraw(UUID command,UUID evidenceId,long version) {
        UUID actor=actor();guard(actor,false);var preliminary=store.jdbc.queryForMap("select * from moderation_evidence where id=?",evidenceId);var discovered=store.submission((UUID)preliminary.get("submission_id"),false);
        owners.require(discovered.reference()).lockRevision(discovered.author(),discovered.reference());store.barrier(discovered.barrier());var submission=store.submission(discovered.id(),true);
        var row=store.jdbc.queryForMap("select * from moderation_evidence where id=? for update",evidenceId);String digest=hash(evidenceId+":"+version);
        if(store.receipt(actor,command,"WITHDRAW",digest).isPresent()) { return; }
        admission.actor(actor,"operator");if(((Number)row.get("version")).longValue()!=version || (Boolean)row.get("withdrawn")) { throw new ModerationFailure("La evidencia cambió"); }
        store.jdbc.update("update moderation_evidence set withdrawn=true,version=version+1 where id=?",evidenceId);store.audit(actor,submission,null,"EVIDENCE_WITHDRAWN");store.receipt(actor,command,"WITHDRAW",digest,evidenceId);
    }
    @Transactional public List<Map<String,Object>> queue(boolean audit) { guard(actor(),audit);return store.jdbc.queryForList("select c.id,r.resource_id,p.revision_id,c.kind,c.reason,c.state,c.owner_id,c.version"+(audit?"":",c.note")+" from moderation_case c join moderation_snapshot p on p.id=c.submission_id join moderation_reference r on r.id=c.reference_id order by c.created_at desc,c.id limit 50"); }
    @Transactional public List<Map<String,Object>> reviewQueue() { guard(actor(),false);return store.jdbc.queryForList("select p.revision_id,r.resource_id,r.owner_kind as kind,s.state,s.reason,s.label from moderation_submission s join moderation_snapshot p on p.id=s.id join moderation_reference r on r.id=p.reference_id where s.state in ('MANUAL_REVIEW','FAILED','AWAITING_ELIGIBILITY','PENDING') order by s.id limit 50"); }
    @Transactional public List<Map<String,Object>> evidenceMetadata(boolean audit) { guard(actor(),audit);return store.jdbc.queryForList("select e.id,r.resource_id,p.revision_id,e.kind,e.reason,e.withdrawn,e.version from moderation_evidence e join moderation_snapshot p on p.id=e.submission_id join moderation_reference r on r.id=e.reference_id order by e.created_at desc,e.id limit 50"); }
    @Transactional public List<Map<String,Object>> ownCases() { UUID actor=actor();identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));return store.jdbc.queryForList("select c.id,r.resource_id,p.revision_id,c.kind,c.reason,c.state,c.version from moderation_case c join moderation_snapshot p on p.id=c.submission_id join moderation_reference r on r.id=c.reference_id where c.reporter_id=? order by c.created_at desc,c.id limit 50",actor); }
    private Map<String,Object> caseRow(UUID id,boolean lock) { var rows=store.jdbc.queryForList("select * from moderation_case where id=?"+(lock?" for update":""),id);if(rows.isEmpty()) { throw ModerationStore.unavailable(); }return rows.getFirst(); }
    private void guard(UUID actor,boolean audit) { identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),audit?Capability.ADMIN_MODERATION_AUDIT:Capability.ADMIN_MODERATION_REVIEW)); }
    private UUID actor() { return proof.actor().orElseThrow(ModerationStore::unavailable); }
    private static String hash(String value) { return ContentDigest.bytes(value.getBytes(StandardCharsets.UTF_8)); }
}
