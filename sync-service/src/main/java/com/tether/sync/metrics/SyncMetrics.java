package com.tether.sync.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics of the sync service (NFR2). Prometheus names in brackets.
 *
 *  - tether.sync.sessions.active  gauge    [tether_sync_sessions_active]            open responder connections in rooms
 *  - tether.sync.relay.latency    timer    [tether_sync_relay_latency_seconds_*]    time to store one update and fan it out
 *  - tether.sync.reconnects       counter  [tether_sync_reconnects_total]           a responder rejoined a room shortly after leaving it
 *  - tether.sync.rejections       counter  [tether_sync_rejections_total{reason}]   connections refused: unauthorized, forbidden, unavailable, bad_request
 */
@Component
public class SyncMetrics {

    /** A join by the same user in the same room within this window after leaving counts as a reconnect. */
    static final long REJOIN_WINDOW_MS = 60_000;
    private static final int PRUNE_ABOVE = 1_000;

    private final MeterRegistry registry;
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final Timer relayLatency;
    private final Counter reconnects;
    private final ConcurrentHashMap<String, Long> recentLeaves = new ConcurrentHashMap<>();

    public SyncMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("tether.sync.sessions.active", activeSessions, AtomicInteger::get)
                .description("Open responder WebSocket connections that joined a room")
                .register(registry);
        this.relayLatency = Timer.builder("tether.sync.relay.latency")
                .description("Time to store one document update and fan it out to the room and to Redis")
                .publishPercentileHistogram()
                .register(registry);
        this.reconnects = Counter.builder("tether.sync.reconnects")
                .description("Responders who rejoined a room shortly after leaving it")
                .register(registry);
    }

    public void sessionOpened(String roomKey, String userKey, long nowMs) {
        activeSessions.incrementAndGet();
        Long leftAt = recentLeaves.remove(roomKey + "|" + userKey);
        if (leftAt != null && nowMs - leftAt <= REJOIN_WINDOW_MS) {
            reconnects.increment();
        }
    }

    public void sessionClosed(String roomKey, String userKey, long nowMs) {
        activeSessions.updateAndGet(n -> Math.max(0, n - 1));
        recentLeaves.put(roomKey + "|" + userKey, nowMs);
        if (recentLeaves.size() > PRUNE_ABOVE) {
            recentLeaves.values().removeIf(leftAt -> nowMs - leftAt > REJOIN_WINDOW_MS);
        }
    }

    public void recordRelay(long nanos) {
        relayLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void rejected(String reason) {
        registry.counter("tether.sync.rejections", "reason", reason).increment();
    }
}
