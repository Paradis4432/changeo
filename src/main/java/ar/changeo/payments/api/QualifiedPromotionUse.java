package ar.changeo.payments.api;

import java.util.Objects;

public record QualifiedPromotionUse(long minor, String evidenceDigest) {
    public QualifiedPromotionUse {
        Objects.requireNonNull(evidenceDigest);
        if ((minor != 0 && minor != 100) || !evidenceDigest.matches("[0-9a-f]{64}")) { throw new IllegalArgumentException("Crédito incompatible"); }
    }
}
