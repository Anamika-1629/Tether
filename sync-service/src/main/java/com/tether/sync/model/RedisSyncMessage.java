package com.tether.sync.model;

/**
 * Message payload published to Redis Pub/Sub topic to relay real-time
 * CRDT document updates and presence events across multiple Sync Service instances.
 */
public record RedisSyncMessage(
        String instanceId,
        String roomKey,
        int messageType,
        String payloadBase64
) {}
