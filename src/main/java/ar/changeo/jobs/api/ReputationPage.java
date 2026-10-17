package ar.changeo.jobs.api;

import java.time.Instant;
import java.util.*;

public record ReputationPage(Map<Integer, Integer> histogram, int eligibleCount, int observedRows, Instant observedAt,
        Optional<ReputationCursor> continuation) {
    public ReputationPage {
        histogram = Map.copyOf(histogram); Objects.requireNonNull(observedAt); Objects.requireNonNull(continuation);
        if (observedRows < 0 || observedRows > 10 || eligibleCount < 0 || eligibleCount > observedRows
                || histogram.entrySet().stream().anyMatch(entry -> entry.getKey() < 1 || entry.getKey() > 5 || entry.getValue() < 0)
                || histogram.values().stream().mapToInt(Integer::intValue).sum() != eligibleCount) { throw new IllegalArgumentException("Página de reputación inválida"); }
    }
}
