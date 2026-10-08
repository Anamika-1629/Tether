package com.tether.sync.websocket;

import com.tether.sync.crdt.VarUintUtils;
import com.tether.sync.crdt.YjsProtocolConstants;
import com.tether.sync.model.Room;
import com.tether.sync.security.IncidentAccessValidator;
import com.tether.sync.security.JwtTokenValidator;
import com.tether.sync.service.LogCompactor;
import com.tether.sync.service.RedisPubSubRelay;
import com.tether.sync.service.RoomManager;
import com.tether.sync.store.UpdateLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance WebSocket handler implementing the standard y-websocket protocol.
 * Handles binary frames for CRDT synchronization (SyncStep1, SyncStep2, SyncUpdate),
 * client awareness presence, and connection heartbeats.
 */
@Component
public class SyncWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SyncWebSocketHandler.class);
    private static final Pattern PATH_PATTERN = Pattern.compile("^/sync/([0-9a-fA-F-]+)$");
    private static final String ATTR_LAST_SEEN = "lastSeenMillis";
    private static final String ATTR_CLEANED_UP = "cleanedUp";

    private final JwtTokenValidator jwtTokenValidator;
    private final IncidentAccessValidator incidentAccessValidator;
    private final RoomManager roomManager;
    private final RedisPubSubRelay redisRelay;
    private final UpdateLogStore updateLog;
    private final LogCompactor compactor;
    private final long heartbeatTimeoutMs;
    private volatile LongSupplier clock = System::currentTimeMillis;

    public SyncWebSocketHandler(
            JwtTokenValidator jwtTokenValidator,
            IncidentAccessValidator incidentAccessValidator,
            RoomManager roomManager,
            RedisPubSubRelay redisRelay,
            UpdateLogStore updateLog,
            LogCompactor compactor,
            @Value("${tether.sync.heartbeat-timeout-ms:30000}") long heartbeatTimeoutMs) {
        this.jwtTokenValidator = jwtTokenValidator;
        this.incidentAccessValidator = incidentAccessValidator;
        this.roomManager = roomManager;
        this.redisRelay = redisRelay;
        this.updateLog = updateLog;
        this.compactor = compactor;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) {
            session.close(new CloseStatus(YjsProtocolConstants.CLOSE_FORBIDDEN, "Invalid URI"));
            return;
        }

        String path = uri.getPath();
        Matcher matcher = PATH_PATTERN.matcher(path);
        if (!matcher.matches()) {
            session.close(new CloseStatus(YjsProtocolConstants.CLOSE_FORBIDDEN, "Unknown room"));
            return;
        }

        String incidentId = matcher.group(1).toLowerCase();
        String token = extractQueryParam(uri.getQuery(), "token");

        JwtTokenValidator.Claims claims = jwtTokenValidator.validateToken(token);
        if (claims == null) {
            log.warn("Unauthorized connection rejected for incident {}", incidentId);
            session.close(new CloseStatus(YjsProtocolConstants.CLOSE_UNAUTHORIZED, "Invalid or expired token"));
            return;
        }

        IncidentAccessValidator.Decision access = incidentAccessValidator.check(incidentId, token);
        if (access == IncidentAccessValidator.Decision.UNAVAILABLE) {
            // Fail closed: cannot verify tenant access right now, so nobody joins. Retryable close code.
            log.warn("Rejected connection for incident {} from tenant {}: access check unavailable",
                    incidentId, claims.tenantId());
            session.close(new CloseStatus(YjsProtocolConstants.CLOSE_TRY_AGAIN_LATER,
                    "Cannot verify incident access, retry shortly"));
            return;
        }
        if (access == IncidentAccessValidator.Decision.DENIED) {
            log.warn("Forbidden connection for incident {} from tenant {}", incidentId, claims.tenantId());
            session.close(new CloseStatus(YjsProtocolConstants.CLOSE_FORBIDDEN, "No access to this incident"));
            return;
        }

        Room room = roomManager.getOrCreateRoom(claims.tenantId(), incidentId);
        session.getAttributes().put("room", room);
        session.getAttributes().put("claims", claims);
        touch(session);
        room.addSession(session);

        log.info("[sync] {} joined room {} ({} responders connected)",
                claims.email(), incidentId, room.getSessions().size());

        // Ask existing peers in the room to announce themselves to the newcomer
        byte[] queryMsg = encodeMessage(YjsProtocolConstants.MESSAGE_QUERY_AWARENESS, null);
        broadcastToRoom(room, queryMsg, session);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        touch(session);
        Room room = (Room) session.getAttributes().get("room");
        if (room == null) {
            return;
        }

        ByteBuffer buffer = message.getPayload();
        try {
            int messageType = VarUintUtils.readVarUint(buffer);

            if (messageType == YjsProtocolConstants.MESSAGE_SYNC) {
                int syncType = VarUintUtils.readVarUint(buffer);
                if (syncType == YjsProtocolConstants.SYNC_STEP1) {
                    // Client is connecting / resyncing: send the stored history (shared across instances,
                    // survives restarts), then empty step 2, then step 1
                    for (byte[] update : updateLog.load(room.getRoomKey())) {
                        sendBinary(session, encodeSync(YjsProtocolConstants.SYNC_UPDATE, update));
                    }
                    sendBinary(session, encodeSync(YjsProtocolConstants.SYNC_STEP2, YjsProtocolConstants.EMPTY_UPDATE));
                    sendBinary(session, encodeSync(YjsProtocolConstants.SYNC_STEP1, YjsProtocolConstants.EMPTY_STATE_VECTOR));
                    compactor.markFullStateRequested(session);
                } else if (syncType == YjsProtocolConstants.SYNC_STEP2 || syncType == YjsProtocolConstants.SYNC_UPDATE) {
                    byte[] update = VarUintUtils.readVarUint8Array(buffer);
                    if (syncType == YjsProtocolConstants.SYNC_STEP2 && compactor.consumeStep2(session, room, update)) {
                        return; // this was the full-state snapshot we asked for; it replaced the old log prefix
                    }
                    long logSize = updateLog.append(room.getRoomKey(), update);
                    byte[] syncFrame = encodeSync(YjsProtocolConstants.SYNC_UPDATE, update);
                    broadcastToRoom(room, syncFrame, session);
                    redisRelay.publish(room.getRoomKey(), YjsProtocolConstants.MESSAGE_SYNC, syncFrame);
                    compactor.maybeRequestSnapshot(session, room, logSize);
                }
            } else if (messageType == YjsProtocolConstants.MESSAGE_AWARENESS) {
                byte[] awarenessUpdate = VarUintUtils.readVarUint8Array(buffer);
                trackAwareness(session, room, awarenessUpdate);
                byte[] rawFrame = message.getPayload().array();
                broadcastToRoom(room, rawFrame, session);
                redisRelay.publish(room.getRoomKey(), YjsProtocolConstants.MESSAGE_AWARENESS, rawFrame);
            } else if (messageType == YjsProtocolConstants.MESSAGE_QUERY_AWARENESS) {
                broadcastToRoom(room, message.getPayload().array(), session);
            }
        } catch (Exception e) {
            log.warn("Dropped malformed message from session {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        touch(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanup(session);
    }

    /**
     * Every 10s: ping each open session, then drop sessions we have not heard from
     * (pong or any frame) within the timeout. Browsers answer pings automatically at the
     * protocol level, so a live tab always stays fresh; a laptop that lost wifi or a killed
     * process never answers and is removed from presence about 30-40s later.
     */
    @Scheduled(fixedRateString = "${tether.sync.heartbeat-interval-ms:10000}")
    public void sendHeartbeat() {
        sweepHeartbeats();
    }

    /** Lets tests control time instead of sleeping for 30 seconds. */
    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public void sweepHeartbeats() {
        long nowMillis = clock.getAsLong();
        for (Room room : roomManager.getRooms()) {
            for (WebSocketSession session : room.getSessions()) {
                long idleMs = nowMillis - lastSeen(session, nowMillis);
                if (idleMs > heartbeatTimeoutMs) {
                    dropDeadSession(session, idleMs);
                } else if (session.isOpen()) {
                    sendPing(session);
                }
            }
        }
    }

    private void sendPing(WebSocketSession session) {
        try {
            session.sendMessage(new PingMessage());
        } catch (Exception e) {
            // A failed ping is not fatal by itself: the missing pong is what gets the session dropped.
            log.debug("Ping to session {} failed: {}", session.getId(), e.getMessage());
        }
    }

    private void dropDeadSession(WebSocketSession session, long idleMs) {
        log.warn("[sync] Dropping session {} after {} ms without a pong", session.getId(), idleMs);
        try {
            session.close(CloseStatus.GOING_AWAY.withReason("Heartbeat timeout"));
        } catch (Exception e) {
            // On a half-dead TCP connection close() can fail or block; cleanup below does not depend on it.
            log.debug("Close of dead session {} failed: {}", session.getId(), e.getMessage());
        }
        cleanup(session);
    }

    /** Removes the session from its room and tells peers it left. Idempotent: runs once per session. */
    private void cleanup(WebSocketSession session) {
        if (session.getAttributes().putIfAbsent(ATTR_CLEANED_UP, Boolean.TRUE) != null) {
            return;
        }
        Room room = (Room) session.getAttributes().get("room");
        if (room == null) {
            return;
        }
        room.removeSession(session);
        compactor.onSessionClosed(session, room);
        broadcastAwarenessRemoval(session, room);
        roomManager.removeRoomIfEmpty(room.getRoomKey());
        JwtTokenValidator.Claims claims = (JwtTokenValidator.Claims) session.getAttributes().get("claims");
        String who = claims != null ? claims.email() : session.getId();
        log.info("[sync] {} left room {} ({} responders connected)", who, room.getIncidentId(), room.getSessions().size());
    }

    private void touch(WebSocketSession session) {
        AtomicLong lastSeen = (AtomicLong) session.getAttributes()
                .computeIfAbsent(ATTR_LAST_SEEN, k -> new AtomicLong());
        lastSeen.set(clock.getAsLong());
    }

    private long lastSeen(WebSocketSession session, long fallback) {
        Object v = session.getAttributes().get(ATTR_LAST_SEEN);
        return v instanceof AtomicLong a ? a.get() : fallback;
    }

    private void trackAwareness(WebSocketSession session, Room room, byte[] updateBytes) {
        ByteBuffer dec = ByteBuffer.wrap(updateBytes);
        int count = VarUintUtils.readVarUint(dec);
        Map<Long, Long> clients = room.getAwarenessClients(session.getId());
        for (int i = 0; i < count; i++) {
            long clientId = VarUintUtils.readVarUint(dec);
            long clock = VarUintUtils.readVarUint(dec);
            String state = VarUintUtils.readVarString(dec);
            if ("null".equals(state)) {
                clients.remove(clientId);
            } else {
                clients.put(clientId, clock);
            }
        }
    }

    private void broadcastAwarenessRemoval(WebSocketSession session, Room room) {
        Map<Long, Long> clients = room.removeAwarenessClients(session.getId());
        if (clients == null || clients.isEmpty()) {
            return;
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        VarUintUtils.writeVarUint(payload, clients.size());
        for (Map.Entry<Long, Long> entry : clients.entrySet()) {
            VarUintUtils.writeVarUint(payload, entry.getKey().intValue());
            VarUintUtils.writeVarUint(payload, entry.getValue().intValue() + 1);
            VarUintUtils.writeVarString(payload, "null");
        }

        byte[] frame = encodeMessage(YjsProtocolConstants.MESSAGE_AWARENESS, payload.toByteArray());
        broadcastToRoom(room, frame, null);
        redisRelay.publish(room.getRoomKey(), YjsProtocolConstants.MESSAGE_AWARENESS, frame);
    }

    private void broadcastToRoom(Room room, byte[] frame, WebSocketSession sender) {
        BinaryMessage binMsg = new BinaryMessage(frame);
        for (WebSocketSession s : room.getSessions()) {
            if (s != sender && s.isOpen()) {
                try {
                    s.sendMessage(binMsg);
                } catch (IOException e) {
                    log.warn("Failed to send frame to session {}: {}", s.getId(), e.getMessage());
                }
            }
        }
    }

    private void sendBinary(WebSocketSession session, byte[] frame) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new BinaryMessage(frame));
            } catch (IOException e) {
                log.warn("Failed to send binary message to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    public static byte[] encodeSync(int syncType, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarUintUtils.writeVarUint(out, YjsProtocolConstants.MESSAGE_SYNC);
        VarUintUtils.writeVarUint(out, syncType);
        VarUintUtils.writeVarUint8Array(out, payload);
        return out.toByteArray();
    }

    public static byte[] encodeMessage(int type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarUintUtils.writeVarUint(out, type);
        if (payload != null && payload.length > 0) {
            VarUintUtils.writeVarUint8Array(out, payload);
        }
        return out.toByteArray();
    }

    private String extractQueryParam(String query, String paramName) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(paramName)) {
                return kv[1];
            }
        }
        return null;
    }
}
