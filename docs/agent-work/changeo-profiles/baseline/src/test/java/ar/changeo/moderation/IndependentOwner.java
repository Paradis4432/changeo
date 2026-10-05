package ar.changeo.moderation;

import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class IndependentOwner implements ContentOwner {
    private final JdbcTemplate jdbc;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final ModerationStore moderation;
    IndependentOwner(JdbcTemplate jdbc,IdentityAccess identity,SecurityProof proof,ModerationStore moderation) { this.jdbc=jdbc;this.identity=identity;this.proof=proof;this.moderation=moderation; }
    void initialize() { jdbc.execute("create table if not exists fixture_canonical(id uuid primary key,revision uuid not null,actor uuid not null,text varchar(32768) not null,digest char(64) not null,file_id uuid,file_digest char(64),approved uuid,version bigint not null default 0)"); }
    void save(AuthorContext author,ContentReference ref,String text,FileReference file) {
        var input=new ReviewInput(ref,text,"GENERAL",ContentDigest.payload(text,"GENERAL",List.of(file)),List.of(file));
        jdbc.update("insert into fixture_canonical(id,revision,actor,text,digest,file_id,file_digest) values(?,?,?,?,?,?,?)",ref.resourceId(),ref.revisionId(),author.actor().value(),text,input.digest(),file.id(),file.digest());
    }
    @Override public Set<SurfaceKind> kinds() { return Set.of(SurfaceKind.PROFILE_BIO); }
    @Override public ReviewInput lockRevision(AuthorContext author,ContentReference ref) {
        ModerationStore.requireTransaction();var row=jdbc.queryForMap("select * from fixture_canonical where id=? for update",ref.resourceId());
        if(ref.ownerKind()!=SurfaceKind.PROFILE_BIO || !ref.revisionId().equals(row.get("revision")) || !author.actor().value().equals(row.get("actor")) || !author.subject().value().equals(row.get("actor")) || author.capability()!=Capability.NEW_REQUEST || author.jobContext().isPresent()) { throw ModerationStore.unavailable(); }
        var file=new FileReference((UUID)row.get("file_id"),ref,(String)row.get("file_digest"));return new ReviewInput(ref,(String)row.get("text"),"GENERAL",(String)row.get("digest"),List.of(file));
    }
    @Override public boolean current(ContentReference ref) { return ref.revisionId().equals(jdbc.queryForObject("select revision from fixture_canonical where id=?",UUID.class,ref.resourceId())); }
    @Override public boolean reportable(ContentReference ref) { return ref.revisionId().equals(jdbc.queryForObject("select approved from fixture_canonical where id=?",UUID.class,ref.resourceId())); }
    @Override public long activate(ContentReference ref) { jdbc.update("update fixture_canonical set approved=?,version=version+1 where id=?",ref.revisionId(),ref.resourceId());return jdbc.queryForObject("select version from fixture_canonical where id=?",Long.class,ref.resourceId()); }
    @Override public void recall(ContentReference ref) { jdbc.update("update fixture_canonical set approved=null,version=version+1 where id=? and approved=?",ref.resourceId(),ref.revisionId()); }
    String read(ContentReference ref) {
        UUID actor=proof.actor().orElseThrow();identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.BROWSE_GENERAL));
        UUID author=jdbc.queryForObject("select actor from fixture_canonical where id=?",UUID.class,ref.resourceId());
        var input=lockRevision(new AuthorContext(new ActorId(author),new SubjectId(author),Capability.NEW_REQUEST),ref);
        moderation.barrier(ref,false);var decision=moderation.submission(ref,true);
        if(!reportable(ref) || !decision.state().equals("APPROVED") || decision.recalled() || !decision.digest().equals(input.digest())) { throw ModerationStore.unavailable(); }
        return input.text();
    }
    @Override public void requireAttachment(FileReference file,FileAccess.Purpose purpose) {
        if(purpose!=FileAccess.Purpose.ORDINARY) { throw ModerationStore.unavailable(); }
        read(file.content());var row=jdbc.queryForMap("select file_id,file_digest from fixture_canonical where id=?",file.content().resourceId());
        if(!file.id().equals(row.get("file_id")) || !file.digest().equals(row.get("file_digest"))) { throw ModerationStore.unavailable(); }
    }
}
