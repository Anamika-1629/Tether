import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { authApi } from '../api/auth.js'
import { SEVERITIES, STATUSES, incidentsApi } from '../api/incidents.js'
import { useAuth } from '../auth/AuthContext.jsx'
import AuditTimeline from '../components/AuditTimeline.jsx'
import { SeverityBadge, StatusBadge } from '../components/Badges.jsx'
import ConnectionBadge, { connectionHint } from '../components/ConnectionBadge.jsx'
import { ErrorBanner, Field, inputClass } from '../components/Field.jsx'
import PresenceIndicator from '../components/PresenceIndicator.jsx'
import SharedNotes from '../components/SharedNotes.jsx'
import { useIncidentRoom } from '../sync/useIncidentRoom.js'

export default function IncidentRoomPage() {
  const { id } = useParams()
  const { session } = useAuth()
  const [incident, setIncident] = useState(null)
  const [audit, setAudit] = useState([])
  const [members, setMembers] = useState([])
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(null)
  const [flash, setFlash] = useState(null)

  const load = useCallback(async () => {
    try {
      const [inc, events] = await Promise.all([incidentsApi.get(id), incidentsApi.audit(id)])
      setIncident(inc)
      setAudit(events)
      setError(null)
    } catch (err) {
      setError(err.status === 404 ? 'Incident not found in your organization.' : err.message)
    }
  }, [id])

  // Another responder changed status/severity/owner: refetch now and say who did it
  const onRemoteChange = useCallback((signal) => {
    load()
    if (signal?.by) {
      setFlash(`${signal.by} updated the incident`)
      setTimeout(() => setFlash(null), 3000)
    }
  }, [load])

  const { room, status, peers, notifyIncidentChanged } = useIncidentRoom(id, session, onRemoteChange)

  useEffect(() => {
    load()
    // Fallback in case the Sync Service is down: the REST data still refreshes
    const timer = setInterval(load, 20_000)
    return () => clearInterval(timer)
  }, [load])

  useEffect(() => {
    authApi.members().then(setMembers).catch(() => setMembers([]))
  }, [])

  const nameFor = useMemo(() => {
    const byEmail = new Map(members.map((m) => [m.email, m.displayName]))
    return (value) => byEmail.get(value) ?? value
  }, [members])

  const update = async (field, value) => {
    setSaving(field)
    try {
      setIncident(await incidentsApi.update(id, { [field]: value }))
      setAudit(await incidentsApi.audit(id))
      notifyIncidentChanged()
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(null)
    }
  }

  if (error && !incident) {
    return (
      <div className="space-y-4">
        <Link to="/" className="text-sm text-slate-400 hover:text-slate-200">← All incidents</Link>
        <ErrorBanner error={error} />
      </div>
    )
  }
  if (!incident) return <p className="text-sm text-slate-500">Loading incident…</p>

  const ownerOptions = members.some((m) => m.email === incident.owner) || !incident.owner
    ? members
    : [...members, { id: 'current', email: incident.owner, displayName: incident.owner }]

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link to="/" className="text-sm text-slate-400 hover:text-slate-200">← All incidents</Link>
        <div className="flex items-center gap-4">
          <PresenceIndicator peers={peers} live={status === 'live'} />
          <ConnectionBadge status={status} />
        </div>
      </div>

      <div>
        <div className="flex flex-wrap items-center gap-3">
          <SeverityBadge severity={incident.severity} />
          <StatusBadge status={incident.status} />
          {flash && <span className="animate-pulse text-xs text-sky-300">{flash}</span>}
        </div>
        <h1 className="mt-2 text-2xl font-bold tracking-tight" data-testid="incident-title">{incident.title}</h1>
        <p className="mt-1 text-sm text-slate-500">
          Opened {new Date(incident.createdAt).toLocaleString()} · ID <span className="font-mono">{incident.id.slice(0, 8)}</span>
        </p>
      </div>

      <ErrorBanner error={error} />

      <div className="grid gap-6 lg:grid-cols-3">
        <section className="space-y-3 lg:col-span-2">
          <div className="flex items-baseline justify-between">
            <h2 className="font-semibold">Shared notes</h2>
            <span className="text-xs text-slate-500">{connectionHint(status)}</span>
          </div>
          <SharedNotes room={room} />
        </section>

        <aside className="space-y-6">
          <div className="space-y-4 rounded-xl border border-slate-800 bg-slate-900/60 p-4">
            <Field label="Status">
              <select className={inputClass} value={incident.status} disabled={saving === 'status'} onChange={(e) => update('status', e.target.value)}>
                {STATUSES.map((s) => <option key={s}>{s}</option>)}
              </select>
            </Field>
            <Field label="Severity">
              <select className={inputClass} value={incident.severity} disabled={saving === 'severity'} onChange={(e) => update('severity', e.target.value)}>
                {SEVERITIES.map((s) => <option key={s}>{s}</option>)}
              </select>
            </Field>
            <Field label="Owner">
              <select className={inputClass} value={incident.owner ?? ''} disabled={saving === 'owner'} onChange={(e) => update('owner', e.target.value)}>
                {!incident.owner && <option value="">Unassigned</option>}
                {ownerOptions.map((m) => <option key={m.id} value={m.email}>{m.displayName}</option>)}
              </select>
            </Field>
          </div>

          <div>
            <h2 className="mb-3 font-semibold">Timeline</h2>
            <AuditTimeline events={audit} nameFor={nameFor} />
          </div>
        </aside>
      </div>
    </div>
  )
}
