package com.tether.sync.store;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

/**
 * Update log kept in a Redis list per room, shared by every sync instance, so a restarted or newly
 * started instance serves the same history.
 *
 *   tether:sync:room:{roomKey}:updates   list of raw Yjs updates (binary), oldest first
 *   tether:sync:room:{roomKey}:lock      compaction lock (SET NX PX)
 *
 * Failure policy: if Redis is unreachable, appends and compaction are skipped and loads return an
 * empty log (with a warning). Live relay between connected clients keeps working, and clients
 * re-upload their own state when they reconnect, so the document heals from any client that has it.
 */
@Component
@ConditionalOnProperty(name = "tether.sync.store", havingValue = "redis", matchIfMissing = true)
public class RedisUpdateLogStore implements UpdateLogStore {

    private static final Logger log = LoggerFactory.getLogger(RedisUpdateLogStore.class);
    private static final String PREFIX = "tether:sync:room:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /** KEYS[1]=log, ARGV[1]=entries to drop, ARGV[2]=snapshot, ARGV[3]=ttl seconds (0 = none). Returns new length or -1. */
    private static final DefaultRedisScript<Long> REPLACE_PREFIX = new DefaultRedisScript<>("""
            local len = redis.call('LLEN', KEYS[1])
            local drop = tonumber(ARGV[1])
            if drop < 1 or len < drop then return -1 end
            redis.call('LTRIM', KEYS[1], drop, -1)
            redis.call('LPUSH', KEYS[1], ARGV[2])
            local ttl = tonumber(ARGV[3])
            if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
            return redis.call('LLEN', KEYS[1])
            """, Long.class);

    private final RedisTemplate<String, byte[]> redis;
    private final Duration ttl;
    private final byte[] instanceId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);

    public RedisUpdateLogStore(
            RedisTemplate<String, byte[]> updateLogRedisTemplate,
            @Value("${tether.sync.log-ttl:30d}") Duration ttl) {
        this.redis = updateLogRedisTemplate;
        this.ttl = ttl;
    }

    private static String logKey(String roomKey) { return PREFIX + roomKey + ":updates"; }
    private static String lockKey(String roomKey) { return PREFIX + roomKey + ":lock"; }

    @Override
    public long append(String roomKey, byte[] update) {
        try {
            String key = logKey(roomKey);
            Long len = redis.opsForList().rightPush(key, update);
            if (!ttl.isZero() && !ttl.isNegative()) redis.expire(key, ttl);
            return len == null ? 0 : len;
        } catch (Exception e) {
            log.warn("Could not persist update for room {}: {}", roomKey, e.getMessage());
            return 0;
        }
    }

    @Override
    public List<byte[]> load(String roomKey) {
        try {
            List<byte[]> updates = redis.opsForList().range(logKey(roomKey), 0, -1);
            return updates == null ? List.of() : updates;
        } catch (Exception e) {
            log.warn("Could not load update log for room {}: {}", roomKey, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean tryBeginCompaction(String roomKey) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(roomKey), instanceId, LOCK_TTL));
        } catch (Exception e) {
            log.warn("Could not take compaction lock for room {}: {}", roomKey, e.getMessage());
            return false;
        }
    }

    @Override
    public void endCompaction(String roomKey) {
        try {
            redis.delete(lockKey(roomKey));
        } catch (Exception e) {
            log.debug("Could not release compaction lock for room {} (it expires on its own): {}", roomKey, e.getMessage());
        }
    }

    @Override
    public long replacePrefix(String roomKey, int dropCount, byte[] snapshot) {
        try {
            Long len = redis.execute(REPLACE_PREFIX, RedisSerializer.byteArray(), new GenericToStringSerializer<>(Long.class),
                    List.of(logKey(roomKey)),
                    String.valueOf(dropCount).getBytes(StandardCharsets.US_ASCII),
                    snapshot,
                    String.valueOf(ttl.isNegative() ? 0 : ttl.toSeconds()).getBytes(StandardCharsets.US_ASCII));
            return len == null ? -1 : len;
        } catch (Exception e) {
            log.warn("Could not compact update log for room {}: {}", roomKey, e.getMessage());
            return -1;
        }
    }
}
