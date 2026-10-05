package ar.changeo.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AbuseLimits {
    private static final int MAX_KEYS = 4096;
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;

    AbuseLimits(Clock clock) { this.clock = clock; }

    public synchronized boolean allow(String key, int maximum) {
        Instant now = clock.instant();
        windows.values().removeIf(window -> !window.expires.isAfter(now));
        Window window = windows.get(AccountService.hash(key));
        if (window == null) {
            if (windows.size() >= MAX_KEYS) { return false; }
            window = new Window(now.plusSeconds(60)); windows.put(AccountService.hash(key), window);
        }
        return ++window.count <= maximum;
    }

    private static final class Window {
        private final Instant expires;
        private int count;
        private Window(Instant expires) { this.expires = expires; }
    }
}
