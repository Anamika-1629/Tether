package com.tether.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.sync.crdt.YjsProtocolConstants;
import com.tether.sync.security.IncidentAccessValidator;
import com.tether.sync.security.IncidentAccessValidator.Decision;
import com.tether.sync.security.JwtTokenValidator;
import com.tether.sync.service.LogCompactor;
import com.tether.sync.service.RedisPubSubRelay;
import com.tether.sync.service.RoomManager;
import com.tether.sync.store.UpdateLogStore;
import com.tether.sync.websocket.SyncWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Connect-time access rules of the handler: who gets into a room, and which close code the rest get.
 * Uses the real handler, validator and room manager; only the socket, Redis relay and the
 * Incident Service answer are stubbed.
 */
class SyncWebSocketHandlerAccessTest {

    private static final String SECRET = "dev-only-insecure-secret-change-me-0123456789";
    private static final String ISSUER = "tether-auth";
    private static final String INCIDENT = "11111111-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();
    private IncidentAccessValidator access;
    private RoomManager rooms;
    private SyncWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        access = mock(IncidentAccessValidator.class);
        rooms = new RoomManager();
        handler = new SyncWebSocketHandler(
                new JwtTokenValidator(SECRET, ISSUER, mapper), access, rooms, mock(RedisPubSubRelay.class),
                mock(UpdateLogStore.class), mock(LogCompactor.class), 30_000);
    }

    @Test
    void incidentServiceDown_closesWithRetryableCodeAndNeverJoinsTheRoom() throws Exception {
        when(access.check(eq(INCIDENT), any())).thenReturn(Decision.UNAVAILABLE);
        WebSocketSession session = session(validToken());

        handler.afterConnectionEstablished(session);

        assertEquals(YjsProtocolConstants.CLOSE_TRY_AGAIN_LATER, closeCode(session));
        assertEquals(0, rooms.getRoomCount(), "a rejected connection must not create or join a room");
    }

    @Test
    void retryableCodeIsNotA44xxCodeBecauseTheClientStopsRetryingOn44xx() {
        int code = YjsProtocolConstants.CLOSE_TRY_AGAIN_LATER;
        assertEquals(false, code >= 4400 && code < 4500);
    }

    @Test
    void noAccess_closesWith4403() throws Exception {
        when(access.check(eq(INCIDENT), any())).thenReturn(Decision.DENIED);
        WebSocketSession session = session(validToken());

        handler.afterConnectionEstablished(session);

        assertEquals(YjsProtocolConstants.CLOSE_FORBIDDEN, closeCode(session));
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    void invalidToken_closesWith4401WithoutAskingTheIncidentService() throws Exception {
        WebSocketSession session = session("not-a-jwt");

        handler.afterConnectionEstablished(session);

        assertEquals(YjsProtocolConstants.CLOSE_UNAUTHORIZED, closeCode(session));
        verify(access, never()).check(any(), any());
    }

    @Test
    void allowed_joinsTheRoomAndIsNotClosed() throws Exception {
        when(access.check(eq(INCIDENT), any())).thenReturn(Decision.ALLOWED);
        WebSocketSession session = session(validToken());

        handler.afterConnectionEstablished(session);

        verify(session, never()).close(any(CloseStatus.class));
        assertEquals(1, rooms.getRoomCount());
    }

    // ---- helpers ---------------------------------------------------------------

    private int closeCode(WebSocketSession session) throws Exception {
        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        return captor.getValue().getCode();
    }

    private WebSocketSession session(String token) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getUri()).thenReturn(URI.create("ws://localhost:8083/sync/" + INCIDENT + "?token=" + token));
        when(s.getAttributes()).thenReturn(new HashMap<>());
        when(s.getId()).thenReturn("session-1");
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    private String validToken() throws Exception {
        Map<String, Object> payload = Map.of(
                "iss", ISSUER,
                "tenantId", UUID.randomUUID().toString(),
                "email", "alice@acme.test",
                "userId", UUID.randomUUID().toString(),
                "exp", Instant.now().getEpochSecond() + 3600);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String body = enc.encodeToString(mapper.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = enc.encodeToString(mac.doFinal((header + "." + body).getBytes(StandardCharsets.UTF_8)));
        return header + "." + body + "." + sig;
    }
}
