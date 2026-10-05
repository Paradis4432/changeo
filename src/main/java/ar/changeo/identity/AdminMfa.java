package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "identity_admin_mfa")
class AdminMfa {
    @Id UUID accountId;
    String encryptedSecret;
    long lastCounter = -1;
    protected AdminMfa() {}
}
