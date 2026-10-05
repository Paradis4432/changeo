package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "identity_guardian_link")
class GuardianLink {
    @Id UUID id;
    UUID guardianId;
    UUID minorId;
    @Enumerated(EnumType.STRING) LinkStatus status;
    Instant authorityExpiresAt;
    String provenance;
    @Version long version;
    protected GuardianLink() {}
}
