package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "identity_audit_event")
class AuditEvent {
    @Id UUID id;
    UUID actorId;
    UUID subjectId;
    String action;
    Instant occurredAt;
    String scope;
    protected AuditEvent() {}
}
