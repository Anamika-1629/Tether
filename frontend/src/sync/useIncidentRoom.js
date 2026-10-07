import { useCallback, useEffect, useRef, useState } from 'react'
import { IndexeddbPersistence } from 'y-indexeddb'
import { WebsocketProvider } from 'y-websocket'
import * as Y from 'yjs'
import { config } from '../config.js'

const COLORS = ['#f97316', '#22c55e', '#3b82f6', '#a855f7', '#ec4899', '#14b8a6', '#eab308', '#ef4444']
const CLOSE_UNAUTHORIZED = 4401
const CLOSE_FORBIDDEN = 4403

function colorFor(id) {
  let hash = 0
  for (const ch of id) hash = (hash * 31 + ch.charCodeAt(0)) | 0
  return COLORS[Math.abs(hash) % COLORS.length]
}

/**
 * Joins the live room for one incident:
 *   notes     Y.Text shared by everyone in the room (conflict-free, via Yjs)
 *   peers     unique responders currently connected (presence, via Yjs awareness)
 *   status    'connecting' | 'live' | 'offline' | 'denied'
 * Notes are also kept in IndexedDB, so edits made offline survive a reload and merge on reconnect.
 *
 * onIncidentChanged fires when another responder changes status/severity/owner, so the page
 * can refetch at once instead of waiting for its next poll. Call notifyIncidentChanged()
 * after a successful PATCH to tell the others.
 */
export function useIncidentRoom(incidentId, session, onIncidentChanged) {
  const [room, setRoom] = useState(null)
  const [status, setStatus] = useState('connecting')
  const [peers, setPeers] = useState([])
  const [restoredLocally, setRestoredLocally] = useState(false)
  const changedRef = useRef(onIncidentChanged)
  changedRef.current = onIncidentChanged

  const { token, user, tenant } = session

  useEffect(() => {
    const ydoc = new Y.Doc()
    const notes = ydoc.getText('notes')
    const signals = ydoc.getMap('signals')

    const local = new IndexeddbPersistence(`tether:${tenant.id}:${incidentId}`, ydoc)
    local.on('synced', () => setRestoredLocally(true))

    const provider = new WebsocketProvider(config.syncUrl, incidentId, ydoc, {
      params: { token },
      disableBc: true, // all collaboration goes through the Sync Service, as in production
      maxBackoffTime: 5000,
    })
    const awareness = provider.awareness
    awareness.setLocalStateField('user', {
      id: user.id, name: user.displayName, email: user.email, color: colorFor(user.id),
    })

    let denied = false
    // Once a connection attempt fails we say "offline" (not "connecting") until one succeeds,
    // so responders know their notes are being kept on this device in the meantime
    let failed = false
    const updateStatus = () => {
      if (denied) return setStatus('denied')
      if (provider.wsconnected) return setStatus(provider.synced ? 'live' : 'connecting')
      setStatus(failed || navigator.onLine === false ? 'offline' : 'connecting')
    }
    provider.on('status', ({ status: s }) => {
      if (s === 'connected') failed = false
      updateStatus()
    })
    provider.on('sync', updateStatus)
    provider.on('connection-error', () => { failed = true; updateStatus() })
    provider.on('connection-close', (event) => {
      failed = true
      if (event?.code === CLOSE_UNAUTHORIZED || event?.code === CLOSE_FORBIDDEN) {
        denied = true
        provider.disconnect() // retrying will not help
      }
      updateStatus()
    })
    window.addEventListener('online', updateStatus)
    window.addEventListener('offline', updateStatus)

    const updatePeers = () => {
      const byUser = new Map()
      for (const state of awareness.getStates().values()) {
        if (state.user) byUser.set(state.user.id, state.user)
      }
      setPeers([...byUser.values()].sort((a, b) => (a.id === user.id ? -1 : b.id === user.id ? 1 : a.name.localeCompare(b.name))))
    }
    awareness.on('change', updatePeers)
    updatePeers()

    signals.observe((event) => {
      if (!event.transaction.local && event.keysChanged.has('incident')) changedRef.current?.(signals.get('incident'))
    })

    setRoom({ ydoc, notes, signals })
    return () => {
      window.removeEventListener('online', updateStatus)
      window.removeEventListener('offline', updateStatus)
      provider.destroy()
      local.destroy()
      ydoc.destroy()
      setRoom(null)
      setPeers([])
      setStatus('connecting')
      setRestoredLocally(false)
    }
  }, [incidentId, token, user.id, user.displayName, user.email, tenant.id])

  const notifyIncidentChanged = useCallback(() => {
    room?.signals.set('incident', { at: Date.now(), by: user.displayName })
  }, [room, user.displayName])

  return { room, status, peers, restoredLocally, notifyIncidentChanged }
}
