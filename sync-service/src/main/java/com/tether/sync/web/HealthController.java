package com.tether.sync.web;

import com.tether.sync.service.RoomManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final RoomManager roomManager;

    public HealthController(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "sync-service",
                "rooms", roomManager.getRoomCount(),
                "activeConnections", roomManager.getTotalActiveConnections()
        ));
    }
}
