package com.tether.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tether.sync.crdt.YjsProtocolConstants;
import com.tether.sync.model.Room;
import com.tether.sync.service.LogCompactor;
import com.tether.sync.store.InMemoryUpdateLogStore;
import com.tether.sync.websocket.SyncWebSocketHandler;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/** Threshold 10, keep newest 3, so a 12-entry log replaces its first 9 entries. */
class LogCompactorTest {

    private static final byte[] SNAPSHOT = {99, 99};

    private InMemoryUpdateLogStore store;
    private LogCompactor compactor;
    private Room room;

    @BeforeEach
    void setUp() {
        store = new InMemoryUpdateLogStore();
        compactor = new LogCompactor(store, 10, 3);
        room = new Room("tenant", "incident");
    }

    private static WebSocketSession session() {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        when(s.getAttributes()).thenReturn(attrs);
        when(s.isOpen()).thenReturn(true);
        when(s.getId()).thenReturn(UUID.randomUUID().toString());
        return s;
    }

    private static byte[] u(int i) { return new byte[] {(byte) i}; }

    private long fillLog(int entries) {
        long size = 0;
        for (int i = 0; i < entries; i++) size = store.append(room.getRoomKey(), u(i));
        return size;
    }

    private static int id(byte[] entry) { return entry.length == 1 ? entry[0] : -1; } // -1 = the snapshot

    @Test
    void belowThresholdDoesNothing() throws Exception {
        WebSocketSession s = session();
        compactor.maybeRequestSnapshot(s, room, fillLog(9));
        verify(s, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void atThresholdAsksForFullStateWithAnEmptyStateVector() throws Exception {
        WebSocketSession s = session();
        compactor.maybeRequestSnapshot(s, room, fillLog(12));

        ArgumentCaptor<WebSocketMessage<?>> sent = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(s, times(1)).sendMessage(sent.capture());
        ByteBuffer payload = ((BinaryMessage) sent.getValue()).getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        assertArrayEquals(SyncWebSocketHandler.encodeSync(
                YjsProtocolConstants.SYNC_STEP1, YjsProtocolConstants.EMPTY_STATE_VECTOR), bytes);
    }

    @Test
    void snapshotReplacesOldEntriesAndKeepsTheNewestThree() {
        WebSocketSession s = session();
        compactor.maybeRequestSnapshot(s, room, fillLog(12));

        assertTrue(compactor.consumeStep2(s, room, SNAPSHOT));

        List<byte[]> log = store.load(room.getRoomKey());
        assertEquals(4, log.size());
        assertArrayEquals(SNAPSHOT, log.get(0));
        assertEquals(List.of(9, 10, 11), log.subList(1, 4).stream().map(LogCompactorTest::id).toList());
    }

    @Test
    void editsThatArriveWhileTheSnapshotIsInFlightAreKept() {
        WebSocketSession s = session();
        compactor.maybeRequestSnapshot(s, room, fillLog(12));
        store.append(room.getRoomKey(), u(12));
        store.append(room.getRoomKey(), u(13));

        compactor.consumeStep2(s, room, SNAPSHOT);

        List<byte[]> log = store.load(room.getRoomKey());
        assertEquals(6, log.size());
        assertArrayEquals(u(13), log.get(5));
    }

    @Test
    void connectTimeStep2IsNotMistakenForASnapshot() throws Exception {
        WebSocketSession s = session();
        long size = fillLog(12);
        compactor.markFullStateRequested(s); // server sent Step 1 on connect; client has not answered yet

        compactor.maybeRequestSnapshot(s, room, size);
        verify(s, never()).sendMessage(any(WebSocketMessage.class));

        assertFalse(compactor.consumeStep2(s, room, u(7)), "ordinary reply, not a snapshot");
        assertEquals(12, store.load(room.getRoomKey()).size());

        compactor.maybeRequestSnapshot(s, room, size);
        verify(s, times(1)).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void onlyOneCompactionPerRoomAtATime() throws Exception {
        WebSocketSession a = session();
        WebSocketSession b = session();
        long size = fillLog(12);

        compactor.maybeRequestSnapshot(a, room, size);
        compactor.maybeRequestSnapshot(b, room, size);

        verify(a, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(b, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void closingTheAskedSessionLetsAnotherTakeOver() throws Exception {
        WebSocketSession a = session();
        WebSocketSession b = session();
        long size = fillLog(12);
        compactor.maybeRequestSnapshot(a, room, size);

        compactor.onSessionClosed(a, room);
        compactor.maybeRequestSnapshot(b, room, size);

        verify(b, times(1)).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void anotherInstanceCannotCompactTheSameRoomConcurrently() throws Exception {
        LogCompactor otherInstance = new LogCompactor(store, 10, 3); // shares the store (like two nodes on one Redis)
        Room otherInstanceRoom = new Room("tenant", "incident");
        WebSocketSession a = session();
        WebSocketSession b = session();
        long size = fillLog(12);

        compactor.maybeRequestSnapshot(a, room, size);
        otherInstance.maybeRequestSnapshot(b, otherInstanceRoom, size);

        verify(a, times(1)).sendMessage(any(WebSocketMessage.class));
        verify(b, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void disabledWhenThresholdIsZero() throws Exception {
        LogCompactor off = new LogCompactor(store, 0, 3);
        WebSocketSession s = session();
        off.maybeRequestSnapshot(s, room, fillLog(500));
        verify(s, never()).sendMessage(any(WebSocketMessage.class));
    }
}
