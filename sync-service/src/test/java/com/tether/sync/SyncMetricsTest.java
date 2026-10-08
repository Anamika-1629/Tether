package com.tether.sync;

import com.tether.sync.metrics.SyncMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncMetricsTest {

    private SimpleMeterRegistry registry;
    private SyncMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SyncMetrics(registry);
    }

    @Test
    void activeSessionsGoesUpAndDownAndNeverBelowZero() {
        metrics.sessionOpened("room", "alice", 0);
        metrics.sessionOpened("room", "bob", 0);
        assertEquals(2.0, active());

        metrics.sessionClosed("room", "alice", 1_000);
        assertEquals(1.0, active());

        metrics.sessionClosed("room", "bob", 1_000);
        metrics.sessionClosed("room", "ghost", 1_000);
        assertEquals(0.0, active());
    }

    @Test
    void rejoiningTheSameRoomQuicklyCountsAsAReconnect() {
        metrics.sessionOpened("room", "alice", 0);
        metrics.sessionClosed("room", "alice", 1_000);
        metrics.sessionOpened("room", "alice", 11_000);

        assertEquals(1.0, reconnects());
    }

    @Test
    void aNewUserOrALongAbsenceOrAnotherRoomIsNotAReconnect() {
        metrics.sessionOpened("room", "alice", 0);
        metrics.sessionClosed("room", "alice", 1_000);

        metrics.sessionOpened("room", "bob", 2_000);                 // never been here
        metrics.sessionOpened("other-room", "alice", 3_000);         // different room
        metrics.sessionOpened("room", "alice", 1_000 + 120_000);     // far outside the window

        assertEquals(0.0, reconnects());
    }

    @Test
    void rejectionsAreCountedPerReason() {
        metrics.rejected("unauthorized");
        metrics.rejected("unauthorized");
        metrics.rejected("forbidden");

        assertEquals(2.0, rejections("unauthorized"));
        assertEquals(1.0, rejections("forbidden"));
        assertEquals(0.0, rejections("unavailable"));
    }

    @Test
    void relayLatencyIsRecordedAsATimer() {
        metrics.recordRelay(2_000_000);
        metrics.recordRelay(4_000_000);

        var timer = registry.get("tether.sync.relay.latency").timer();
        assertEquals(2, timer.count());
        assertEquals(6.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001);
    }

    private double active() {
        return registry.get("tether.sync.sessions.active").gauge().value();
    }

    private double reconnects() {
        return registry.get("tether.sync.reconnects").counter().count();
    }

    private double rejections(String reason) {
        return registry.counter("tether.sync.rejections", "reason", reason).count();
    }
}
