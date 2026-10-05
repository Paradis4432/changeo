package ar.changeo.files;

import ar.changeo.files.api.*;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.IdentityAccess;
import java.io.*;
import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class PrivateFileService implements FileAccess {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<AttachmentAccessPolicy> policies;
    private final IdentityAccess identity;
    private final SecurityProof proof;
    private final Admission admission;
    private final Clock clock;
    private final Path root;
    private final FileInspector inspector;
    public PrivateFileService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectProvider<AttachmentAccessPolicy> policies,
                              IdentityAccess identity, SecurityProof proof, Admission admission, Clock clock, FileInspector inspector,
                              @Value("${changeo.files-root:.runtime/content-artifacts}") String root) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(transactions); this.policies=policies;
        this.inspector=inspector; this.identity=identity; this.proof=proof; this.admission=admission; this.clock=clock; this.root=Path.of(root).toAbsolutePath().normalize();
    }
    @Override public FileReference intake(UUID command, AuthorContext author, ContentReference content, String mediaType, byte[] bytes) {
        requireTransaction();
        if (!proof.actor().filter(author.actor().value()::equals).isPresent()) { throw denied(); }
        identity.requireForUpdate(author.permission());
        if (bytes == null || bytes.length == 0 || bytes.length > 1048576) { throw new ModerationFailure("Archivo vacío o mayor que 1 MiB local"); }
        String digest=ContentDigest.bytes(bytes);
        var existing=jdbc.queryForList("select id,digest,resource_id,revision_id,owner_kind,media_type from files_artifact where actor_id=? and command_id=?",author.actor().value(),command);
        if (!existing.isEmpty()) {
            var row=existing.getFirst();
            if (!digest.equals(row.get("digest")) || !content.resourceId().equals(row.get("resource_id")) || !content.revisionId().equals(row.get("revision_id")) || !content.ownerKind().name().equals(row.get("owner_kind")) || !mediaType.equals(row.get("media_type"))) { throw denied(); }
            return new FileReference((UUID)row.get("id"),content,digest);
        }
        admission.actor(author.actor().value(),"upload");
        FileInspector.Inspection inspection=inspector.inspect(mediaType,bytes);
        UUID id=UUID.randomUUID();
        write(id,bytes);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if (status!=STATUS_COMMITTED) { remove(id); } }
        });
        jdbc.update("insert into files_artifact values(?,?,?,?,?,?,?,?,?,?,?,?)",id,command,author.actor().value(),content.resourceId(),content.revisionId(),content.ownerKind().name(),digest,mediaType,bytes.length,inspection.safe(),inspection.reason(),Timestamp.from(clock.instant()));
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
            byte[] bytes=copy(reference.id(),metadata.size());
            if(!ContentDigest.bytes(bytes).equals(reference.digest())) { throw denied(); }
            return new Download(bytes,metadata.mediaType());
        });
    }
    private void directory() throws IOException {
        Path current=root.getRoot();
        for(Path component:root) {
            current=current.resolve(component);
            if(Files.isSymbolicLink(current)) { throw denied(); }
        }
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) { Files.createDirectories(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))) { throw denied(); }
    }
    private void write(UUID id,byte[] bytes) {
        try {
            directory();
            try(var channel=Files.newByteChannel(root.resolve(id.toString()),Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) { channel.write(buffer); }
            }
        } catch(IOException failure) { throw new ModerationFailure("Almacenamiento privado no disponible"); }
    }
    private byte[] copy(UUID id,long size) {
        try {
            directory(); Path path=root.resolve(id.toString());
            if(!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))) { throw denied(); }
            try(SeekableByteChannel channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
                if(channel.size()!=size || size>1048576) { throw denied(); }
                ByteBuffer buffer=ByteBuffer.allocate((int)size); while(buffer.hasRemaining()) { if(channel.read(buffer)<0) { throw denied(); } }
                return buffer.array();
            }
        } catch(IOException failure) { throw new ModerationFailure("Archivo privado no disponible"); }
    }
    private void remove(UUID id) { try { Files.deleteIfExists(root.resolve(id.toString())); } catch(IOException failure) { throw new IllegalStateException("Private rollback cleanup failed",failure); } }
    private static void requireTransaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw denied(); } }
    private static ModerationFailure denied() { return new ModerationFailure("Archivo no disponible para este acceso"); }
}
