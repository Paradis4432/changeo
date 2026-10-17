package ar.changeo.subscriptions.api;

import ar.changeo.identity.api.SubjectId;
import java.time.Instant;
import java.util.Optional;

public interface LoyaltyPublicAccess {
    Optional<Achievement> current(SubjectId provider);
    record Achievement(int verifiedMilestone, String label, String ruleVersion, Instant observedAt) {
        public Achievement {
            if (verifiedMilestone < 1 || verifiedMilestone > 2) { throw new IllegalArgumentException("Hito público incompatible"); }
        }
    }
}
