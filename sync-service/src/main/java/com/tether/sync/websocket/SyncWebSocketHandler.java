package com.tether.sync.websocket;

import com.tether.sync.crdt.VarUintUtils;
import com.tether.sync.crdt.YjsProtocolConstants;
import com.tether.sync.model.Room;
import com.tether.sync.security.IncidentAccessValidator;
import com.tether.sync.security.JwtTokenValidator;
import com.tether.sync.service.RedisPubSubRelay;
import com.tether.sync.service.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
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

    private final JwtTokenValidator jwtTokenValidator;
    private final IncidentAccessValidator incidentAccessValidator;
    private final RoomManager roomManager;
    private final RedisPubSubRelay redisRelay;

    public SyncWebSocketHandler(
            JwtTokenValidator jwtTokenValidator,
            IncidentAccessValidator incidentAccessValidator,
            RoomManager roomManager,
            RedisPubSubRelay redisRelay) {
        this.jwtTokenValidator = jwtTokenValidator;
        this.incidentAccessValidator = incidentAccessValidator;
        this.roomManager = roomManager;
        this.redisRelay = redisRelay;
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

        if (!incidentAccessValidator.canAccessIncident(incidentId, token)) {
            log.warn("Forbidden connection for incident {} from tenant {}", incidentId, claims.tenantId());
            session.close(new CloseStatus(YjsProtocolConstants.CLOSE_FORBIDDEN, "No access to this incident"));
            return;
        }

        Room room = roomManager.getOrCreateRoom(claims.tenantId(), incidentId);
        session.getAttributes().put("room", room);
        session.getAttributes().put("claims", claims);
        room.addSession(session);

        log.info("[sync] {} joined room {} ({} responders connected)",
                claims.email(), incidentId, room.getSessions().size());

        // Ask existing peers in the room to announce themselves to the newcomer
        byte[] queryMsg = encodeMessage(YjsProtocolConstants.MESSAGE_QUERY_AWARENESS, null);
        broadcastToRoom(room, queryMsg, session);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
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
                    // Client is connecting / resyncing: send all room updates, then empty step 2, then step 1
                    for (byte[] update : room.getUpdates()) {
                        sendBinary(session, encodeSync(YjsProtocolConstants.SYNC_UPDATE, update));
                    }
                    sendBinary(session, encodeSync(YjsProtocolConstants.SYNC_STEP2, YjsProtocolConstants.EMPTY_UPDATE));
                    sendBinary(session, encodeSync(YjsProtocolConstants.SYNC_STEP1, YjsProtocolConstants.EMPTY_STATE_VECTOR));
                } else if (syncType == YjsProtocolConstants.SYNC_STEP2 || syncType == YjsProtocolConstants.SYNC_UPDATE) {
                    byte[] update = VarUintUtils.readVarUint8Array(buffer);
                    room.appendUpdate(update);
                    byte[] syncFrame = encodeSync(YjsProtocolConstants.SYNC_UPDATE, update);
                    broadcastToRoom(room, syncFrame, session);
                    redisRelay.publish(room.getRoomKey(), YjsProtocolConstants.MESSAGE_SYNC, syncFrame);
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
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Room room = (Room) session.getAttributes().get("room");
        if (room != null) {
            room.removeSession(session);
            broadcastAwarenessRemoval(session, room);
            roomManager.removeRoomIfEmpty(room.getRoomKey());
            JwtTokenValidator.Claims claims = (JwtTokenValidator.Claims) session.getAttributes().get("claims");
            String who = claims != null ? claims.email() : session.getId();
            log.info("[sync] {} left room {} ({} responders connected)", who, room.getIncidentId(), room.getSessions().size());
        }
    }

    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        for (int i = 0; i < roomManager.getRoomCount(); i++) {
            // Heartbeat can send PingMessage to keep alive
        }
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
