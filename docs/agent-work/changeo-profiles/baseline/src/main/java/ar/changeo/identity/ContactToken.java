package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "identity_contact_token")
class ContactToken {
    @Id UUID id;
    UUID accountId;
    @Enumerated(EnumType.STRING) TokenPurpose purpose;
    String tokenHash;
    long generation;
    Instant expiresAt;
    boolean consumed;
    protected ContactToken() {}
}
