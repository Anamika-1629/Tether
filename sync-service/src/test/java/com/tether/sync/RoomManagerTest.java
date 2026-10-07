package com.tether.sync;

import com.tether.sync.model.Room;
import com.tether.sync.service.RoomManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

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
    void testRoomUpdateAppend() {
        String tenantId = UUID.randomUUID().toString();
        String incidentId = UUID.randomUUID().toString();

        Room room = roomManager.getOrCreateRoom(tenantId, incidentId);
        byte[] update1 = new byte[] { 1, 2, 3 };
        byte[] update2 = new byte[] { 4, 5, 6 };

        room.appendUpdate(update1);
        room.appendUpdate(update2);

        assertEquals(2, room.getUpdates().size());
        assertArrayEquals(update1, room.getUpdates().get(0));
        assertArrayEquals(update2, room.getUpdates().get(1));
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
}
