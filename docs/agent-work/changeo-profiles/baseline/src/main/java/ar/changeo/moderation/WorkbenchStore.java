package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import ar.changeo.files.api.FileReference;
import ar.changeo.identity.api.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class WorkbenchStore {
    final JdbcTemplate jdbc;
    WorkbenchStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    Resource resource(UUID id,boolean lock) {
        ModerationStore.requireTransaction();
        var rows=jdbc.query("select * from workbench_resource where id=?"+(lock?" for update":""),(r,n)->new Resource(r.getObject("id",UUID.class),SurfaceKind.valueOf(r.getString("kind")),r.getObject("actor_id",UUID.class),r.getObject("subject_id",UUID.class),Capability.valueOf(r.getString("capability")),r.getString("audience"),r.getObject("current_revision",UUID.class),r.getObject("approved_revision",UUID.class),r.getLong("version")),id);
        if(rows.isEmpty()) { throw ModerationStore.unavailable(); } return rows.getFirst();
    }
    ReviewInput input(Resource resource,UUID revision) {
        var rows=jdbc.query("select text,declared_label,digest from workbench_revision where id=? and resource_id=?",(r,n)->new ReviewInput(resource.reference(revision),r.getString("text"),r.getString("declared_label"),r.getString("digest"),attachments(resource.reference(revision))),revision,resource.id());
        if(rows.isEmpty()) { throw ModerationStore.unavailable(); } return rows.getFirst();
    }
    List<FileReference> attachments(ContentReference ref) { return jdbc.query("select file_id,digest from workbench_attachment where revision_id=? order by file_id",(r,n)->new FileReference(r.getObject("file_id",UUID.class),ref,r.getString("digest")),ref.revisionId()); }
    boolean member(Resource resource,UUID actor) { return resource.actor().equals(actor) || resource.subject().equals(actor) || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workbench_member where resource_id=? and account_id=?)",Boolean.class,resource.id(),actor)); }
    record Resource(UUID id,SurfaceKind kind,UUID actor,UUID subject,Capability capability,String audience,UUID current,UUID approved,long version) {
        ContentReference reference(UUID revision) { return new ContentReference(kind,id,revision); }
        AuthorContext author() { return new AuthorContext(new ActorId(actor),new SubjectId(subject),capability); }
    }
}
