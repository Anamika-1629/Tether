package com.tether.sync.service;

import com.tether.sync.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active collaborative rooms in memory, keyed by "tenantId:incidentId".
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
        Room room = rooms.get(roomKey);
        if (room != null && room.isEmpty() && room.getUpdates().isEmpty()) {
            rooms.remove(roomKey);
            log.info("Removed empty room: {}", roomKey);
        }
    }

    public int getRoomCount() {
        return rooms.size();
    }

    public int getTotalActiveConnections() {
        return rooms.values().stream().mapToInt(r -> r.getSessions().size()).sum();
    }
}
