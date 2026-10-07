# Tether Sync Service

> **Member 3 — Sync Service (WebSocket + CRDT + Presence)**  
> Technical Heart of Tether: Conflict-free concurrent incident notes collaboration and real-time presence relay.

---

## 1. Overview

The **Sync Service** delivers the collaborative real-time core of Tether:
- **FR2**: Multiple responders edit the same incident notes concurrently without overwriting each other.
- **FR3**: Offline edits are preserved locally and merge automatically on reconnection.
- **FR4**: Real-time presence indicator showing active responders per incident room.
- **NFR3**: Horizontally scalable using Redis Pub/Sub cross-instance event replication.

### Technology Stack
- **Framework:** Spring Boot 3.3.4 (Java 17)
- **Transport:** Spring WebSocket (`BinaryWebSocketHandler`)
- **Protocol:** Standard [y-websocket](https://github.com/yjs/y-websocket) binary protocol (lib0 LEB128 varUint encoding)
- **Data Model:** Conflict-Free Replicated Data Types (CRDT) via Yjs delta relay
- **Pub/Sub Relay:** Redis (`StringRedisTemplate` / `RedisMessageListenerContainer`)
- **Security:** HMAC-SHA256 JWT authentication (`tether-auth` issuer) with tenant isolation

---

## 2. Architecture & Protocol Contract

The service operates on port **8083**:
```
ws://<host>:8083/sync/{incidentId}?token=<JWT>
```

```
   Browser Tab 1 (Alice)            Browser Tab 2 (Bob)
   [ Yjs / IndexedDB ]              [ Yjs / IndexedDB ]
            │                                │
            │ Binary WebSocket (y-websocket) │
            ▼                                ▼
┌────────────────────────────────────────────────────────┐
│               Tether Sync Service (:8083)             │
│                                                        │
│  • Handshake: JWT verification (tenantId extraction)   │
│  • Incident Auth: Verify incident belongs to tenant    │
│  • Room Store: tenantId:incidentId                     │
│  • CRDT Relay: SyncStep1, SyncStep2, SyncUpdate        │
│  • Presence: Awareness tracking & departure broadcasts │
└───────────────────────────┬────────────────────────────┘
                            │
               Redis Pub/Sub (Upstash / Local)
              Topic: tether:sync:events
                            │
┌───────────────────────────┴────────────────────────────┐
│          Sync Service Node 2 (Horizontal Scale)        │
└────────────────────────────────────────────────────────┘
```

### Binary Protocol Frames (lib0 encoding)
| Frame Type | Client Action | Server Action |
|---|---|---|
| `0, 0, <stateVector>` | **Sync Step 1** (on initial connect / reconnect) | Replays room update history (`0, 2, <update>`), sends empty Step 2 (`0, 1, [0, 0]`) marking client synced, and queries client's local updates (`0, 0, [0]`). |
| `0, 1, <update>` or `0, 2, <update>` | **Document Edit** (CRDT update) | Appends update to room log, broadcasts to peers (`0, 2, <update>`), and publishes to Redis Pub/Sub. |
| `1, <awarenessUpdate>` | **Presence Ping** | Updates active client clocks, broadcasts to room peers, publishes to Redis. |
| `3` | **Query Awareness** | Prompts connected clients to announce themselves to a newly joined responder. |
| *Disconnect* | **Departure** | Broadcasts awareness removal with clock+1 and `"null"` state so client count updates immediately. |

---

## 3. Configuration

Configured via environment variables with sane defaults for local development:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8083` | HTTP / WebSocket server port |
| `JWT_SECRET` | `dev-only-insecure-secret-change-me-0123456789` | HMAC-SHA256 secret shared across Tether services |
| `JWT_ISSUER` | `tether-auth` | Expected JWT issuer claim |
| `INCIDENT_SERVICE_URL` | `http://localhost:8082` | Incident service URL for access verification |
| `REDIS_HOST` | `localhost` | Redis host for Pub/Sub |
| `REDIS_PORT` | `6379` | Redis port |
| `SYNC_REDIS_TOPIC` | `tether:sync:events` | Redis Pub/Sub topic name |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Allowed WebSocket origins |

---

## 4. Running the Service

### Using Maven
```bash
./mvnw clean spring-boot:run
```

### Running Tests
```bash
./mvnw test
```

### Health Check Endpoint
```bash
curl http://localhost:8083/health
# {"status":"UP","service":"sync-service","rooms":1,"activeConnections":2}
```
