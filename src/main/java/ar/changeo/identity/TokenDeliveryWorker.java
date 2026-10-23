package ar.changeo.identity;

import java.time.Instant;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static ar.changeo.identity.DurableTokenDelivery.time;

final class TokenDeliveryWorker {
    private final DurableTokenDelivery store;
    private final ResendTokenClient client;
    private final TransactionTemplate transaction;

    TokenDeliveryWorker(DurableTokenDelivery store, ResendTokenClient client, PlatformTransactionManager transactions) {
        this.store = store; this.client = client; this.transaction = new TransactionTemplate(transactions);
    }

    boolean processOne() {
        standalone();
        var now = time(store.clock.instant());
        var candidates = store.jdbc.query("select id from identity_token_delivery where available_at<=? and (lease_until is null or lease_until<=?) and state in ('PENDING','DISPATCHING','UNKNOWN','ACCEPTED') order by available_at,id limit 20",
                (row, index) -> row.getObject(1, UUID.class), now, now);
        for (UUID id : candidates) {
            if (process(id)) { return true; }
        }
        return false;
    }

    boolean process(UUID id) {
        standalone();
        Optional<Dispatch> prepared = authorize(id);
        if (prepared.isEmpty()) { return false; }
        Dispatch dispatch = prepared.orElseThrow();
        var result = dispatch.providerId().isPresent() ? client.query(dispatch.providerId().orElseThrow()) : client.send(dispatch.id(), dispatch.body());
        observe(dispatch, result);
        return true;
    }

    Optional<Dispatch> authorize(UUID id) {
        standalone();
        return transaction.execute(status -> authorizeCurrent(id));
    }

    private Optional<Dispatch> authorizeCurrent(UUID id) {
        var discovered = store.find(id, false);
        if (discovered.isEmpty()) { return Optional.empty(); }
        store.identity.lockAccounts(List.of(discovered.orElseThrow().accountId()));
        ContactToken token = store.identity.lock(ContactToken.class, discovered.orElseThrow().tokenId());
        var current = store.find(id, true).orElseThrow();
        Instant now = store.clock.instant();
        if (current.availableAt() == null || current.availableAt().isAfter(now) || current.leaseUntil() != null && current.leaseUntil().isAfter(now)
                || Set.of(DurableTokenDelivery.State.CANCELLED, DurableTokenDelivery.State.FAILED, DurableTokenDelivery.State.DELIVERED).contains(current.state())) { return Optional.empty(); }
        Account account = store.identity.account(current.accountId());
        String invalid = invalid(current, token, account, now);
        if (invalid != null) { store.jdbc.update("update identity_token_delivery set invalid_reason=coalesce(invalid_reason,?) where id=?", invalid, id); }
        if (current.providerId() != null) {
            if (current.observations() >= 6) { stop(current, "OBSERVATION_EXHAUSTED"); return Optional.empty(); }
            UUID lease = UUID.randomUUID();
            store.jdbc.update("update identity_token_delivery set observations=observations+1,lease_generation=lease_generation+1,lease_token=?,lease_until=?,reason='QUERY_COMMITTED' where id=?",
                    lease, time(now.plusSeconds(30)), id);
            return Optional.of(new Dispatch(id, current.leaseGeneration() + 1, lease, Optional.of(current.providerId()), new byte[0]));
        }
        if (current.state() == DurableTokenDelivery.State.DISPATCHING) {
            store.jdbc.update("update identity_token_delivery set state='UNKNOWN',uncertain_at=coalesce(uncertain_at,?),reason='LEASE_EXPIRED' where id=?", time(now), id);
            current = store.find(id, true).orElseThrow();
        }
        if (invalid != null || current.invalidReason() != null) {
            store.jdbc.update("update identity_token_delivery set state=?,available_at=null,lease_token=null,lease_until=null,reason='TOKEN_INVALID' where id=?",
                    current.dispatchedAt() == null ? "CANCELLED" : "UNKNOWN", id);
            return Optional.empty();
        }
        if (current.attempts() >= 3 || !current.createdAt().plusSeconds(86340).isAfter(now)) { stop(current, "POST_EXHAUSTED"); return Optional.empty(); }
        byte[] body;
        try { body = store.spool.open(current.binding(token.tokenHash)); }
        catch (TokenDeliverySpool.MaterialFailure failure) { stop(current, "MATERIAL_UNAVAILABLE"); return Optional.empty(); }
        now = store.clock.instant();
        if (!token.expiresAt.isAfter(now)) {
            store.jdbc.update("update identity_token_delivery set state=?,invalid_reason='TOKEN_EXPIRED',available_at=null,lease_token=null,lease_until=null,reason='TOKEN_INVALID' where id=?",
                    current.dispatchedAt() == null ? "CANCELLED" : "UNKNOWN", id);
            return Optional.empty();
        }
        UUID lease = UUID.randomUUID();
        store.jdbc.update("update identity_token_delivery set state='DISPATCHING',attempts=attempts+1,lease_generation=lease_generation+1,lease_token=?,lease_until=?,dispatched_at=coalesce(dispatched_at,?),reason='DISPATCH_COMMITTED' where id=?",
                lease, time(now.plusSeconds(30)), time(now), id);
        return Optional.of(new Dispatch(id, current.leaseGeneration() + 1, lease, Optional.empty(), body));
    }

    private String invalid(DurableTokenDelivery.Intent intent, ContactToken token, Account account, Instant now) {
        if (!token.accountId.equals(intent.accountId()) || token.purpose != intent.purpose() || token.generation != intent.generation()
                || !token.expiresAt.equals(intent.expiresAt())) { return "TOKEN_BINDING"; }
        if (token.consumed) { return "TOKEN_CONSUMED"; }
        if (!token.expiresAt.isAfter(now)) { return "TOKEN_EXPIRED"; }
        if (account.securityEpoch != intent.epoch()) { return "SECURITY_EPOCH"; }
        long generation = token.purpose == TokenPurpose.CONTACT ? account.contactGeneration : account.recoveryGeneration;
        return generation == token.generation ? null : "TOKEN_SUPERSEDED";
    }

    private void stop(DurableTokenDelivery.Intent intent, String reason) {
        String state = intent.providerId() != null ? "ACCEPTED" : intent.dispatchedAt() == null ? "FAILED" : "UNKNOWN";
        store.jdbc.update("update identity_token_delivery set state=?,available_at=null,lease_token=null,lease_until=null,reason=?,uncertain_at=case when dispatched_at is not null and provider_id is null then coalesce(uncertain_at,?) else uncertain_at end where id=?",
                state, reason, time(store.clock.instant()), intent.id());
    }

    void observe(Dispatch dispatch, ResendTokenClient.Result result) {
        standalone();
        transaction.executeWithoutResult(status -> {
            var found = store.find(dispatch.id(), true);
            if (found.isEmpty()) { return; }
            var current = found.orElseThrow(); Instant now = store.clock.instant();
            if (current.leaseGeneration() != dispatch.generation() || !dispatch.lease().equals(current.leaseToken())
                    || current.leaseUntil() == null || !current.leaseUntil().isAfter(now)) { return; }
            if (dispatch.providerId().isPresent()) { observeQuery(current, result, now); }
            else { observePost(current, result, now); }
        });
    }

    private void observePost(DurableTokenDelivery.Intent current, ResendTokenClient.Result result, Instant now) {
        if (result.outcome() == ResendTokenClient.Outcome.ACCEPTED && result.providerId().isPresent()) {
            store.jdbc.update("update identity_token_delivery set state='ACCEPTED',provider_id=?,accepted_at=coalesce(accepted_at,?),observed_at=?,available_at=?,lease_token=null,lease_until=null,reason=? where id=?",
                    result.providerId().orElseThrow(), time(now), time(now), time(now.plusSeconds(30)), result.reason(), current.id());
            return;
        }
        boolean rejected = result.outcome() == ResendTokenClient.Outcome.REJECTED;
        boolean uncertain = !rejected || current.uncertainAt() != null;
        String state = rejected && !uncertain ? "FAILED" : "UNKNOWN";
        Instant retry = !rejected && current.attempts() < 3 ? now.plusSeconds(Math.max(current.attempts() == 1 ? 5 : 30, result.retrySeconds())) : null;
        store.jdbc.update("update identity_token_delivery set state=?,observed_at=?,available_at=?,lease_token=null,lease_until=null,reason=?,uncertain_at=case when ? then coalesce(uncertain_at,?) else uncertain_at end where id=?",
                state, time(now), time(retry), result.reason(), uncertain, time(now), current.id());
    }

    private void observeQuery(DurableTokenDelivery.Intent current, ResendTokenClient.Result result, Instant now) {
        String state = switch (result.outcome()) {
            case DELIVERED -> "DELIVERED";
            case TERMINAL_FAILURE -> "FAILED";
            default -> "ACCEPTED";
        };
        Instant retry = state.equals("ACCEPTED") && current.observations() < 6 ? now.plusSeconds(Math.max(30, result.retrySeconds())) : null;
        store.jdbc.update("update identity_token_delivery set state=?,observed_at=?,available_at=?,lease_token=null,lease_until=null,reason=?,delivered_at=case when ? then coalesce(delivered_at,?) else delivered_at end where id=?",
                state, time(now), time(retry), result.reason(), state.equals("DELIVERED"), time(now), current.id());
    }

    private static void standalone() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Token worker requires independent transactions"); }
    }

    static final class Dispatch {
        private final UUID id;
        private final long generation;
        private final UUID lease;
        private final Optional<UUID> providerId;
        private final byte[] body;
        Dispatch(UUID id, long generation, UUID lease, Optional<UUID> providerId, byte[] body) {
            this.id = id; this.generation = generation; this.lease = lease; this.providerId = providerId; this.body = body.clone();
        }
        UUID id() { return id; }
        long generation() { return generation; }
        UUID lease() { return lease; }
        Optional<UUID> providerId() { return providerId; }
        byte[] body() { return body.clone(); }
    }
}
