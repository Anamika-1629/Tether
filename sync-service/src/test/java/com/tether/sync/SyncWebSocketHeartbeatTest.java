package com.tether.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.sync.crdt.VarUintUtils;
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
import org.springframework.web.socket.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Heartbeat: dead connections (no pong, no traffic) are dropped and removed from presence.
 * Time is faked, so "30 seconds of silence" runs instantly.
 */
class SyncWebSocketHeartbeatTest {

    private static final String SECRET = "dev-only-insecure-secret-change-me-0123456789";
    private static final String ISSUER = "tether-auth";
    private static final String INCIDENT = "11111111-2222-3333-4444-555555555555";
    private static final String TENANT = "99999999-8888-7777-6666-555555555555";
    private static final long TIMEOUT_MS = 30_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private RoomManager rooms;
    private SyncWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        IncidentAccessValidator access = mock(IncidentAccessValidator.class);
        when(access.check(any(), any())).thenReturn(Decision.ALLOWED);
        rooms = new RoomManager();
        handler = new SyncWebSocketHandler(new JwtTokenValidator(SECRET, ISSUER, mapper), access, rooms,
                mock(RedisPubSubRelay.class), mock(UpdateLogStore.class), mock(LogCompactor.class), TIMEOUT_MS);
        handler.setClock(now::get);
    }

    @Test
    void sweepSendsAPingToEveryLiveSession() throws Exception {
        WebSocketSession a = join("a");
        WebSocketSession b = join("b");

        advance(10_000);
        handler.sweepHeartbeats();

        verify(a).sendMessage(any(PingMessage.class));
        verify(b).sendMessage(any(PingMessage.class));
    }

    @Test
    void silentSessionIsDroppedAndPeersAreToldItLeft() throws Exception {
        WebSocketSession alice = join("alice");
        WebSocketSession bob = join("bob");
        announcePresence(bob, 42);                 // bob has a presence entry peers can see

        advance(20_000);
        pong(alice);                               // alice's browser answers the ping, bob's is gone
        handler.sweepHeartbeats();
        verify(bob, never()).close(any(CloseStatus.class));   // 20s of silence is still within the timeout

        advance(15_000);                           // bob silent for 35s, alice for 15s
        handler.sweepHeartbeats();

        ArgumentCaptor<CloseStatus> closed = ArgumentCaptor.forClass(CloseStatus.class);
        verify(bob).close(closed.capture());
        assertEquals(CloseStatus.GOING_AWAY.getCode(), closed.getValue().getCode());
        verify(alice, never()).close(any(CloseStatus.class));

        assertEquals(1, rooms.getRooms().iterator().next().getSessions().size(), "only alice remains");
        assertEquals(1, presenceRemovalFramesSentTo(alice), "alice is told bob's presence ended");
    }

    @Test
    void anyTrafficKeepsASessionAlive() throws Exception {
        WebSocketSession alice = join("alice");

        for (int i = 0; i < 12; i++) {             // 2 minutes, alice only ever sends awareness updates
            advance(10_000);
            announcePresence(alice, 7);
            handler.sweepHeartbeats();
        }

        verify(alice, never()).close(any(CloseStatus.class));
        assertEquals(1, rooms.getRoomCount());
    }

    @Test
    void lastSilentSessionRemovesTheRoom() throws Exception {
        WebSocketSession bob = join("bob");

        advance(TIMEOUT_MS + 1);
        handler.sweepHeartbeats();

        verify(bob).close(any(CloseStatus.class));
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    void sessionIsStillCleanedUpWhenCloseFailsOnABrokenConnection() throws Exception {
        WebSocketSession alice = join("alice");
        WebSocketSession bob = join("bob");
        announcePresence(bob, 42);
        doThrow(new IOException("connection reset")).when(bob).close(any(CloseStatus.class));

        advance(TIMEOUT_MS + 1);
        pong(alice);
        handler.sweepHeartbeats();

        assertEquals(1, rooms.getRooms().iterator().next().getSessions().size());
        assertEquals(1, presenceRemovalFramesSentTo(alice));
    }

    @Test
    void cleanupRunsOnlyOnceEvenIfTheContainerAlsoReportsTheClose() throws Exception {
        WebSocketSession alice = join("alice");
        WebSocketSession bob = join("bob");
        announcePresence(bob, 42);

        advance(TIMEOUT_MS + 1);
        pong(alice);
        handler.sweepHeartbeats();
        handler.afterConnectionClosed(bob, CloseStatus.GOING_AWAY);   // the late close callback

        assertEquals(1, presenceRemovalFramesSentTo(alice), "no duplicate removal broadcast");
    }

    @Test
    void aFailedPingDoesNotCrashTheSweepOrDropTheSessionEarly() throws Exception {
        WebSocketSession bob = join("bob");
        doThrow(new IOException("broken pipe")).when(bob).sendMessage(any(PingMessage.class));

        advance(10_000);
        assertDoesNotThrow(handler::sweepHeartbeats);

        verify(bob, never()).close(any(CloseStatus.class));
        assertEquals(1, rooms.getRoomCount());
    }

    // ---- helpers ---------------------------------------------------------------

    private void advance(long ms) { now.addAndGet(ms); }

    private void pong(WebSocketSession s) throws Exception { handler.handleMessage(s, new PongMessage()); }

    private WebSocketSession join(String name) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getUri()).thenReturn(URI.create("ws://localhost:8083/sync/" + INCIDENT + "?token=" + token(name)));
        when(s.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(s.getId()).thenReturn("session-" + name);
        when(s.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(s);
        return s;
    }

    /** Sends a y-protocols awareness update for one client id, so the server records a presence entry. */
    private void announcePresence(WebSocketSession s, int yjsClientId) throws Exception {
        ByteArrayOutputStream update = new ByteArrayOutputStream();
        VarUintUtils.writeVarUint(update, 1);               // one client in this update
        VarUintUtils.writeVarUint(update, yjsClientId);
        VarUintUtils.writeVarUint(update, 1);               // clock
        VarUintUtils.writeVarString(update, "{\"user\":\"x\"}");

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        VarUintUtils.writeVarUint(frame, YjsProtocolConstants.MESSAGE_AWARENESS);
        VarUintUtils.writeVarUint8Array(frame, update.toByteArray());
        handler.handleMessage(s, new BinaryMessage(frame.toByteArray()));
    }

    /** Counts awareness frames that mark a client as gone (state "null") sent to this session. */
    private int presenceRemovalFramesSentTo(WebSocketSession s) throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(s, atLeast(0)).sendMessage(captor.capture());
        int count = 0;
        List<WebSocketMessage<?>> sent = captor.getAllValues();
        for (WebSocketMessage<?> m : sent) {
            if (m instanceof BinaryMessage bm) {
                ByteBuffer buf = bm.getPayload().duplicate();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                if (bytes.length > 0 && bytes[0] == YjsProtocolConstants.MESSAGE_AWARENESS
                        && new String(bytes, StandardCharsets.UTF_8).contains("null")) {
                    count++;
                }
            }
        }
        return count;
    }

    private String token(String email) throws Exception {
        Map<String, Object> payload = Map.of(
                "iss", ISSUER,
                "tenantId", TENANT,
                "email", email + "@acme.test",
                "userId", UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8)).toString(),
                "exp", Instant.now().getEpochSecond() + 3600);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String body = enc.encodeToString(mapper.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return header + "." + body + "." + enc.encodeToString(mac.doFinal((header + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
