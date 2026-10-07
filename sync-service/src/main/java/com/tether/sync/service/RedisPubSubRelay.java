package com.tether.sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.sync.crdt.VarUintUtils;
import com.tether.sync.crdt.YjsProtocolConstants;
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

import java.nio.ByteBuffer;
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
    private boolean redisAvailable = true;

    public RedisPubSubRelay(
            StringRedisTemplate redisTemplate,
            RoomManager roomManager,
            ObjectMapper objectMapper,
            @Value("${tether.sync.redis-topic:tether:sync:events}") String topic) {
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
        if (!redisAvailable) {
            return;
        }

        try {
            String b64 = Base64.getEncoder().encodeToString(payload);
            RedisSyncMessage syncMessage = new RedisSyncMessage(instanceId, roomKey, messageType, b64);
            String json = objectMapper.writeValueAsString(syncMessage);
            redisTemplate.convertAndSend(topic, json);
        } catch (Exception e) {
            log.warn("Redis publish failed (will continue locally): {}", e.getMessage());
            redisAvailable = false;
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

            // If it's a sync update, also keep it in the room's history
            if (syncMsg.messageType() == YjsProtocolConstants.MESSAGE_SYNC) {
                try {
                    ByteBuffer buf = ByteBuffer.wrap(payload);
                    int type = VarUintUtils.readVarUint(buf);
                    if (type == YjsProtocolConstants.MESSAGE_SYNC) {
                        int syncType = VarUintUtils.readVarUint(buf);
                        if (syncType == YjsProtocolConstants.SYNC_UPDATE || syncType == YjsProtocolConstants.SYNC_STEP2) {
                            byte[] update = VarUintUtils.readVarUint8Array(buf);
                            room.appendUpdate(update);
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Could not parse sync update for room history: {}", ex.getMessage());
                }
            }

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
