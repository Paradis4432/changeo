package ar.changeo.identity;

import java.util.Optional;
import java.util.UUID;

public interface SecurityProof {
    Optional<UUID> actor();
    boolean mfa(UUID account, long epoch);
    boolean freshPassword(UUID account, long epoch);
}
