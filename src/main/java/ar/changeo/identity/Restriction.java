package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "identity_restriction")
class Restriction {
    @Id UUID id;
    UUID accountId;
    boolean active;
    String reason;
    Instant createdAt;
    protected Restriction() {}
}
