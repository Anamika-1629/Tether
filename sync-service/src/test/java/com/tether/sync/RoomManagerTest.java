package com.tether.sync;

import com.tether.sync.model.Room;
import com.tether.sync.service.RoomManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomManagerTest {

    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
    }

    @Test
    void testGetOrCreateRoom() {
        String tenantId = UUID.randomUUID().toString();
        String incidentId = UUID.randomUUID().toString();

        Room room1 = roomManager.getOrCreateRoom(tenantId, incidentId);
        assertNotNull(room1);
        assertEquals(tenantId + ":" + incidentId, room1.getRoomKey());

        Room room2 = roomManager.getOrCreateRoom(tenantId, incidentId);
        assertSame(room1, room2);
        assertEquals(1, roomManager.getRoomCount());
    }

    @Test
    void testRemoveRoomIfEmpty() {
        String tenantId = UUID.randomUUID().toString();
        String incidentId = UUID.randomUUID().toString();

        Room room = roomManager.getOrCreateRoom(tenantId, incidentId);
        assertEquals(1, roomManager.getRoomCount());

        roomManager.removeRoomIfEmpty(room.getRoomKey());
        assertEquals(0, roomManager.getRoomCount());
    }

    @Test
    void testRoomWithConnectedSessionIsKept() {
        Room room = roomManager.getOrCreateRoom(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        room.addSession(session);

        roomManager.removeRoomIfEmpty(room.getRoomKey());
        assertEquals(1, roomManager.getRoomCount());

        room.removeSession(session);
        roomManager.removeRoomIfEmpty(room.getRoomKey());
        assertEquals(0, roomManager.getRoomCount());
    }
}
