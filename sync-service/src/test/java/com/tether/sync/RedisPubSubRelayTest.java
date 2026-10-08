package com.tether.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.sync.crdt.YjsProtocolConstants;
import com.tether.sync.service.RedisPubSubRelay;
import com.tether.sync.service.RoomManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Publish-side recovery (A4): after a Redis failure the relay pauses for a cooldown, then tries again,
 * and keeps publishing once Redis answers. Before A4 one failure switched cross-instance relay off for good.
 */
class RedisPubSubRelayTest {

    private static final long COOLDOWN_MS = 100;

    private StringRedisTemplate redis;
    private RedisPubSubRelay relay;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        relay = new RedisPubSubRelay(redis, mock(RoomManager.class), new ObjectMapper(), "topic", COOLDOWN_MS);
    }

    @Test
    void afterAFailure_publishingPausesForTheCooldownInsteadOfHammeringRedis() {
        doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(eq("topic"), anyString());

        publish();
        publish();
        publish();

        verify(redis, times(1)).convertAndSend(eq("topic"), anyString());
    }

    @Test
    void afterTheCooldown_itTriesAgainAndKeepsPublishingOnceRedisIsBack() throws Exception {
        doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(eq("topic"), anyString());
        publish();

        Thread.sleep(COOLDOWN_MS * 3);
        reset(redis); // Redis is back: the mock now accepts publishes
        publish();
        publish();

        verify(redis, times(2)).convertAndSend(eq("topic"), anyString());
    }

    private void publish() {
        relay.publish("tenant:incident", YjsProtocolConstants.MESSAGE_SYNC, new byte[] {1, 2, 3});
    }
}
