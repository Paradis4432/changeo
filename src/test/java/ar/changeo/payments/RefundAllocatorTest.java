package ar.changeo.payments;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RefundAllocatorTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    @Test void shouldResolveEqualRemaindersBySourceIdRegardlessOfInputOrder() {
        assertThat(RefundAllocator.allocate(1, List.of(new RefundAllocator.Weight(SECOND, 1), new RefundAllocator.Weight(FIRST, 1))))
                .containsExactly(new RefundAllocator.Share(FIRST, 1));
    }

    @Test void shouldRecalculateSuccessiveCentsAgainstRemainingSourcesWithoutExceedingCaps() {
        Map<UUID, Long> remaining = new HashMap<>(Map.of(FIRST, 17L, SECOND, 29L));
        Map<UUID, Long> refunded = new HashMap<>();
        for (int cent = 0; cent < 46; cent++) {
            var shares = RefundAllocator.allocate(1, remaining.entrySet().stream().filter(value -> value.getValue() > 0)
                    .map(value -> new RefundAllocator.Weight(value.getKey(), value.getValue())).toList());
            assertThat(shares.stream().mapToLong(RefundAllocator.Share::amountMinor).sum()).isEqualTo(1);
            for (var share : shares) {
                remaining.compute(share.sourceId(), (key, balance) -> Math.subtractExact(balance, share.amountMinor()));
                refunded.merge(share.sourceId(), share.amountMinor(), Math::addExact);
            }
            assertThat(remaining.values()).allMatch(value -> value >= 0);
        }
        assertThat(refunded).containsExactlyInAnyOrderEntriesOf(Map.of(FIRST, 17L, SECOND, 29L));
        assertThat(remaining.values()).containsOnly(0L);
    }

    @Test void shouldAvoidIntermediateMultiplicationOverflow() {
        long first = Long.MAX_VALUE / 2;
        long second = Long.MAX_VALUE - first;
        assertThat(RefundAllocator.allocate(Long.MAX_VALUE, List.of(new RefundAllocator.Weight(FIRST, first), new RefundAllocator.Weight(SECOND, second))))
                .containsExactly(new RefundAllocator.Share(FIRST, first), new RefundAllocator.Share(SECOND, second));
    }

    @Test void shouldRejectOverflowAndInvalidCapsRatherThanRoundingThem() {
        assertThatThrownBy(() -> RefundAllocator.allocate(1, List.of(new RefundAllocator.Weight(FIRST, Long.MAX_VALUE), new RefundAllocator.Weight(SECOND, 1))))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> RefundAllocator.allocate(3, List.of(new RefundAllocator.Weight(FIRST, 2)))).isInstanceOf(PaymentFailure.class);
        assertThatThrownBy(() -> RefundAllocator.allocate(1, List.of(new RefundAllocator.Weight(FIRST, 2), new RefundAllocator.Weight(FIRST, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundAllocator.Weight(FIRST, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
