package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "identity_job_consent")
class JobConsent {
    @Id UUID id;
    UUID linkId;
    UUID jobId;
    String mode;
    @Enumerated(EnumType.STRING) ConsentStatus status;
    @Version long version;
    protected JobConsent() {}
}
