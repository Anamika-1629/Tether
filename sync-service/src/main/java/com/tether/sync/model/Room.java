package com.tether.sync.model;

import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents an active collaborative incident room.
 * Holds active WebSocket sessions and current awareness clocks per session.
 * The Yjs update history is NOT kept here: it lives in the shared UpdateLogStore, so a room
 * costs almost nothing in memory and is dropped as soon as its last session leaves.
 */
public class Room {

    private final String roomKey;
    private final String tenantId;
    private final String incidentId;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /** Epoch millis when a compaction was requested from one of this instance's sessions; 0 = none. */
    private final AtomicLong compactionStartedAt = new AtomicLong();
    private final Map<String, Map<Long, Long>> sessionAwareness = new ConcurrentHashMap<>();

    public Room(String tenantId, String incidentId) {
        this.tenantId = tenantId;
        this.incidentId = incidentId;
        this.roomKey = tenantId + ":" + incidentId;
    }

    public String getRoomKey() {
        return roomKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public Set<WebSocketSession> getSessions() {
        return sessions;
    }

    public void addSession(WebSocketSession session) {
        sessions.add(session);
        sessionAwareness.put(session.getId(), new ConcurrentHashMap<>());
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
    }

    public Map<Long, Long> getAwarenessClients(String sessionId) {
        return sessionAwareness.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
    }

    public Map<Long, Long> removeAwarenessClients(String sessionId) {
        return sessionAwareness.remove(sessionId);
    }

    /** True if this instance may start a compaction now. A stuck attempt is retried after {@code timeoutMs}. */
    public boolean tryBeginCompaction(long nowMs, long timeoutMs) {
        long started = compactionStartedAt.get();
        if (started != 0 && nowMs - started < timeoutMs) return false;
        return compactionStartedAt.compareAndSet(started, nowMs);
    }

    public void endCompaction() {
        compactionStartedAt.set(0);
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }
}
