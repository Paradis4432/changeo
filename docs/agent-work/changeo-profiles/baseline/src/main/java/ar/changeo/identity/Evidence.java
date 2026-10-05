package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "identity_verification_evidence")
class Evidence {
    @Id UUID id;
    @Column(insertable = false, updatable = false) long ordinal;
    UUID accountId;
    @Enumerated(EnumType.STRING) EvidenceKind kind;
    Integer age;
    Instant expiresAt;
    String scope;
    String provenance;
    Instant createdAt;
    protected Evidence() {}
}
