package ar.changeo.identity;

import java.util.Optional;

final class TokenDeliveryCoordinator {
    private final TokenDelivery local;
    private final Optional<DurableTokenDelivery> durable;

    TokenDeliveryCoordinator(TokenDelivery local, Optional<DurableTokenDelivery> durable) {
        this.local = local; this.durable = durable;
    }

    Preparation prepare(ContactToken token, long epoch, String recipient, String raw) {
        if (durable.isPresent()) {
            return durable.orElseThrow().prepare(token, epoch, recipient, raw);
        }
        return local.deliver(token.accountId, token.purpose.name(), raw) ? Preparation.LOCAL_DELIVERED : Preparation.FAILED;
    }

    enum Preparation { LOCAL_DELIVERED, QUEUED, FAILED }
}
