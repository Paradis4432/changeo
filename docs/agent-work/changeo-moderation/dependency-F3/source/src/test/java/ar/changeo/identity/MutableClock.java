package ar.changeo.identity;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));
    void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
    void reset() { now.set(Instant.parse("2026-10-01T00:00:00Z")); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
}
