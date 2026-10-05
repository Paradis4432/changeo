package ar.changeo.marketplace;

import ar.changeo.files.api.FileReference;
import ar.changeo.identity.api.*;
import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.moderation.*;
import ar.changeo.moderation.api.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class MarketplaceStore {
    final JdbcTemplate jdbc;
    private final Clock clock;

    MarketplaceStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    Resource resource(UUID id, boolean lock) {
        requireTransaction();
        return jdbc.query("select * from marketplace_resource where id=?" + (lock ? " for update" : ""), (row,index) ->
                new Resource(id, row.getObject("subject_id",UUID.class), row.getObject("creator_id",UUID.class),
                        PresenceType.valueOf(row.getString("type")), PublicationLifecycle.valueOf(row.getString("lifecycle")),
                        row.getObject("current_revision",UUID.class), row.getObject("approved_revision",UUID.class), row.getLong("version")), id)
                .stream().findFirst().orElseThrow(MarketplaceStore::unavailable);
    }

    Revision revision(Resource resource, UUID id) {
        return jdbc.query("select * from marketplace_revision where resource_id=? and id=?", (row,index) -> {
            var details = new MarketplaceDetails(row.getString("title"),row.getString("body"),strings(row,"tags"),
                    row.getString("province"),row.getString("area"),new HashSet<>(strings(row,"modes").stream().map(FulfillmentMode::valueOf).toList()),
                    row.getString("availability"),Optional.ofNullable(row.getBigDecimal("amount")),
                    Optional.ofNullable(row.getDate("deadline")).map(java.sql.Date::toLocalDate),row.getBoolean("badge_opt_in"));
            var context = new AuthorContext(new ActorId(row.getObject("actor_id",UUID.class)),new SubjectId(row.getObject("subject_id",UUID.class)),Capability.valueOf(row.getString("capability")));
            var reference = resource.reference(id);
            return new Revision(reference,context,details,row.getString("review_text"),row.getString("declared_label"),row.getString("digest"),attachments(reference));
        },resource.id(),id).stream().findFirst().orElseThrow(MarketplaceStore::unavailable);
    }

    void insert(Resource resource, UUID revision, AuthorContext author, MarketplaceDetails details, String label, List<FileReference> attachments) {
        String text = details.reviewText(resource.type());
        String digest = ContentDigest.payload(text,label,attachments);
        jdbc.update("insert into marketplace_revision(id,resource_id,actor_id,subject_id,capability,title,body,tags,province,area,modes,availability,amount,deadline,badge_opt_in,review_text,declared_label,digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                revision,resource.id(),author.actor().value(),author.subject().value(),author.capability().name(),details.title(),details.body(),
                new SqlArrayValue("text",details.tags().toArray()),details.province(),details.area(),
                new SqlArrayValue("text",details.modes().stream().map(Enum::name).sorted().toArray()),details.availability(),details.amount().orElse(null),
                details.deadline().map(java.sql.Date::valueOf).orElse(null),details.badgeOptIn(),text,label,digest,Timestamp.from(clock.instant()));
        for (FileReference file : attachments) { jdbc.update("insert into marketplace_attachment values(?,?,?)",revision,file.id(),file.digest()); }
        jdbc.update("update marketplace_resource set current_revision=?,version=version+1 where id=?",revision,resource.id());
    }

    boolean owns(ContentReference reference) {
        String type = switch(reference.ownerKind()) { case PROFILE_BIO -> "type in ('CUSTOMER_PROFILE','PROVIDER_PROFILE')"; case OFFER_BODY -> "type='OFFER'"; case REQUEST_BODY -> "type='REQUEST'"; default -> "false"; };
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from marketplace_resource r join marketplace_revision v on v.resource_id=r.id where r.id=? and v.id=? and " + type + ")",Boolean.class,reference.resourceId(),reference.revisionId()));
    }

    List<FileReference> attachments(ContentReference reference) {
        return jdbc.query("select file_id,digest from marketplace_attachment where revision_id=? order by file_id", (row,index) ->
                new FileReference(row.getObject(1,UUID.class),reference,row.getString(2)),reference.revisionId());
    }

    Optional<ContentReference> receipt(UUID actor, UUID command, String operation, String digest) {
        var rows = jdbc.queryForList("select * from marketplace_receipt where actor_id=? and command_id=?",actor,command);
        if (rows.isEmpty()) { return Optional.empty(); }
        var row = rows.getFirst();
        if (!operation.equals(row.get("operation")) || !digest.equals(row.get("digest"))) { throw new ModerationFailure("Comando ligado a otra operación; revisá el estado actual"); }
        var resource = resource((UUID)row.get("resource_id"),true);
        return Optional.of(resource.reference((UUID)row.get("revision_id")));
    }

    void receipt(UUID actor, UUID command, String operation, String digest, ContentReference reference) {
        jdbc.update("insert into marketplace_receipt values(?,?,?,?,?,?)",actor,command,operation,digest,reference.resourceId(),reference.revisionId());
    }

    boolean historicalParticipant(Resource resource, UUID actor) {
        return resource.subject().equals(actor) || resource.creator().equals(actor)
                || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from marketplace_revision where resource_id=? and actor_id=?)",Boolean.class,resource.id(),actor));
    }

    private static List<String> strings(ResultSet row, String name) throws SQLException {
        Array array = row.getArray(name);
        try { return List.of((String[])array.getArray()); } finally { array.free(); }
    }
    static void requireTransaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new ModerationFailure("Se requiere una transacción del propietario"); } }
    static ModerationFailure unavailable() { return new ModerationFailure("Publicación no disponible para este acceso"); }

    record Resource(UUID id, UUID subject, UUID creator, PresenceType type, PublicationLifecycle lifecycle, UUID current, UUID approved, long version) {
        ContentReference reference(UUID revision) { return new ContentReference(type.surface(),id,revision); }
    }
    record Revision(ContentReference reference, AuthorContext author, MarketplaceDetails details, String text, String label, String digest, List<FileReference> attachments) {
        ReviewInput input() { return new ReviewInput(reference,text,label,digest,attachments); }
    }
}
