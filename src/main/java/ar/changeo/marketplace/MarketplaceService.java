package ar.changeo.marketplace;

import ar.changeo.files.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MarketplaceService {
    private final MarketplaceStore store;
    private final SecurityProof proof;
    private final IdentityAccess identity;
    private final FileAccess files;
    private final ModerationAccess moderation;
    private final Admission admission;
    private final TransactionTemplate transaction;

    MarketplaceService(MarketplaceStore store, SecurityProof proof, IdentityAccess identity, FileAccess files,
                       ModerationAccess moderation, Admission admission, PlatformTransactionManager transactions) {
        this.store=store; this.proof=proof; this.identity=identity; this.files=files; this.moderation=moderation;
        this.admission=admission; this.transaction=new TransactionTemplate(transactions);
    }

    public ContentReference save(Draft draft) {
        return transaction.execute(status -> {
            UUID actor=actor();
            var discovered=draft.resource().map(id -> store.resource(id,false)).orElse(null);
            UUID subject=discovered==null ? draft.subject() : discovered.subject();
            PresenceType type=discovered==null ? draft.type() : discovered.type();
            if (type!=draft.type() || !subject.equals(draft.subject())) { throw MarketplaceStore.unavailable(); }
            var author=context(actor,subject,type,actor.equals(subject) ? type.draftCapability() : type.publishCapability());
            identity.requireForUpdate(author.permission());
            validate(type,draft.details(),draft.label());
            String digest=digest(draft.details().reviewText(type)+draft.label()+draft.resource()+draft.version()+subject,draft.uploads());
            var replay=store.receipt(actor,draft.command(),"SAVE",digest);
            if (replay.isPresent()) { return replay.orElseThrow(); }
            admission.actor(actor,"marketplace-save");
            UUID resourceId=discovered==null ? UUID.randomUUID() : discovered.id();
            UUID revisionId=UUID.randomUUID();
            var reference=new ContentReference(type.surface(),resourceId,revisionId);
            var attachments=intake(draft.command(),author,reference,draft.uploads());
            MarketplaceStore.Resource resource;
            if (discovered==null) {
                store.jdbc.update("insert into marketplace_resource(id,subject_id,creator_id,type) values(?,?,?,?)",resourceId,subject,actor,type.name());
                resource=store.resource(resourceId,true);
            } else {
                resource=store.resource(resourceId,true);
                editable(resource,draft.version());
            }
            store.insert(resource,revisionId,author,draft.details(),draft.label(),attachments);
            store.receipt(actor,draft.command(),"SAVE",digest,reference);
            return reference;
        });
    }

    public ContentReference prepare(Preparation preparation) {
        return transaction.execute(status -> {
            UUID actor=actor();
            var discovered=store.resource(preparation.source().resourceId(),false);
            var original=store.revision(discovered,preparation.source().revisionId());
            if (!original.reference().equals(preparation.source())) { throw MarketplaceStore.unavailable(); }
            var author=context(actor,discovered.subject(),discovered.type(),discovered.type().publishCapability());
            identity.requireForUpdate(author.permission());
            String digest=digest(preparation.source()+":"+preparation.version()+":"+preparation.omitPreviousMedia(),preparation.uploads());
            var replay=store.receipt(actor,preparation.command(),"PREPARE",digest);
            if (replay.isPresent()) { return replay.orElseThrow(); }
            if (!original.attachments().isEmpty() && !preparation.omitPreviousMedia() && preparation.uploads().isEmpty()) {
                throw new ModerationFailure("Volvé a adjuntar los archivos para esta revisión o elegí omitirlos explícitamente");
            }
            admission.actor(actor,"marketplace-prepare");
            var reference=discovered.reference(UUID.randomUUID());
            var attachments=intake(preparation.command(),author,reference,preparation.uploads());
            var resource=store.resource(discovered.id(),true);
            editable(resource,preparation.version());
            if (!original.reference().revisionId().equals(resource.current())) { throw new ModerationFailure("El borrador cambió; revisá la versión actual"); }
            store.insert(resource,reference.revisionId(),author,original.details(),original.label(),attachments);
            store.receipt(actor,preparation.command(),"PREPARE",digest,reference);
            return reference;
        });
    }

    public ModerationAccess.Approval submit(ContentReference reference) {
        return transaction.execute(status -> {
            var resource=store.resource(reference.resourceId(),false);
            var revision=store.revision(resource,reference.revisionId());
            if (!revision.reference().equals(reference) || revision.author().capability()!=resource.type().publishCapability()
                    || !revision.author().actor().value().equals(actor())) { throw MarketplaceStore.unavailable(); }
            return moderation.submitForReview(revision.author(),reference,revision.text(),revision.label(),revision.attachments());
        });
    }

    public void transition(UUID command, UUID id, long version, String action) {
        transaction.executeWithoutResult(status -> {
            UUID actor=actor();
            var discovered=store.resource(id,false);
            boolean resume=action.equals("RESUME");
            if (!Set.of("PAUSE","RESUME","CLOSE").contains(action)) { throw MarketplaceStore.unavailable(); }
            var permission=context(actor,discovered.subject(),discovered.type(),discovered.type().publishCapability()).permission();
            identity.requireForUpdate(permission);
            String digest=digest(id+":"+version+":"+action,List.of());
            if (store.receipt(actor,command,action,digest).isPresent()) { return; }
            admission.actor(actor,"marketplace-state");
            var resource=store.resource(id,true);
            if (resource.version()!=version || resource.lifecycle()==PublicationLifecycle.CLOSED) { throw new ModerationFailure("La publicación cambió o está cerrada; revisá su estado"); }
            PublicationLifecycle next;
            if (resume) {
                if (resource.lifecycle()!=PublicationLifecycle.PAUSED || resource.approved()==null) { throw new ModerationFailure("Solo se puede reanudar una revisión aprobada y pausada"); }
                var revision=store.revision(resource,resource.approved());
                var approval=moderation.approvalForUpdate(revision.reference());
                MarketplaceReader.exactApproval(revision,approval);
                next=PublicationLifecycle.OPEN;
            } else { next=action.equals("CLOSE") ? PublicationLifecycle.CLOSED : PublicationLifecycle.PAUSED; }
            store.jdbc.update("update marketplace_resource set lifecycle=?,version=version+1 where id=?",next.name(),id);
            store.receipt(actor,command,action,digest,resource.reference(resource.current()));
        });
    }

    public ContentReference reference(UUID id, UUID revision) {
        return transaction.execute(status -> {
            var reference=store.resource(id,false).reference(revision);
            if (!store.owns(reference)) { throw MarketplaceStore.unavailable(); }
            return reference;
        });
    }

    private List<FileReference> intake(UUID command, AuthorContext author, ContentReference reference, List<Upload> uploads) {
        if (uploads.size()>4) { throw new ModerationFailure("Hasta cuatro adjuntos por revisión local"); }
        List<FileReference> result=new ArrayList<>();
        for (int index=0;index<uploads.size();index++) {
            var upload=uploads.get(index);
            result.add(files.intake(UUID.nameUUIDFromBytes((command+":"+index).getBytes(StandardCharsets.UTF_8)),author,reference,upload.mediaType(),upload.bytes()));
        }
        return result.stream().sorted(Comparator.comparing(file -> file.id().toString())).toList();
    }
    private static void editable(MarketplaceStore.Resource resource, long version) {
        if (resource.version()!=version || resource.lifecycle()!=PublicationLifecycle.OPEN) { throw new ModerationFailure("Versión desactualizada o publicación pausada/cerrada; revisá su estado antes de editar"); }
    }
    private static void validate(PresenceType type, MarketplaceDetails details, String label) {
        details.reviewText(type);
        if (!Set.of("GENERAL","SENSITIVE","ADULT").contains(label)
                || (type.profile() && (details.amount().isPresent() || details.deadline().isPresent()))
                || (!type.profile() && details.badgeOptIn())) { throw new ModerationFailure("Campos o etiqueta incompatibles con esta publicación"); }
    }
    private UUID actor() { return proof.actor().orElseThrow(MarketplaceStore::unavailable); }
    private static AuthorContext context(UUID actor, UUID subject, PresenceType type, Capability capability) { return new AuthorContext(new ActorId(actor),new SubjectId(subject),capability); }
    private static String digest(String value, List<Upload> uploads) {
        StringBuilder payload=new StringBuilder(value);
        for (Upload upload:uploads) { payload.append(':').append(upload.mediaType()).append(':').append(ContentDigest.bytes(upload.bytes())); }
        return ContentDigest.bytes(payload.toString().getBytes(StandardCharsets.UTF_8));
    }
    public record Upload(String mediaType, byte[] bytes) {
        public Upload {
            Objects.requireNonNull(mediaType);
            if (bytes==null || bytes.length==0 || bytes.length>1048576) { throw new ModerationFailure("Archivo vacío o mayor que 1 MiB local"); }
            bytes=bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record Draft(UUID command, Optional<UUID> resource, long version, PresenceType type, UUID subject,
                        MarketplaceDetails details, String label, List<Upload> uploads) {
        public Draft { Objects.requireNonNull(command); Objects.requireNonNull(resource); Objects.requireNonNull(type); Objects.requireNonNull(subject); Objects.requireNonNull(details); Objects.requireNonNull(label); uploads=List.copyOf(uploads); }
    }
    public record Preparation(UUID command, ContentReference source, long version, boolean omitPreviousMedia, List<Upload> uploads) {
        public Preparation { Objects.requireNonNull(command); Objects.requireNonNull(source); uploads=List.copyOf(uploads); }
    }
}
