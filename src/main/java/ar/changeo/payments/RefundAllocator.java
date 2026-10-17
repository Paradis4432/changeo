package ar.changeo.payments;

import java.math.BigInteger;
import java.util.*;

final class RefundAllocator {
    private RefundAllocator() {}

    static List<Share> allocate(long amountMinor, List<Weight> weights) {
        if (amountMinor <= 0 || weights.isEmpty()) { throw new IllegalArgumentException("Reembolso positivo y fuentes requeridos"); }
        long total = 0;
        Set<UUID> sources = new HashSet<>();
        for (var weight : weights) {
            if (!sources.add(weight.sourceId())) { throw new IllegalArgumentException("Fuente duplicada"); }
            total = Math.addExact(total, weight.remainingMinor());
        }
        if (amountMinor > total) { throw new PaymentFailure("El reembolso excede el saldo reembolsable"); }
        var divisor = BigInteger.valueOf(total);
        List<Fraction> fractions = new ArrayList<>();
        long allocated = 0;
        for (var weight : weights) {
            var result = BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(weight.remainingMinor())).divideAndRemainder(divisor);
            long floor = result[0].longValueExact();
            allocated = Math.addExact(allocated, floor);
            fractions.add(new Fraction(weight.sourceId(), floor, result[1]));
        }
        fractions.sort(Comparator.comparing(Fraction::remainder).reversed().thenComparing(value -> value.sourceId().toString()));
        long residual = Math.subtractExact(amountMinor, allocated);
        List<Share> shares = new ArrayList<>();
        for (int index = 0; index < fractions.size(); index++) {
            var fraction = fractions.get(index);
            long share = Math.addExact(fraction.floor(), index < residual ? 1 : 0);
            if (share > 0) { shares.add(new Share(fraction.sourceId(), share)); }
        }
        shares.sort(Comparator.comparing(value -> value.sourceId().toString()));
        return List.copyOf(shares);
    }

    record Weight(UUID sourceId, long remainingMinor) {
        Weight { Objects.requireNonNull(sourceId); if (remainingMinor <= 0) { throw new IllegalArgumentException("Saldo de fuente positivo requerido"); } }
    }
    record Share(UUID sourceId, long amountMinor) {}
    private record Fraction(UUID sourceId, long floor, BigInteger remainder) {}
}
