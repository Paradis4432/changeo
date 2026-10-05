package ar.changeo.identity;

import java.util.UUID;

public interface TokenDelivery {
    boolean deliver(UUID accountId, String purpose, String token);
}
