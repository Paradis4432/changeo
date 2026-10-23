package ar.changeo.files;

import ar.changeo.files.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.IdentityAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class PrivateFileService implements FileAccess, ReviewFileAccess {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<AttachmentAccessPolicy> policies;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final Admission admission;
    private final Clock clock;
    private final LocalPrivateBlobStore local;
    private final R2PrivateBlobStore remote;
    private final FileInspector inspector;
    private ObjectProvider<ReviewFilePolicy> reviewPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    void reviewPolicies(ObjectProvider<ReviewFilePolicy> reviewPolicies) { this.reviewPolicies=reviewPolicies; }
    public PrivateFileService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectProvider<AttachmentAccessPolicy> policies,
                              IdentityAccess identity, SecurityProof proof, Admission admission, Clock clock, FileInspector inspector,
                              LocalPrivateBlobStore local, ObjectProvider<R2PrivateBlobStore> remote) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(transactions); this.policies=policies;
        this.inspector=inspector; this.identity=identity; this.proof=proof; this.admission=admission; this.clock=clock;
        this.local=local; this.remote=remote.getIfAvailable();
    }
    @Transactional(propagation = Propagation.MANDATORY)
    @Override public FileReference intake(UUID command, AuthorContext author, ContentReference content, String mediaType, byte[] bytes) {
        requireTransaction();
        if (!proof.actor().filter(author.actor().value()::equals).isPresent()) { throw denied(); }
        identity.requireForUpdate(author.permission());
        if (bytes == null || bytes.length == 0 || bytes.length > 1048576) { throw new ModerationFailure("Archivo vacío o mayor que 1 MiB local"); }
        byte[] immutable=bytes.clone();
        String digest=ContentDigest.bytes(immutable);
        var existing=jdbc.queryForList("select id,digest,resource_id,revision_id,owner_kind,media_type from files_artifact where actor_id=? and command_id=?",author.actor().value(),command);
        if (!existing.isEmpty()) {
            var row=existing.getFirst();
            if (!digest.equals(row.get("digest")) || !content.resourceId().equals(row.get("resource_id")) || !content.revisionId().equals(row.get("revision_id")) || !content.ownerKind().name().equals(row.get("owner_kind")) || !mediaType.equals(row.get("media_type"))) { throw denied(); }
            return new FileReference((UUID)row.get("id"),content,digest);
        }
        admission.actor(author.actor().value(),"upload");
        FileInspector.Inspection inspection=inspector.inspect(mediaType,immutable);
        UUID id=UUID.randomUUID();
        PrivateBlobStore storage=remote==null?local:remote;
        PrivateBlobStore.Blob blob=storage.create(id,mediaType,immutable);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED) { storage.rollback(blob); }
                else if(blob instanceof PrivateBlobStore.Remote binding) { remote.committed(binding); }
            }
        });
        jdbc.update("insert into files_artifact values(?,?,?,?,?,?,?,?,?,?,?,?)",id,command,author.actor().value(),content.resourceId(),content.revisionId(),content.ownerKind().name(),digest,mediaType,immutable.length,inspection.safe(),inspection.reason(),Timestamp.from(clock.instant()));
        if(blob instanceof PrivateBlobStore.Remote binding) {
            jdbc.update("insert into files_blob_binding values(?,'R2',?,?,?)",id,binding.storeId(),binding.objectKey(),binding.etag());
        }
        return new FileReference(id,content,digest);
    }
    @Override public Metadata metadata(FileReference reference) {
        var rows=jdbc.query("select * from files_artifact where id=?",(r,n) -> {
            if (!reference.content().resourceId().equals(r.getObject("resource_id",UUID.class)) || !reference.content().revisionId().equals(r.getObject("revision_id",UUID.class)) || !reference.content().ownerKind().name().equals(r.getString("owner_kind")) || !reference.digest().equals(r.getString("digest"))) { throw denied(); }
            return new Metadata(reference,r.getString("media_type"),r.getLong("size"),r.getBoolean("safe"),r.getString("reason"));
        },reference.id());
        if(rows.isEmpty()) { throw denied(); } return rows.getFirst();
    }
    @Override public Download read(FileReference reference, Purpose purpose) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("La lectura de bytes debe iniciar su propia transacción"); }
        return transaction.execute(status -> {
            AttachmentAccessPolicy policy=policies.getIfUnique();
            if(policy==null) { throw denied(); }
            policy.requireCurrentAccess(reference,purpose);
            Metadata metadata=metadata(reference);
            if(!metadata.safe()) { throw denied(); }
            byte[] bytes=copy(reference.id(),metadata.mediaType(),metadata.size());
            if(!ContentDigest.bytes(bytes).equals(reference.digest())) { throw denied(); }
            return new Download(bytes,metadata.mediaType());
        });
    }
    private byte[] copy(UUID id,String mediaType,long size) {
        var bindings=jdbc.queryForList("select backend,store_id,object_key,etag from files_blob_binding where file_id=?",id);
        if(bindings.isEmpty()) { return local.read(new PrivateBlobStore.Local(id),mediaType,size); }
        var binding=bindings.getFirst();
        if(remote==null || !"R2".equals(binding.get("backend"))) { throw denied(); }
        return remote.read(new PrivateBlobStore.Remote(id,(String)binding.get("store_id"),(String)binding.get("object_key"),(String)binding.get("etag")),mediaType,size);
    }
    @Override public Material materialize(Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw denied(); }
        var bounded = new TransactionTemplate(transaction.getTransactionManager());
        bounded.setTimeout(35);
        return bounded.execute(status -> {
            jdbc.execute("set local lock_timeout = '2s'");
            jdbc.execute("set local statement_timeout = '5s'");
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            ReviewFilePolicy policy = reviewPolicies == null ? null : reviewPolicies.getIfUnique();
            if (policy == null) { throw denied(); }
            policy.requireCurrentReview(request);
            List<ReviewFileAccess.File> material = new ArrayList<>();
            for (var reference : request.attachments()) {
                Metadata metadata = metadata(reference);
                if (!metadata.safe() || !Set.of("text/plain", "image/png", "image/jpeg").contains(metadata.mediaType())
                        || metadata.size() < 1 || metadata.size() > 1048576 || System.nanoTime() >= deadline) { throw denied(); }
                byte[] bytes = copy(reference.id(), metadata.mediaType(), metadata.size());
                if (bytes.length != metadata.size() || !ContentDigest.bytes(bytes).equals(reference.digest())
                        || !inspector.inspect(metadata.mediaType(), bytes).safe() || System.nanoTime() >= deadline) { throw denied(); }
                material.add(new ReviewFileAccess.File(reference, metadata.mediaType(), bytes));
            }
            if (System.nanoTime() >= deadline) { throw denied(); }
            return new Material(material);
        });
    }
    private static void requireTransaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw denied(); } }
    private static ModerationFailure denied() { return new ModerationFailure("Archivo no disponible para este acceso"); }
}
