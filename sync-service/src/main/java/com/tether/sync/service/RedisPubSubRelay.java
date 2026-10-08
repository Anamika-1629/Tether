package com.tether.sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.sync.model.RedisSyncMessage;
import com.tether.sync.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.UUID;

/**
 * Cross-instance message relay using Redis Pub/Sub.
 * Enables horizontal scalability across multiple sync-service instances
 * while maintaining eventual consistency across collaborative rooms.
 */
@Service
public class RedisPubSubRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubRelay.class);

    private final String instanceId = UUID.randomUUID().toString();
    private final StringRedisTemplate redisTemplate;
    private final RoomManager roomManager;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final long retryCooldownMs;
    /** 0 means publishing is allowed. Otherwise do not try Redis again before this time (epoch ms). */
    private volatile long nextRetryAtMs = 0;

    public RedisPubSubRelay(
            StringRedisTemplate redisTemplate,
            RoomManager roomManager,
            ObjectMapper objectMapper,
            @Value("${tether.sync.redis-topic:tether:sync:events}") String topic,
            @Value("${tether.sync.redis-retry-cooldown-ms:5000}") long retryCooldownMs) {
        this.retryCooldownMs = retryCooldownMs;
        this.redisTemplate = redisTemplate;
        this.roomManager = roomManager;
        this.objectMapper = objectMapper;
        this.topic = topic;
        log.info("Initialized RedisPubSubRelay for instance {}", instanceId);
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Publishes a raw binary frame to Redis for cross-node replication.
     */
    public void publish(String roomKey, int messageType, byte[] payload) {
        long now = System.currentTimeMillis();
        if (now < nextRetryAtMs) {
            return;
        }

        try {
            String b64 = Base64.getEncoder().encodeToString(payload);
            RedisSyncMessage syncMessage = new RedisSyncMessage(instanceId, roomKey, messageType, b64);
            String json = objectMapper.writeValueAsString(syncMessage);
            redisTemplate.convertAndSend(topic, json);
            if (nextRetryAtMs != 0) {
                nextRetryAtMs = 0;
                log.info("Redis publish recovered, cross-instance relay resumed");
            }
        } catch (Exception e) {
            if (nextRetryAtMs == 0) {
                log.warn("Redis publish failed, relaying locally only and retrying every {} ms: {}",
                        retryCooldownMs, e.getMessage());
            }
            nextRetryAtMs = now + retryCooldownMs;
        }
    }

    /**
     * Handles incoming broadcast messages from other sync-service instances.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            RedisSyncMessage syncMsg = objectMapper.readValue(json, RedisSyncMessage.class);

            // Skip messages originating from this instance
            if (instanceId.equals(syncMsg.instanceId())) {
                return;
            }

            Room room = roomManager.getRoom(syncMsg.roomKey());
            if (room == null || room.isEmpty()) {
                return;
            }

            byte[] payload = Base64.getDecoder().decode(syncMsg.payloadBase64());

            // History is NOT recorded here: the instance that received the edit already wrote it to the
            // shared UpdateLogStore before publishing, so every instance sees the same log.

            // Relay to all local WebSocket sessions in this room
            BinaryMessage binMsg = new BinaryMessage(payload);
            for (WebSocketSession session : room.getSessions()) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(binMsg);
                    } catch (Exception ex) {
                        log.warn("Failed to relay Redis message to session {}: {}", session.getId(), ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process incoming Redis Pub/Sub message: {}", e.getMessage());
        }
    }
}
