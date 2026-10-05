package ar.changeo.identity;

import jakarta.persistence.*;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

@Entity
@Table(name = "identity_account")
class Account {
    @Id UUID id;
    String contact;
    String passwordHash;
    boolean contactVerified;
    long securityEpoch;
    long contactGeneration;
    long recoveryGeneration;
    boolean sensitivePreference;
    boolean adultPreference;
    @Version long version;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "identity_account_role", joinColumns = @JoinColumn(name = "account_id"))
    @Column(name = "role") @Enumerated(EnumType.STRING)
    Set<AdminRole> roles = new HashSet<>();
    protected Account() {}
}
