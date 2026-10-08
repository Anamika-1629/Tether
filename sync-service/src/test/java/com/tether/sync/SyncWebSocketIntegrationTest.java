package com.tether.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tether.sync.crdt.VarUintUtils;
import com.tether.sync.crdt.YjsProtocolConstants;
import com.tether.sync.security.IncidentAccessValidator;
import com.tether.sync.security.IncidentAccessValidator.Decision;
import com.tether.sync.security.JwtTokenValidator;
import com.tether.sync.service.RedisPubSubRelay;
import com.tether.sync.websocket.SyncWebSocketHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests of the sync service over a real WebSocket on a random port, with real clients
 * speaking the y-websocket binary protocol. Ports the scenarios of frontend/sync-dev-server/server.test.js:
 * live edits, concurrent edits, history replay and offline merge, presence, and auth.
 *
 * Only the Incident Service answer and Redis are replaced by mocks. The update log uses the in-memory
 * store (see application-test.properties). There is no Yjs library on the JVM, so "updates" are opaque
 * byte arrays: the server never parses them, it stores and relays them, which is what is under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SyncWebSocketIntegrationTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    private static final String ISSUER = "tether-auth";
    private static final long WAIT_MS = 5_000;
    private static final String TENANT_A = UUID.randomUUID().toString();
    private static final String TENANT_B = UUID.randomUUID().toString();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenValidator jwtValidator;

    @Autowired
    private MeterRegistry meterRegistry;

    // Stand-ins for the Incident Service check and for Redis, so the test needs no other process.
    @MockBean
    private IncidentAccessValidator access;
    @MockBean
    private RedisPubSubRelay redisRelay;
    @MockBean
    private RedisMessageListenerContainer redisListenerContainer;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Client> clients = new ArrayList<>();
    private String incident;

    @BeforeEach
    void setUp() {
        // A fresh incident per test keeps rooms (and their stored history) isolated from each other.
        incident = UUID.randomUUID().toString();
        // Every incident belongs to tenant A only, like the real Incident Service would answer.
        when(access.check(any(), any())).thenAnswer(inv -> {
            JwtTokenValidator.Claims claims = jwtValidator.validateToken(inv.<String>getArgument(1));
            return claims != null && TENANT_A.equals(claims.tenantId()) ? Decision.ALLOWED : Decision.DENIED;
        });
    }

    @AfterEach
    void tearDown() {
        clients.forEach(Client::closeQuietly);
    }

    // ---- scenario 1: live edits -------------------------------------------------

    @Test
    void liveEdit_reachesTheOtherResponderInBothDirectionsAndIsNotEchoedBack() throws Exception {
        Client alice = connect("alice");
        Client bob = connect("bob");
        alice.await(SyncWebSocketIntegrationTest::isQueryAwareness); // proves bob is registered in the room

        byte[] fromAlice = bytes("Checkout is down");
        alice.send(syncUpdate(fromAlice));
        bob.await(f -> isSyncUpdate(f, fromAlice));

        byte[] fromBob = bytes(", DB pool exhausted");
        bob.send(syncUpdate(fromBob));
        alice.await(f -> isSyncUpdate(f, fromBob));

        alice.assertNoFrame(f -> isSyncUpdate(f, fromAlice), 300);
    }

    // ---- scenario 2: concurrent edits ------------------------------------------

    @Test
    void concurrentEdits_madeAtTheSameMomentAreBothDelivered() throws Exception {
        Client carol = connect("carol");
        Client dave = connect("dave");
        carol.await(SyncWebSocketIntegrationTest::isQueryAwareness);

        byte[] a = bytes("[A] ");
        byte[] b = bytes(" [B]");
        carol.send(syncUpdate(a));
        dave.send(syncUpdate(b));

        dave.await(f -> isSyncUpdate(f, a));
        carol.await(f -> isSyncUpdate(f, b));
    }

    // ---- scenario 3: history replay for late joiners ---------------------------

    @Test
    void lateJoiner_receivesStoredHistoryBeforeStep2() throws Exception {
        Client alice = connect("alice");
        Client observer = connect("observer");
        alice.await(SyncWebSocketIntegrationTest::isQueryAwareness);

        byte[] u1 = bytes("first note");
        byte[] u2 = bytes("second note");
        alice.send(syncUpdate(u1));
        alice.send(syncUpdate(u2));
        // The server stores an update before it relays it, so once the observer has both, they are stored.
        observer.await(f -> isSyncUpdate(f, u1));
        observer.await(f -> isSyncUpdate(f, u2));

        Client erin = connect("erin");
        erin.send(syncStep1());
        erin.await(f -> isSyncUpdate(f, u1));
        erin.await(f -> isSyncUpdate(f, u2));
        erin.await(f -> isSyncStep(f, YjsProtocolConstants.SYNC_STEP2));
    }

    // ---- scenario 4: offline merge ----------------------------------------------

    @Test
    void offlineMerge_editsMadeWhileDisconnectedAreExchangedOnReconnect() throws Exception {
        Client online = connect("frank");
        Client laptop = connect("grace");
        online.await(SyncWebSocketIntegrationTest::isQueryAwareness);

        laptop.close();
        laptop.awaitClose();

        // Frank keeps working while Grace is offline. The server has to keep it for her.
        byte[] onlineNote = bytes(" ONLINE NOTE");
        online.send(syncUpdate(onlineNote));

        // Grace comes back (a new socket, like a browser reconnect) and asks for what she missed.
        Client laptopBack = connect("grace");
        laptopBack.send(syncStep1());
        laptopBack.await(f -> isSyncUpdate(f, onlineNote));

        // Her offline edit is uploaded after reconnecting and reaches Frank.
        byte[] offlineNote = bytes("OFFLINE NOTE ");
        laptopBack.send(syncUpdate(offlineNote));
        online.await(f -> isSyncUpdate(f, offlineNote));
    }

    // ---- scenario 5: presence ---------------------------------------------------

    @Test
    void presence_isRelayedAndRemovedWhenTheResponderDisconnects() throws Exception {
        Client heidi = connect("heidi");
        Client ivan = connect("ivan");
        heidi.await(SyncWebSocketIntegrationTest::isQueryAwareness);

        long ivanClientId = 4242;
        ivan.send(awarenessFrame(ivanClientId, 1, "{\"user\":{\"name\":\"ivan\"}}"));
        heidi.await(f -> isAwarenessFor(f, ivanClientId, false));

        ivan.close();
        heidi.await(f -> isAwarenessFor(f, ivanClientId, true));
    }

    // ---- scenario 6: auth -------------------------------------------------------

    @Test
    void forgedExpiredOrMissingToken_isClosedWith4401() throws Exception {
        assertClosedWith(YjsProtocolConstants.CLOSE_UNAUTHORIZED,
                connect("mallory", token(TENANT_A, "another-secret-that-is-at-least-32-bytes-long!!", 3600)));
        assertClosedWith(YjsProtocolConstants.CLOSE_UNAUTHORIZED, connect("mallory", "not-a-jwt"));
        assertClosedWith(YjsProtocolConstants.CLOSE_UNAUTHORIZED,
                connect("mallory", token(TENANT_A, SECRET, -60)));
        assertClosedWith(YjsProtocolConstants.CLOSE_UNAUTHORIZED, connect("mallory", ""));
    }

    @Test
    void userFromAnotherTenant_isClosedWith4403() throws Exception {
        assertClosedWith(YjsProtocolConstants.CLOSE_FORBIDDEN,
                connect("eve", token(TENANT_B, SECRET, 3600)));
    }

    @Test
    void incidentServiceUnavailable_isRejectedWithoutAStopRetryingCode() throws Exception {
        when(access.check(any(), any())).thenReturn(Decision.UNAVAILABLE);
        int code = connect("alice").awaitClose().getCode();
        // The server sends 1013 (asserted exactly in SyncWebSocketHandlerAccessTest). Tomcat's own test
        // client does not accept 1013 and reports it as 1002 (protocol error); browsers pass 1013 through.
        assertTrue(code == YjsProtocolConstants.CLOSE_TRY_AGAIN_LATER || code == 1002,
                "unexpected close code " + code);
    }

    @Test
    void rejectedConnections_areCountedByReason() throws Exception {
        double before = rejections("unauthorized");
        assertClosedWith(YjsProtocolConstants.CLOSE_UNAUTHORIZED, connect("mallory", "not-a-jwt"));
        assertEquals(before + 1, rejections("unauthorized"));
    }

    private double rejections(String reason) {
        return meterRegistry.counter("tether.sync.rejections", "reason", reason).count();
    }

    // ---- isolation --------------------------------------------------------------

    @Test
    void updatesStayInsideTheirOwnIncidentRoom() throws Exception {
        Client alice = connect("alice");
        Client bob = connect("bob");
        Client other = connect("carol", token(TENANT_A, SECRET, 3600), UUID.randomUUID().toString());
        alice.await(SyncWebSocketIntegrationTest::isQueryAwareness);

        byte[] secret = bytes("only for this incident");
        alice.send(syncUpdate(secret));
        bob.await(f -> isSyncUpdate(f, secret));
        other.assertNoFrame(f -> isSyncUpdate(f, secret), 300);
    }

    // ---- client -----------------------------------------------------------------

    /** A real WebSocket client that records every binary frame the server sends it. */
    private final class Client extends BinaryWebSocketHandler {
        final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        volatile WebSocketSession session;

        @Override
        protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) {
            ByteBuffer payload = message.getPayload();
            byte[] copy = new byte[payload.remaining()];
            payload.get(copy);
            frames.add(copy);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }

        void send(byte[] frame) throws IOException {
            session.sendMessage(new BinaryMessage(frame));
        }

        void close() throws IOException {
            session.close(CloseStatus.NORMAL);
        }

        void closeQuietly() {
            try {
                if (session != null && session.isOpen()) {
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception ignored) {
                // test cleanup only
            }
        }

        /** Skips frames until one matches (join, awareness and step frames are interleaved). */
        byte[] await(Predicate<byte[]> match) throws InterruptedException {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    fail("timed out waiting for an expected frame");
                }
                byte[] frame = frames.poll(left, TimeUnit.MILLISECONDS);
                if (frame != null && match.test(frame)) {
                    return frame;
                }
            }
        }

        void assertNoFrame(Predicate<byte[]> match, long forMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + forMs;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                byte[] frame = frames.poll(left, TimeUnit.MILLISECONDS);
                if (frame != null && match.test(frame)) {
                    fail("received a frame that should not have been delivered");
                }
            }
        }

        CloseStatus awaitClose() throws Exception {
            return closed.get(WAIT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private Client connect(String name) throws Exception {
        return connect(name, token(TENANT_A, SECRET, 3600));
    }

    private Client connect(String name, String token) throws Exception {
        return connect(name, token, incident);
    }

    private Client connect(String name, String token, String incidentId) throws Exception {
        Client client = new Client();
        clients.add(client);
        URI uri = URI.create("ws://localhost:" + port + "/sync/" + incidentId + "?token=" + token);
        client.session = new StandardWebSocketClient()
                .execute(client, new WebSocketHttpHeaders(), uri)
                .get(WAIT_MS, TimeUnit.MILLISECONDS);
        return client;
    }

    private void assertClosedWith(int expectedCode, Client client) throws Exception {
        assertEquals(expectedCode, client.awaitClose().getCode());
    }

    // ---- frame helpers ----------------------------------------------------------

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] syncUpdate(byte[] update) {
        return SyncWebSocketHandler.encodeSync(YjsProtocolConstants.SYNC_UPDATE, update);
    }

    private static byte[] syncStep1() {
        return SyncWebSocketHandler.encodeSync(YjsProtocolConstants.SYNC_STEP1, YjsProtocolConstants.EMPTY_STATE_VECTOR);
    }

    private static byte[] awarenessFrame(long clientId, int clock, String stateJson) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        VarUintUtils.writeVarUint(payload, 1);
        VarUintUtils.writeVarUint(payload, (int) clientId);
        VarUintUtils.writeVarUint(payload, clock);
        VarUintUtils.writeVarString(payload, stateJson);
        return SyncWebSocketHandler.encodeMessage(YjsProtocolConstants.MESSAGE_AWARENESS, payload.toByteArray());
    }

    private static boolean isQueryAwareness(byte[] frame) {
        return frame.length >= 1 && frame[0] == YjsProtocolConstants.MESSAGE_QUERY_AWARENESS;
    }

    private static boolean isSyncStep(byte[] frame, int syncType) {
        try {
            ByteBuffer b = ByteBuffer.wrap(frame);
            return VarUintUtils.readVarUint(b) == YjsProtocolConstants.MESSAGE_SYNC
                    && VarUintUtils.readVarUint(b) == syncType;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isSyncUpdate(byte[] frame, byte[] expectedUpdate) {
        try {
            ByteBuffer b = ByteBuffer.wrap(frame);
            return VarUintUtils.readVarUint(b) == YjsProtocolConstants.MESSAGE_SYNC
                    && VarUintUtils.readVarUint(b) == YjsProtocolConstants.SYNC_UPDATE
                    && Arrays.equals(VarUintUtils.readVarUint8Array(b), expectedUpdate);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True for an awareness frame that mentions {@code clientId}; {@code removed} means its state is "null". */
    private static boolean isAwarenessFor(byte[] frame, long clientId, boolean removed) {
        try {
            ByteBuffer b = ByteBuffer.wrap(frame);
            if (VarUintUtils.readVarUint(b) != YjsProtocolConstants.MESSAGE_AWARENESS) {
                return false;
            }
            ByteBuffer update = ByteBuffer.wrap(VarUintUtils.readVarUint8Array(b));
            int count = VarUintUtils.readVarUint(update);
            for (int i = 0; i < count; i++) {
                long id = VarUintUtils.readVarUint(update);
                VarUintUtils.readVarUint(update); // clock
                String state = VarUintUtils.readVarString(update);
                if (id == clientId && "null".equals(state) == removed) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- token helper -----------------------------------------------------------

    private String token(String tenantId, String secret, long validForSeconds) throws Exception {
        Map<String, Object> payload = Map.of(
                "iss", ISSUER,
                "tenantId", tenantId,
                "email", "user@acme.test",
                "userId", UUID.randomUUID().toString(),
                "exp", Instant.now().getEpochSecond() + validForSeconds);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String body = enc.encodeToString(mapper.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = enc.encodeToString(mac.doFinal((header + "." + body).getBytes(StandardCharsets.UTF_8)));
        return header + "." + body + "." + sig;
    }
}
