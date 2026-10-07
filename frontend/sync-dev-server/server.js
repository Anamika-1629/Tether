/**
 * Development stand-in for the Sync Service (port 8083).
 *
 * It speaks the standard y-websocket protocol, so the frontend can switch to the real
 * Spring Boot Sync Service without changing anything once that service implements the
 * same contract (see ../README.md, "Sync Service contract").
 *
 * The server is a relay: it never needs a Yjs implementation. That keeps it easy to port to Java.
 *   - Every document update a client sends is appended to the room's log and relayed to the others.
 *   - A client that joins gets the log replayed, then is asked for anything it has that the room lacks
 *     (edits made while offline).
 *   - Awareness (presence) messages are relayed; when a connection drops, the server tells everyone
 *     that its users left, so "N responders active" updates at once instead of after a 30s timeout.
 *
 * Auth: ws://host:8083/sync/{incidentId}?token=<JWT from auth-service>
 *   The JWT is verified (HS256, same JWT_SECRET/issuer as the other services), then the incident is
 *   fetched from the Incident Service with the caller's token, so only users of the incident's tenant
 *   can join its room.
 */
import { createHmac, timingSafeEqual } from 'node:crypto'
import { createServer } from 'node:http'
import { pathToFileURL } from 'node:url'
import * as decoding from 'lib0/decoding'
import * as encoding from 'lib0/encoding'
import { WebSocketServer } from 'ws'

// y-websocket message types
const MESSAGE_SYNC = 0
const MESSAGE_AWARENESS = 1
const MESSAGE_QUERY_AWARENESS = 3
// y-protocols sync sub-types
const SYNC_STEP1 = 0
const SYNC_STEP2 = 1
const SYNC_UPDATE = 2
/** A Yjs update that contains nothing (0 structs, empty delete set). */
const EMPTY_UPDATE = new Uint8Array([0, 0])
/** A state vector that knows nothing, so the client replies with its whole document. */
const EMPTY_STATE_VECTOR = new Uint8Array([0])

const CLOSE_UNAUTHORIZED = 4401
const CLOSE_FORBIDDEN = 4403

export function createSyncServer({
  port = 8083,
  jwtSecret = process.env.JWT_SECRET ?? 'dev-only-insecure-secret-change-me-0123456789',
  jwtIssuer = process.env.JWT_ISSUER ?? 'tether-auth',
  incidentServiceUrl = process.env.INCIDENT_SERVICE_URL ?? 'http://localhost:8082',
  canAccessIncident = (incidentId, token) => fetchIncident(incidentServiceUrl, incidentId, token),
  log = console.log,
} = {}) {
  /** roomKey -> { clients: Set<ws>, updates: Uint8Array[] } */
  const rooms = new Map()

  const httpServer = createServer((req, res) => {
    if (req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ status: 'UP', rooms: rooms.size }))
      return
    }
    res.writeHead(404).end()
  })
  const wss = new WebSocketServer({ server: httpServer })

  wss.on('connection', async (ws, req) => {
    ws.binaryType = 'arraybuffer'
    // Buffer anything the client sends while we are still checking its access
    const pending = []
    ws.on('message', (data) => pending.push(data))

    const url = new URL(req.url, 'http://localhost')
    const match = url.pathname.match(/^\/sync\/([0-9a-fA-F-]{36})$/)
    if (!match) return ws.close(CLOSE_FORBIDDEN, 'Unknown room')
    const incidentId = match[1].toLowerCase()

    const claims = verifyJwt(url.searchParams.get('token'), jwtSecret, jwtIssuer)
    if (!claims?.tenantId) return ws.close(CLOSE_UNAUTHORIZED, 'Invalid or expired token')
    if (!(await canAccessIncident(incidentId, url.searchParams.get('token')))) {
      return ws.close(CLOSE_FORBIDDEN, 'No access to this incident')
    }
    if (ws.readyState !== ws.OPEN) return

    const roomKey = `${claims.tenantId}:${incidentId}`
    const room = rooms.get(roomKey) ?? { clients: new Set(), updates: [] }
    rooms.set(roomKey, room)
    room.clients.add(ws)
    ws.awarenessClients = new Map() // Yjs clientID -> last clock seen on this connection
    ws.isAlive = true
    log(`[sync] ${claims.email} joined ${incidentId} (${room.clients.size} connected)`)

    ws.removeAllListeners('message')
    ws.on('message', (data) => handleMessage(ws, room, new Uint8Array(data)))
    ws.on('pong', () => { ws.isAlive = true })
    ws.on('close', () => {
      room.clients.delete(ws)
      broadcastAwarenessRemoval(ws, room)
      if (room.clients.size === 0 && room.updates.length === 0) rooms.delete(roomKey)
      log(`[sync] ${claims.email} left ${incidentId} (${room.clients.size} connected)`)
    })

    // Ask everyone already here to announce themselves, so the newcomer sees them at once
    broadcast(room, encodeMessage(MESSAGE_QUERY_AWARENESS), ws)
    for (const data of pending) handleMessage(ws, room, new Uint8Array(data))
  })

  function handleMessage(ws, room, message) {
    try {
      const decoder = decoding.createDecoder(message)
      const type = decoding.readVarUint(decoder)
      if (type === MESSAGE_SYNC) {
        const syncType = decoding.readVarUint(decoder)
        if (syncType === SYNC_STEP1) {
          // Client is (re)connecting: give it the room's history, mark it synced, then ask for its own edits
          for (const update of room.updates) send(ws, encodeSync(SYNC_UPDATE, update))
          send(ws, encodeSync(SYNC_STEP2, EMPTY_UPDATE))
          send(ws, encodeSync(SYNC_STEP1, EMPTY_STATE_VECTOR))
        } else if (syncType === SYNC_STEP2 || syncType === SYNC_UPDATE) {
          const update = decoding.readVarUint8Array(decoder)
          room.updates.push(update)
          broadcast(room, encodeSync(SYNC_UPDATE, update), ws)
        }
      } else if (type === MESSAGE_AWARENESS) {
        trackAwareness(ws, decoding.readVarUint8Array(decoder))
        broadcast(room, message, ws)
      }
    } catch (err) {
      log(`[sync] dropped malformed message: ${err.message}`)
    }
  }

  /** Remember which Yjs clients this connection speaks for, so we can announce their departure. */
  function trackAwareness(ws, update) {
    const decoder = decoding.createDecoder(update)
    const count = decoding.readVarUint(decoder)
    for (let i = 0; i < count; i++) {
      const clientId = decoding.readVarUint(decoder)
      const clock = decoding.readVarUint(decoder)
      const state = decoding.readVarString(decoder)
      if (state === 'null') ws.awarenessClients.delete(clientId)
      else ws.awarenessClients.set(clientId, clock)
    }
  }

  function broadcastAwarenessRemoval(ws, room) {
    if (!ws.awarenessClients?.size) return
    const encoder = encoding.createEncoder()
    encoding.writeVarUint(encoder, ws.awarenessClients.size)
    for (const [clientId, clock] of ws.awarenessClients) {
      encoding.writeVarUint(encoder, clientId)
      encoding.writeVarUint(encoder, clock + 1)
      encoding.writeVarString(encoder, 'null')
    }
    broadcast(room, encodeMessage(MESSAGE_AWARENESS, encoding.toUint8Array(encoder)))
  }

  // Drop connections that stopped answering pings (laptop lid closed, wifi gone)
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) { ws.terminate(); continue }
      ws.isAlive = false
      ws.ping()
    }
  }, 15_000)

  return {
    listen: () => new Promise((resolve) => httpServer.listen(port, () => resolve(httpServer.address().port))),
    close: () => new Promise((resolve) => {
      clearInterval(heartbeat)
      for (const ws of wss.clients) ws.terminate()
      wss.close(() => httpServer.close(() => resolve()))
    }),
  }
}

function send(ws, message) {
  if (ws.readyState === ws.OPEN) ws.send(message)
}

function broadcast(room, message, except) {
  for (const client of room.clients) if (client !== except) send(client, message)
}

function encodeSync(syncType, payload) {
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, MESSAGE_SYNC)
  encoding.writeVarUint(encoder, syncType)
  encoding.writeVarUint8Array(encoder, payload)
  return encoding.toUint8Array(encoder)
}

function encodeMessage(type, payload) {
  const encoder = encoding.createEncoder()
  encoding.writeVarUint(encoder, type)
  if (payload) encoding.writeVarUint8Array(encoder, payload)
  return encoding.toUint8Array(encoder)
}

/** Minimal HS256 verification: signature, issuer, expiry. Returns the claims or null. */
export function verifyJwt(token, secret, issuer) {
  if (!token) return null
  const parts = token.split('.')
  if (parts.length !== 3) return null
  const [header, payload, signature] = parts
  const expected = createHmac('sha256', secret).update(`${header}.${payload}`).digest()
  const given = Buffer.from(signature, 'base64url')
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) return null
  try {
    if (JSON.parse(Buffer.from(header, 'base64url')).alg !== 'HS256') return null
    const claims = JSON.parse(Buffer.from(payload, 'base64url'))
    if (claims.iss !== issuer) return null
    if (typeof claims.exp !== 'number' || claims.exp * 1000 <= Date.now()) return null
    return claims
  } catch {
    return null
  }
}

async function fetchIncident(baseUrl, incidentId, token) {
  try {
    const res = await fetch(`${baseUrl}/incidents/${incidentId}`, { headers: { Authorization: `Bearer ${token}` } })
    return res.ok
  } catch {
    return false
  }
}

// Run directly: `npm run dev:sync`
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT ?? 8083)
  createSyncServer({ port }).listen().then((p) => {
    console.log(`[sync] dev Sync Service listening on ws://localhost:${p}/sync/{incidentId}`)
  })
}
