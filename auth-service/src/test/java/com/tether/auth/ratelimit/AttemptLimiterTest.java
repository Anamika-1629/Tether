package com.tether.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    /** A clock the test can move forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private final TestClock clock = new TestClock();
    private final AttemptLimiter limiter = new AttemptLimiter(3, Duration.ofMinutes(15), clock);

    @Test
    void blocksOnlyOnceTheLimitIsReached() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertThat(limiter.blockedFor("k")).isZero();
        limiter.recordFailure("k");
        assertThat(limiter.blockedFor("k")).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void blockLiftsWhenTheWindowEnds() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("k");
        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.blockedFor("k")).isEqualTo(Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.blockedFor("k")).isZero();
        limiter.recordFailure("k");
        assertThat(limiter.blockedFor("k")).isZero(); // a fresh window starts at 1 failure
    }

    @Test
    void resetClearsFailures() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("k");
        limiter.reset("k");
        assertThat(limiter.blockedFor("k")).isZero();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("attacker");
        assertThat(limiter.blockedFor("attacker")).isPositive();
        assertThat(limiter.blockedFor("someone-else")).isZero();
    }
}
