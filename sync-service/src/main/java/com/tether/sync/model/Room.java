package com.tether.sync.model;

import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents an active collaborative incident room.
 * Holds active WebSocket sessions, ordered Yjs CRDT updates log,
 * and current awareness clocks per session.
 */
public class Room {

    private final String roomKey;
    private final String tenantId;
    private final String incidentId;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final List<byte[]> updates = new CopyOnWriteArrayList<>();
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

    public List<byte[]> getUpdates() {
        return updates;
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

    public void appendUpdate(byte[] update) {
        updates.add(update);
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }
}
