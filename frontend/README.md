# Tether Frontend (Member 4)

React + Tailwind CSS + Yjs. Port **3000**. This is the incident room the reviewers see. It ties the three backend services into one screen.

| Talks to | For | How |
|---|---|---|
| Auth Service `:8081` | Sign in, create account / join with code, list teammates | REST, stores the JWT |
| Incident Service `:8082` | Incident list, title/status/severity/owner, audit timeline | REST with `Authorization: Bearer <jwt>` |
| Sync Service `:8083` | Shared notes and "N responders active" | WebSocket, Yjs protocol (contract below) |

## Run

```bash
cd frontend
npm install
npm run dev          # http://localhost:3000
```

No `.env` is needed: every URL has a localhost default (see [`.env.example`](.env.example) to point elsewhere).

The Sync Service isn't built yet, so this folder includes a **development stand-in** that implements the same contract:

```bash
npm run dev:sync     # ws://localhost:8083/sync (needs the Incident Service running, for access checks)
```

Without it the app still works. Notes are saved in the browser and the badge says **Offline** until a Sync Service is reachable, after which they merge automatically.

| Command | What it does |
|---|---|
| `npm run dev` | Frontend with hot reload on :3000 |
| `npm run dev:sync` | Development Sync Service on :8083 |
| `npm test` | 19 tests: textarea↔Yjs binding, plus the sync server with real Yjs clients (live edits, concurrent edits, offline merge, presence, auth) |
| `npm run build` | Production build into `dist/` |

## What's on screen

- **Login** (`/login`): sign in, or create an account by either starting a new organization or joining one with a join code. The JWT is kept in `sessionStorage`, so each browser tab can be a different responder. Every request sends it, and any 401 or token expiry sends you back to login.
- **Incidents** (`/`): declare an incident (title + severity), and filter by status. The list refreshes every 15 s. Owners see their organization's join code in the header (click to copy).
- **Incident room** (`/incidents/:id`):
  - Title, severity, status and owner come from the Incident Service. Changing status, severity or owner sends a PATCH, and the other responders see it **instantly**, because a small "changed" signal goes through the shared Yjs document and their pages refetch.
  - **Shared notes** is a plain textarea bound to a Yjs `Y.Text`. Concurrent typing merges without conflicts, and your cursor stays where you were typing.
  - **"2 responders active"** comes from Yjs awareness. Someone who closes their laptop disappears from the count within seconds.
  - A connection badge shows **Live / Connecting… / Offline / No sync access**. Offline edits are kept in IndexedDB, survive a reload, and merge on reconnect.
  - The **Timeline** shows the Incident Service's append-only audit log ("Bob Mehta changed status OPEN → INVESTIGATING"), with emails shown as names.

### Why it's deliberately plain

The tool is used while production is on fire, so every choice favors "keeps working" over "looks clever":
- **A textarea, not a rich-text editor.** Fewer moving parts, pasted logs stay byte-for-byte, and it works on any browser.
- **A text presence indicator, not live cursors.** It's readable at a glance and cheap on a degraded network.
- **Offline is a normal state, not an error.** Typing never blocks. The badge says the notes are safe on this device.
- **REST data refreshes every 20 s even if the Sync Service is down**, so the room never shows stale status for long.

## Demo script (two responders, about 3 minutes)

Start Postgres, `auth-service`, `incident-service`, then `npm run dev:sync` and `npm run dev`.

1. **Tab 1:** Create account → "New organization" → *Alice, Acme Corp*. Note the **join code** in the header.
2. **Tab 1:** Declare *"Checkout API returning 500s"*, SEV2. You land in the incident room: *1 responder active, Live*.
3. **Tab 2** (a new tab is a separate session): Create account → "Join with code" → *Bob* + the code. Open the same incident (from the list).
4. Both tabs show **2 responders active**. Type in Bob's notes and watch it appear in Alice's tab as you type.
5. In Bob's tab, set status **INVESTIGATING** and owner **Bob**. Alice's tab updates by itself, and the timeline shows who changed what.
6. **Outage drill:** stop `npm run dev:sync` (Ctrl+C). Alice's badge turns **Offline**; she keeps typing; reload the page and her notes are still there. Restart `npm run dev:sync` and Bob receives everything she wrote.

All six steps were run end to end against the real Auth and Incident services.

## Sync Service contract

This is for the Sync Service owner (Member 3). The frontend uses the standard [y-websocket](https://github.com/yjs/y-websocket) client, so the real service only has to implement this. `sync-dev-server/server.js` (about 200 lines) is a working reference to port to Spring Boot.

**Connect:** `ws://<host>:8083/sync/{incidentId}?token=<JWT>` (binary frames). Browsers can't set headers on a WebSocket, hence the query parameter. Don't log query strings.

**Access check, on connect:**
1. Verify the JWT: HS256, same `JWT_SECRET`, `iss = tether-auth`, not expired. Otherwise close with **4401**.
2. Check the incident belongs to the token's `tenantId`. The dev server calls `GET /incidents/{id}` on the Incident Service with the same token; 404 means close with **4403**. The client stops retrying on any 44xx code. If the Incident Service is unreachable or erroring, the Sync Service closes with **1013** (try again later) instead, which the client does retry.
3. The room key is `tenantId:incidentId`.

**Messages:** each frame starts with a varUint type (lib0 encoding):

| Client sends | Server does |
|---|---|
| `0,0,<stateVector>` Sync step 1 (on every connect) | Reply with every stored update as `0,2,<update>`, then `0,1,<0x00 0x00>` (empty step 2: marks the client synced), then `0,0,<0x00>` (step 1 with an empty state vector, so the client sends back everything it has, including offline edits) |
| `0,1,<update>` or `0,2,<update>` | Append `update` to the room's log and relay it to the other clients as `0,2,<update>` |
| `1,<awarenessUpdate>` | Relay it to the other clients unchanged. Remember the `(clientID, clock)` pairs in it for this connection |
| *(new client joins)* | Send `3` (query awareness) to the existing clients, so they announce themselves to the newcomer |
| *(connection closes)* | Broadcast `1,<awarenessUpdate>` marking that connection's clientIDs as `clock+1, "null"`, so presence drops at once |

The server never needs a Yjs implementation. It stores and relays opaque update bytes, and Yjs merges duplicates on the clients. Ping every ~15 s and drop connections that don't answer. For persistence across restarts, store the update log (e.g. in Postgres or Redis), keyed by room. Even without that, clients re-upload their copies on reconnect, which is how the outage drill above recovers.

## Layout

```
frontend/
├── src/
│   ├── api/            REST clients (auth, incidents), Bearer token + error handling
│   ├── auth/           Session (sessionStorage), auto sign-out on expiry or 401
│   ├── components/     Badges, presence, connection badge, shared notes, timeline
│   ├── pages/          Login, incident list, incident room
│   ├── sync/           useIncidentRoom (Yjs doc + IndexedDB + WebSocket + awareness), textarea diff/caret logic
│   └── config.js       Backend URLs (VITE_* overrides)
└── sync-dev-server/    Development Sync Service + its tests
```
