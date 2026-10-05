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
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkbenchService {
    private final WorkbenchStore store;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final FileAccess files;
    private final ModerationAccess moderation;
    private final Admission admission;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final org.springframework.beans.factory.ObjectProvider<ContentReader> readers;
    public WorkbenchService(WorkbenchStore store,IdentityAccess identity,SecurityProof proof,FileAccess files,ModerationAccess moderation,Admission admission,Clock clock,PlatformTransactionManager transactions,org.springframework.beans.factory.ObjectProvider<ContentReader> readers) {
        this.store=store; this.identity=identity; this.proof=proof; this.files=files; this.moderation=moderation; this.admission=admission; this.clock=clock; this.transaction=new TransactionTemplate(transactions);this.readers=readers;
    }
    public ContentReference save(Draft draft) {
        return transaction.execute(status -> {
            UUID actor=proof.actor().orElseThrow(ModerationStore::unavailable);
            WorkbenchStore.Resource discovered=draft.resource().map(id->store.resource(id,false)).orElse(null);
            AuthorContext author=discovered==null?new AuthorContext(new ActorId(actor),new SubjectId(draft.subject().orElse(actor)),draft.audience().equals("PRIVATE")?Capability.SEND_MARKETPLACE_MESSAGE:Capability.NEW_REQUEST):discovered.author();
            if(!author.actor().value().equals(actor)) { throw ModerationStore.unavailable(); }
            identity.requireForUpdate(author.permission());
            ContentDigest.validate(draft.text(),draft.label(),List.of());
            if(!Set.of("PUBLIC","PRIVATE").contains(draft.audience()) || draft.uploads().size()>4 || (draft.audience().equals("PUBLIC") && draft.participant().isPresent())) { throw new ModerationFailure("Audiencia o adjuntos inválidos"); }
            String digest=commandDigest(draft);
            var replay=store.jdbc.queryForList("select * from workbench_receipt where actor_id=? and command_id=?",actor,draft.command());
            if(!replay.isEmpty()) {
                var row=replay.getFirst(); if(!digest.equals(row.get("digest"))) { throw new ModerationFailure("Comando ya ligado a otro borrador"); }
                var existing=store.resource((UUID)row.get("resource_id"),true);return existing.reference((UUID)row.get("revision_id"));
            }
            admission.actor(actor,"draft");
            UUID resourceId=discovered==null?UUID.randomUUID():discovered.id(); UUID revisionId=UUID.randomUUID();
            SurfaceKind kind=discovered==null?(draft.audience().equals("PRIVATE")?SurfaceKind.PRIVATE_MESSAGE:SurfaceKind.REQUEST_BODY):discovered.kind();
            var ref=new ContentReference(kind,resourceId,revisionId);
            List<FileReference> attachments=new ArrayList<>();
            for(int index=0;index<draft.uploads().size();index++) {
                var upload=draft.uploads().get(index);
                UUID fileCommand=UUID.nameUUIDFromBytes((draft.command()+":"+index).getBytes(StandardCharsets.UTF_8));
                attachments.add(files.intake(fileCommand,author,ref,upload.mediaType(),upload.bytes()));
            }
            if(discovered==null) {
                store.jdbc.update("insert into workbench_resource(id,kind,actor_id,subject_id,capability,audience) values(?,?,?,?,?,?)",resourceId,kind.name(),actor,author.subject().value(),author.capability().name(),draft.audience());
                draft.participant().ifPresent(id->store.jdbc.update("insert into workbench_member values(?,?)",resourceId,id));
            } else {
                var current=store.resource(resourceId,true);
                if(current.version()!=draft.version() || !current.author().equals(author) || !current.audience().equals(draft.audience())) { throw new ModerationFailure("El recurso cambió; revisá su estado antes de editar"); }
            }
            String payload=ContentDigest.payload(draft.text(),draft.label(),attachments);
            store.jdbc.update("insert into workbench_revision values(?,?,?,?,?,?)",revisionId,resourceId,draft.text(),draft.label(),payload,Timestamp.from(clock.instant()));
            for(var file:attachments) { store.jdbc.update("insert into workbench_attachment values(?,?,?)",revisionId,file.id(),file.digest()); }
            store.jdbc.update("update workbench_resource set current_revision=?,version=version+1 where id=?",revisionId,resourceId);
            store.jdbc.update("insert into workbench_receipt values(?,?,?,?,?)",actor,draft.command(),digest,resourceId,revisionId);
            return ref;
        });
    }
    public ModerationAccess.Approval submit(ContentReference reference) {
        return transaction.execute(status -> {
            var resource=store.resource(reference.resourceId(),false);
            var input=store.input(resource,reference.revisionId());
            return moderation.submitForReview(resource.author(),reference,input.text(),input.declaredLabel(),input.attachments());
        });
    }
    public ContentReference reference(UUID resource,UUID revision) { return transaction.execute(status->store.resource(resource,false).reference(revision)); }
    public List<ContentReader.ContentView> search(String query) {
        if(query==null || query.length()>100) { throw new ModerationFailure("Búsqueda demasiado larga"); }
        var ids=store.jdbc.query("select id from workbench_resource where audience='PUBLIC' and approved_revision is not null order by id limit 50",(r,n)->r.getObject(1,UUID.class));
        List<ContentReader.ContentView> results=new ArrayList<>();
        for(UUID id:ids) {
            var visible=visible(id);
            if(visible.isPresent()) {
                var view=visible.orElseThrow();
                if(view.text().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) { results.add(view); }
            }
        }
        return List.copyOf(results);
    }
    private Optional<ContentReader.ContentView> visible(UUID id) {
        try { return Optional.of(readers.getObject().retrieve(id)); }
        catch(ModerationFailure | ar.changeo.identity.IdentityFailure denied) { return Optional.empty(); }
    }
    public List<UUID> own() {
        return transaction.execute(status -> {
            UUID actor=proof.actor().orElseThrow(ModerationStore::unavailable);identity.requireForUpdate(PermissionRequest.self(new AccountId(actor),Capability.ACCESS_SUPPORT));
            return store.jdbc.query("select id from workbench_resource where actor_id=? or subject_id=? order by id limit 50",(r,n)->r.getObject(1,UUID.class),actor,actor);
        });
    }
    public void removeParticipant(UUID resourceId,UUID participant) {
        transaction.executeWithoutResult(status -> {
            var discovered=store.resource(resourceId,false);UUID actor=proof.actor().orElseThrow(ModerationStore::unavailable);
            if(!discovered.actor().equals(actor)) { throw ModerationStore.unavailable(); }
            identity.requireForUpdate(discovered.author().permission()); var current=store.resource(resourceId,true);
            if(!current.author().equals(discovered.author())) { throw ModerationStore.unavailable(); }
            store.jdbc.update("delete from workbench_member where resource_id=? and account_id=?",resourceId,participant);
        });
    }
    private static String commandDigest(Draft draft) {
        StringBuilder value=new StringBuilder(ContentDigest.payload(draft.text(),draft.label(),List.of())).append(':').append(draft.resource()).append(':').append(draft.version()).append(':').append(draft.audience()).append(':').append(draft.subject()).append(':').append(draft.participant());
        for(var upload:draft.uploads()) { value.append(':').append(upload.mediaType()).append(':').append(ContentDigest.bytes(upload.bytes())); }
        return ContentDigest.bytes(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    public record Upload(String mediaType,byte[] bytes) {
        public Upload { Objects.requireNonNull(mediaType);Objects.requireNonNull(bytes);if(bytes.length==0 || bytes.length>1048576) { throw new ModerationFailure("Archivo vacío o mayor que 1 MiB local"); }bytes=bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record Draft(UUID command,Optional<UUID> resource,long version,String text,String label,String audience,Optional<UUID> subject,Optional<UUID> participant,List<Upload> uploads) {
        public Draft { Objects.requireNonNull(command);Objects.requireNonNull(resource);Objects.requireNonNull(subject);Objects.requireNonNull(participant);uploads=List.copyOf(uploads); }
    }
}
