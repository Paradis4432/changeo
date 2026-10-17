package ar.changeo.notifications;

import ar.changeo.identity.SecurityProof;
import ar.changeo.identity.api.ActorId;
import ar.changeo.moderation.api.*;
import ar.changeo.notifications.api.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@Service
public class NotificationService implements NotificationAccess {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<NoticeOwner> owners;
    private final Clock clock;
    private final SecurityProof proof;
    private final TransactionTemplate transaction;
    NotificationService(JdbcTemplate jdbc, ObjectProvider<NoticeOwner> owners, Clock clock, SecurityProof proof, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.owners = owners; this.clock = clock; this.proof = proof; this.transaction = new TransactionTemplate(transactions);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(ContentReference reference, Set<ActorId> recipients) {
        owner(reference);
        var exact = Set.copyOf(recipients);
        if (exact.isEmpty() || exact.size() > 2) { throw new IllegalArgumentException("Destinatarios inválidos"); }
        var now = Timestamp.from(clock.instant());
        for (var recipient : exact.stream().sorted(Comparator.comparing(actor -> actor.value().toString())).toList()) {
            jdbc.update("insert into notifications_intent(id,recipient_id,owner_kind,resource_id,revision_id,available_at,created_at) values(?,?,?,?,?,?,?) on conflict do nothing",
                    UUID.randomUUID(), recipient.value(), reference.ownerKind().name(), reference.resourceId(), reference.revisionId(), now, now);
        }
    }
    public boolean runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("El trabajador requiere transacciones independientes"); }
        Claim claim = transaction.execute(status -> claim());
        if (claim == null) { return false; }
        try { transaction.executeWithoutResult(status -> deliver(claim)); }
        catch (RuntimeException failure) { transaction.executeWithoutResult(status -> failed(claim)); }
        return true;
    }
    private Claim claim() {
        var now = clock.instant();
        var rows = jdbc.query("select * from notifications_intent where (state in ('PENDING','RETRY_WAIT') and available_at<=?) or (state='CLAIMED' and lease_until<=?) order by available_at,id for update skip locked limit 1",
                (row, index) -> new Claim(row.getObject("id", UUID.class), new ActorId(row.getObject("recipient_id", UUID.class)),
                        new ContentReference(SurfaceKind.valueOf(row.getString("owner_kind")), row.getObject("resource_id", UUID.class), row.getObject("revision_id", UUID.class)),
                        row.getLong("generation") + 1, UUID.randomUUID(), row.getInt("attempts") + 1), Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) { return null; }
        var claim = rows.getFirst();
        if (claim.attempts() > 3) {
            jdbc.update("update notifications_intent set state='FAILED',claim_token=null,lease_until=null,reason='EXHAUSTED' where id=?", claim.id());
            return null;
        }
        jdbc.update("update notifications_intent set state='CLAIMED',generation=?,attempts=?,claim_token=?,lease_until=?,reason='CLAIMED' where id=?",
                claim.generation(), claim.attempts(), claim.token(), Timestamp.from(now.plusSeconds(30)), claim.id());
        return claim;
    }
    private void deliver(Claim claim) {
        owner(claim.reference()).requireNotice(claim.reference(), claim.recipient());
        if (!live(claim)) { return; }
        jdbc.update("insert into notifications_local_delivery values(?,?) on conflict do nothing", claim.id(), Timestamp.from(clock.instant()));
        jdbc.update("update notifications_intent set state='DELIVERED',delivered_at=?,claim_token=null,lease_until=null,reason='LOCAL_ON_SITE' where id=?",
                Timestamp.from(clock.instant()), claim.id());
    }
    private boolean live(Claim claim) {
        var rows = jdbc.queryForList("select state,generation,claim_token,lease_until from notifications_intent where id=? for update", claim.id());
        if (rows.isEmpty()) { return false; }
        var row = rows.getFirst();
        return row.get("state").equals("CLAIMED") && ((Number) row.get("generation")).longValue() == claim.generation()
                && claim.token().equals(row.get("claim_token")) && ((Timestamp) row.get("lease_until")).toInstant().isAfter(clock.instant());
    }
    private void failed(Claim claim) {
        if (!live(claim)) { return; }
        jdbc.update("update notifications_intent set state=?,available_at=?,claim_token=null,lease_until=null,reason=? where id=?",
                claim.attempts() == 3 ? "FAILED" : "RETRY_WAIT", Timestamp.from(clock.instant().plusSeconds(30L * claim.attempts())),
                claim.attempts() == 3 ? "EXHAUSTED" : "CURRENT_CONTENT_UNAVAILABLE", claim.id());
    }
    public List<Notice> own() {
        ActorId actor = new ActorId(proof.actor().orElseThrow(() -> new IllegalArgumentException("Cuenta requerida")));
        var candidates = jdbc.query("select * from notifications_intent where recipient_id=? order by created_at desc,id limit 20",
                (row, index) -> new Notice(row.getObject("id", UUID.class), new ContentReference(SurfaceKind.valueOf(row.getString("owner_kind")),
                        row.getObject("resource_id", UUID.class), row.getObject("revision_id", UUID.class)), row.getString("state"), row.getString("reason")), actor.value());
        List<Notice> result = new ArrayList<>();
        for (var candidate : candidates) {
            Optional<Notice> current;
            try {
                current = transaction.execute(status -> {
                    owner(candidate.reference()).requireNotice(candidate.reference(), actor);
                    var rows = jdbc.query("select state,reason from notifications_intent where id=? and recipient_id=? for update",
                            (row, index) -> new Notice(candidate.id(), candidate.reference(), row.getString(1), row.getString(2)), candidate.id(), actor.value());
                    return rows.stream().findFirst();
                });
            } catch (RuntimeException unavailable) { current = Optional.empty(); }
            current.ifPresent(result::add);
        }
        return List.copyOf(result);
    }
    private NoticeOwner owner(ContentReference reference) {
        var matches = owners.orderedStream().filter(owner -> owner.noticeKinds().contains(reference.ownerKind())).toList();
        if (matches.size() != 1) { throw new IllegalArgumentException("Propietario de aviso no disponible"); }
        return matches.getFirst();
    }
    private record Claim(UUID id, ActorId recipient, ContentReference reference, long generation, UUID token, int attempts) {}
    public record Notice(UUID id, ContentReference reference, String state, String reason) {}
}
