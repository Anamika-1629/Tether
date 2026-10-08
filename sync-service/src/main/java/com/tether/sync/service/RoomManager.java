package com.tether.sync.service;

import com.tether.sync.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the rooms that currently have connected sessions on this instance, keyed by "tenantId:incidentId".
 */
@Service
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public Room getOrCreateRoom(String tenantId, String incidentId) {
        String key = tenantId + ":" + incidentId;
        return rooms.computeIfAbsent(key, k -> {
            log.info("Created collaborative room: {}", key);
            return new Room(tenantId, incidentId);
        });
    }

    public Room getRoom(String roomKey) {
        return rooms.get(roomKey);
    }

    public void removeRoomIfEmpty(String roomKey) {
        // History lives in the UpdateLogStore, so an empty room can always be dropped from memory.
        boolean[] removed = {false};
        rooms.computeIfPresent(roomKey, (k, room) -> {
            if (room.isEmpty()) {
                removed[0] = true;
                return null;
            }
            return room;
        });
        if (removed[0]) log.info("Removed empty room: {}", roomKey);
    }

    /** Snapshot of the current rooms, safe to iterate while rooms are added or removed. */
    public Collection<Room> getRooms() {
        return new ArrayList<>(rooms.values());
    }

    public int getRoomCount() {
        return rooms.size();
    }

    public int getTotalActiveConnections() {
        return rooms.values().stream().mapToInt(r -> r.getSessions().size()).sum();
    }
}
