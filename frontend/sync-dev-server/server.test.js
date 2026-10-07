import { createHmac, randomUUID } from 'node:crypto'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { WebsocketProvider } from 'y-websocket'
import * as Y from 'yjs'
import { createSyncServer, verifyJwt } from './server.js'

const SECRET = 'test-secret-that-is-at-least-32-bytes-long!!'
const TENANT_A = randomUUID()
const TENANT_B = randomUUID()
const INCIDENT = randomUUID()

function jwt(claims, secret = SECRET) {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const body = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ iss: 'tether-auth', exp: Math.floor(Date.now() / 1000) + 3600, ...claims })}`
  return `${body}.${createHmac('sha256', secret).update(body).digest('base64url')}`
}

const tokenFor = (name, tenantId = TENANT_A) => jwt({ userId: randomUUID(), tenantId, email: `${name}@acme.test` })

let server
let url
const providers = []

beforeAll(async () => {
  // Stand-in for the Incident Service check: INCIDENT belongs to tenant A only
  server = createSyncServer({
    port: 0,
    jwtSecret: SECRET,
    log: () => {},
    canAccessIncident: async (id, token) => id === INCIDENT && verifyJwt(token, SECRET, 'tether-auth')?.tenantId === TENANT_A,
  })
  url = `ws://localhost:${await server.listen()}/sync`
})

afterAll(async () => {
  providers.forEach((p) => p.destroy())
  await server.close()
})

function connect(name, { token = tokenFor(name), doc = new Y.Doc(), room = INCIDENT } = {}) {
  const provider = new WebsocketProvider(url, room, doc, { params: { token }, WebSocketPolyfill: WebSocket, disableBc: true })
  provider.awareness.setLocalStateField('user', { id: name, name })
  providers.push(provider)
  return { doc, provider, text: doc.getText('notes') }
}

const until = async (check, ms = 3000) => {
  const start = Date.now()
  while (!check()) {
    if (Date.now() - start > ms) throw new Error('timed out waiting for condition')
    await new Promise((r) => setTimeout(r, 20))
  }
}

const peerNames = (provider) => [...provider.awareness.getStates().values()].map((s) => s.user?.name).filter(Boolean).sort()

describe('dev Sync Service', () => {
  it('syncs text between two responders in real time', async () => {
    const alice = connect('alice')
    const bob = connect('bob')
    await until(() => alice.provider.synced && bob.provider.synced)

    alice.text.insert(0, 'Checkout is down')
    await until(() => bob.text.toString() === 'Checkout is down')

    bob.text.insert(bob.text.length, ', DB pool exhausted')
    await until(() => alice.text.toString() === 'Checkout is down, DB pool exhausted')
  })

  it('merges concurrent edits made at the same moment without losing either', async () => {
    const a = connect('carol')
    const b = connect('dave')
    await until(() => a.provider.synced && b.provider.synced && a.text.toString() === b.text.toString())
    a.text.insert(0, '[A] ')
    b.text.insert(b.text.length, ' [B]')
    await until(() => a.text.toString() === b.text.toString() && a.text.toString().includes('[A]') && a.text.toString().includes('[B]'))
  })

  it('replays history to someone who joins later', async () => {
    const late = connect('erin')
    await until(() => late.text.toString().includes('DB pool exhausted'))
  })

  it('merges edits made while offline when the responder reconnects', async () => {
    const online = connect('frank')
    const laptop = connect('grace')
    await until(() => online.provider.synced && laptop.provider.synced)

    laptop.provider.disconnect()
    laptop.text.insert(0, 'OFFLINE NOTE ')
    online.text.insert(online.text.length, ' ONLINE NOTE')
    await new Promise((r) => setTimeout(r, 200))
    expect(online.text.toString()).not.toContain('OFFLINE NOTE')

    laptop.provider.connect()
    await until(() => online.text.toString() === laptop.text.toString())
    expect(online.text.toString()).toContain('OFFLINE NOTE')
    expect(online.text.toString()).toContain('ONLINE NOTE')
  })

  it('shows who is present and drops someone the moment they disconnect', async () => {
    // Others from earlier tests are in the same room, so assert only on the names this test controls
    const heidi = connect('heidi')
    const ivan = connect('ivan')
    await until(() => peerNames(heidi.provider).includes('ivan') && peerNames(ivan.provider).includes('heidi'))

    ivan.provider.ws.terminate() // abrupt drop, like closing the laptop lid
    ivan.provider.shouldConnect = false
    await until(() => !peerNames(heidi.provider).includes('ivan'), 2000)
  })

  it('rejects a forged token and users from another tenant', async () => {
    const closes = async (token) => {
      const ws = new WebSocket(`${url}/${INCIDENT}?token=${token}`)
      return new Promise((resolve) => ws.on('close', (code) => resolve(code)))
    }
    expect(await closes(jwt({ tenantId: TENANT_A, email: 'x' }, 'a-different-secret-that-is-32-bytes-long'))).toBe(4401)
    expect(await closes('not-a-jwt')).toBe(4401)
    expect(await closes(tokenFor('mallory', TENANT_B))).toBe(4403)
  })
})
