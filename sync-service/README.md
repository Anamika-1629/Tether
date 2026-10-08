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
│  • Rooms: tenantId:incidentId (sessions only, in RAM)  │
│  • Update log: Redis list per room (shared, durable)   │
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
| `0, 0, <stateVector>` | **Sync Step 1** (on initial connect / reconnect) | Replays the room's stored update log (`0, 2, <update>`), sends empty Step 2 (`0, 1, [0, 0]`) marking client synced, and queries client's local updates (`0, 0, [0]`). |
| `0, 1, <update>` or `0, 2, <update>` | **Document Edit** (CRDT update) | Appends update to the room's Redis update log, broadcasts to peers (`0, 2, <update>`), and publishes to Redis Pub/Sub. |
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
| `INCIDENT_CHECK_ENABLED` | `true` | Verify incident access on connect. Set `false` only for local testing |
| `INCIDENT_CONNECT_TIMEOUT_MS` | `2000` | Connect timeout of the access check |
| `INCIDENT_READ_TIMEOUT_MS` | `3000` | Read timeout of the access check |
| `REDIS_HOST` | `localhost` | Redis host for Pub/Sub |
| `REDIS_PORT` | `6379` | Redis port |
| `SYNC_REDIS_TOPIC` | `tether:sync:events` | Redis Pub/Sub topic name |
| `REDIS_SSL` | `false` | Set `true` for TLS (Upstash) |
| `REDIS_TIMEOUT_MS` | `2000` | Redis command timeout |
| `REDIS_CONNECT_TIMEOUT_MS` | `2000` | Redis connect timeout |
| `SYNC_REDIS_RETRY_COOLDOWN_MS` | `5000` | Wait this long after a failed publish before retrying |
| `SYNC_HEARTBEAT_INTERVAL_MS` | `10000` | How often every open session is pinged |
| `SYNC_HEARTBEAT_TIMEOUT_MS` | `30000` | A session silent for longer than this is dropped |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Allowed WebSocket origins |

### Heartbeat

Every 10 s the service pings each open session. A pong, or any frame from the client, counts as proof of life.
A session silent for more than 30 s is closed (`1001`) and removed from its room, and peers receive an awareness
removal so the presence list updates. Browsers answer pings automatically, so a healthy tab never notices.
A laptop that lost wifi, or a killed browser process, disappears from presence about 30 to 40 s later instead of
lingering until the TCP connection finally times out. Cleanup runs once per session even if the close callback
also fires, and does not depend on the close frame being deliverable.

### Connection close codes

| Code | Meaning | Client behaviour |
|---|---|---|
| `4401` | Missing, invalid or expired JWT | Stops retrying, signs the user out |
| `4403` | The Incident Service says this tenant has no access to the incident | Stops retrying |
| `1013` | Access could not be verified (Incident Service unreachable, timed out, 5xx or 429) | Keeps retrying with backoff; notes stay safe in IndexedDB |

The access check **fails closed**: if the Incident Service cannot give a clear yes, nobody joins the room.
`1013` is deliberately not a `44xx` code, because the frontend gives up on those.

---

## Update log persistence and compaction

Each room's Yjs history is a Redis list (`tether:sync:room:{tenantId}:{incidentId}:updates`). It is written **before** an edit is broadcast, so:

- a restarted instance serves the same history, and an instance started later sees it too (Pub/Sub only carries live messages, the list carries history);
- an empty room is dropped from memory immediately; rooms hold sessions and awareness only;
- the log expires `tether.sync.log-ttl` (default 30 days) after the room's last edit. Set it to `0` to keep it forever.

**Compaction.** The server cannot merge Yjs updates itself, so when a log reaches `tether.sync.compact-threshold` entries (default 200) it asks one connected client for its full document (a Sync Step 1 with an empty state vector, which y-websocket clients answer with the whole doc). That snapshot atomically replaces everything except the newest `compact-keep-tail` (default 25) entries. Only one compaction runs per room at a time (Redis lock), and Yjs updates are idempotent, so keeping an entry the snapshot already contains is harmless.

**If Redis is down.** Live relay between already-connected clients keeps working on that instance, but edits are not stored and new joiners get no history. Clients re-upload their own state when they reconnect, so the document heals from any client that still has it. Relay resumes automatically within a few seconds of Redis coming back, but Pub/Sub messages sent during the outage are not replayed. Use `tether.sync.store=memory` to run without Redis at all (history is then lost on restart and not shared).

| Variable | Default | Meaning |
|---|---|---|
| `SYNC_STORE` | `redis` | `redis` or `memory` |
| `SYNC_LOG_TTL` | `30d` | History expiry after last edit (`0` = never) |
| `SYNC_COMPACT_THRESHOLD` | `200` | Log entries that trigger compaction (`0` = off) |
| `SYNC_COMPACT_KEEP_TAIL` | `25` | Newest entries never compacted |
| `SYNC_MAX_MESSAGE_BYTES` | `1048576` | Largest WebSocket frame accepted (Tomcat's default is 8 KB) |

---

## Metrics (NFR2)

Exposed at `/actuator/prometheus` (Micrometer). All series carry an `application="sync-service"` tag.

| Series | Type | Meaning |
|---|---|---|
| `tether_sync_sessions_active` | gauge | Open responder connections that joined a room |
| `tether_sync_relay_latency_seconds` (`_count`, `_sum`, `_bucket`) | timer | Time to store one document update and fan it out to the room and Redis. Server side only, not end to end |
| `tether_sync_reconnects_total` | counter | A responder rejoined the same room within 60 s of leaving it |
| `tether_sync_rejections_total{reason}` | counter | Connections refused: `unauthorized` (4401), `forbidden` (4403), `unavailable` (1013), `bad_request` |

Check: `curl -s localhost:8083/actuator/prometheus | grep tether_sync`. The relay and reconnect series appear after the first edit and the first rejoin.

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
