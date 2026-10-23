package ar.changeo.identity;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class DurableTokenDelivery {
    final JdbcTemplate jdbc;
    final IdentityStore identity;
    final Clock clock;
    final TokenDeliverySpool spool;
    private final String sender;

    DurableTokenDelivery(JdbcTemplate jdbc, IdentityStore identity, Clock clock, TokenDeliverySpool spool, String sender) {
        this.jdbc = jdbc; this.identity = identity; this.clock = clock; this.spool = spool; this.sender = AccountService.normalize(sender);
    }

    TokenDeliveryCoordinator.Preparation prepare(ContactToken token, long epoch, String recipient, String raw) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Token preparation requires the identity transaction"); }
        identity.queries().flush();
        identity.queries().refresh(token);
        UUID id = UUID.randomUUID();
        var binding = new TokenDeliverySpool.Binding(id, token.id, token.accountId, token.purpose, token.generation, epoch, token.expiresAt, token.tokenHash);
        try { spool.seal(binding, sender, recipient, raw); }
        catch (TokenDeliverySpool.MaterialFailure failure) { return TokenDeliveryCoordinator.Preparation.FAILED; }
        jdbc.update("insert into identity_token_delivery(id,token_id,account_id,purpose,generation,security_epoch,expires_at,state,created_at,available_at,reason) values(?,?,?,?,?,?,?,'PENDING',?,?,'PREPARED')",
                id, token.id, token.accountId, token.purpose.name(), token.generation, epoch, time(token.expiresAt), time(clock.instant()), time(clock.instant()));
        return TokenDeliveryCoordinator.Preparation.QUEUED;
    }

    Optional<Intent> find(UUID id, boolean lock) {
        return jdbc.query("select * from identity_token_delivery where id=?" + (lock ? " for update" : ""), (row, index) -> intent(row), id).stream().findFirst();
    }

    private Intent intent(ResultSet row) throws SQLException {
        return new Intent(row.getObject("id", UUID.class), row.getObject("token_id", UUID.class), row.getObject("account_id", UUID.class),
                TokenPurpose.valueOf(row.getString("purpose")), row.getLong("generation"), row.getLong("security_epoch"), instant(row, "expires_at"),
                State.valueOf(row.getString("state")), instant(row, "created_at"), instant(row, "available_at"), instant(row, "dispatched_at"), instant(row, "uncertain_at"),
                row.getInt("attempts"), row.getInt("observations"), row.getLong("lease_generation"), row.getObject("lease_token", UUID.class), instant(row, "lease_until"),
                row.getObject("provider_id", UUID.class), row.getString("invalid_reason"));
    }

    static Timestamp time(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet row, String name) throws SQLException { Timestamp value = row.getTimestamp(name); return value == null ? null : value.toInstant(); }
    enum State { PENDING, DISPATCHING, UNKNOWN, ACCEPTED, DELIVERED, FAILED, CANCELLED }
    record Intent(UUID id, UUID tokenId, UUID accountId, TokenPurpose purpose, long generation, long epoch, Instant expiresAt,
            State state, Instant createdAt, Instant availableAt, Instant dispatchedAt, Instant uncertainAt, int attempts, int observations,
            long leaseGeneration, UUID leaseToken, Instant leaseUntil, UUID providerId, String invalidReason) {
        TokenDeliverySpool.Binding binding(String hash) { return new TokenDeliverySpool.Binding(id, tokenId, accountId, purpose, generation, epoch, expiresAt, hash); }
    }
}
