package com.tether.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failures per key inside a fixed window and blocks the key once it hits the limit.
 * In-memory, so each instance counts on its own; move to Redis when the service is scaled out.
 */
public class AttemptLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /** How long the key is still blocked for, or Duration.ZERO if it may try again. */
    public Duration blockedFor(String key) {
        Window w = windows.get(key);
        if (w == null) return Duration.ZERO;
        Instant now = clock.instant();
        if (!now.isBefore(w.resetsAt())) {
            windows.remove(key, w);
            return Duration.ZERO;
        }
        return w.failures() >= maxFailures ? Duration.between(now, w.resetsAt()) : Duration.ZERO;
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        windows.compute(key, (k, w) -> (w == null || !now.isBefore(w.resetsAt()))
                ? new Window(1, now.plus(window))
                : new Window(w.failures() + 1, w.resetsAt()));
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> !now.isBefore(w.resetsAt()));
        }
    }

    public void reset(String key) {
        windows.remove(key);
    }

    private record Window(int failures, Instant resetsAt) {}
}
