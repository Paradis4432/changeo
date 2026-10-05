package ar.changeo.identity;

import ar.changeo.identity.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicIdentityBadgeService implements PublicIdentityBadgeAccess {
    private final IdentityStore store;
    private final SecurityProof proof;
    private final Clock clock;

    PublicIdentityBadgeService(IdentityStore store, SecurityProof proof, Clock clock) {
        this.store = store;
        this.proof = proof;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<AccountId, PublicIdentityBadge> findCurrent(Set<AccountId> profileSubjects) {
        Set<AccountId> subjects = Set.copyOf(profileSubjects);
        if (subjects.size() > 200) { throw new IdentityFailure("Proyección pública demasiado amplia"); }
        Set<UUID> accounts = new HashSet<>();
        subjects.forEach(subject -> accounts.add(subject.value()));
        proof.actor().ifPresent(accounts::add);
        store.lockAccounts(accounts);
        Map<AccountId, PublicIdentityBadge> result = new HashMap<>();
        for (AccountId subject : subjects) {
            if (store.restricted(subject.value())) { continue; }
            store.evidence(subject.value(), EvidenceKind.OPTIONAL_BADGE).filter(this::valid)
                    .ifPresent(evidence -> result.put(subject, new PublicIdentityBadge("SANDBOX_SYNTHETIC", evidence.createdAt,
                            evidence.expiresAt, "LOCAL_SYNTHETIC_CHECK")));
        }
        return Map.copyOf(result);
    }

    private boolean valid(Evidence evidence) {
        if (!"SANDBOX_SYNTHETIC".equals(evidence.scope) || evidence.createdAt == null
                || evidence.createdAt.isAfter(clock.instant()) || !evidence.expiresAt.isAfter(clock.instant())
                || !evidence.createdAt.isBefore(evidence.expiresAt) || evidence.age != null) { return false; }
        String prefix = "LOCAL_SYNTHETIC_OPERATOR:";
        if (evidence.provenance == null || !evidence.provenance.startsWith(prefix)) { return false; }
        try { return UUID.fromString(evidence.provenance.substring(prefix.length())).toString().equals(evidence.provenance.substring(prefix.length())); }
        catch (IllegalArgumentException failure) { return false; }
    }
}
