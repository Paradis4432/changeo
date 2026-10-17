package ar.changeo.payments.api;

import ar.changeo.identity.api.*;
import java.util.*;

public record PromotionClaim(String campaign, SubjectId provider, AccountId originalBillingPayer, List<UUID> evidenceJobIds,
        String evidenceDigest, String ruleVersion) {
    public PromotionClaim {
        Objects.requireNonNull(provider); Objects.requireNonNull(originalBillingPayer);
        evidenceJobIds = List.copyOf(evidenceJobIds);
        if (!"SL1B-LOYALTY-2".equals(campaign) || !campaign.equals(ruleVersion) || evidenceJobIds.size() != 2
                || new HashSet<>(evidenceJobIds).size() != 2 || !evidenceDigest.matches("[0-9a-f]{64}")) { throw new IllegalArgumentException("Evidencia promocional incompatible"); }
    }
}
