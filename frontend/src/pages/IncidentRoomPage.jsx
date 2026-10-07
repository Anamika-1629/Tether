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

  /* ── Error / loading states ───────────────────────────────── */
  if (error && !incident) {
    return (
      <div className="space-y-4 fade-in">
        <Link to="/" className="inline-flex items-center gap-1.5 text-sm text-slate-400 transition-colors hover:text-slate-200">
          <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
          </svg>
          All incidents
        </Link>
        <ErrorBanner error={error} />
      </div>
    )
  }

  if (!incident) {
    return (
      <div className="flex flex-col items-center justify-center py-20 fade-in">
        <svg className="mb-4 h-8 w-8 animate-spin text-sky-500" fill="none" viewBox="0 0 24 24">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
        </svg>
        <p className="text-sm text-slate-500">Loading incident…</p>
      </div>
    )
  }

  const ownerOptions = members.some((m) => m.email === incident.owner) || !incident.owner
    ? members
    : [...members, { id: 'current', email: incident.owner, displayName: incident.owner }]

  /* ── Severity accent ──────────────────────────────────────── */
  const SEV_ACCENT = {
    SEV1: 'from-red-900/20 via-transparent',
    SEV2: 'from-orange-900/15 via-transparent',
    SEV3: 'from-yellow-900/10 via-transparent',
    SEV4: 'from-slate-800/20 via-transparent',
  }

  return (
    <div className="space-y-6 fade-in">
      {/* ── Top bar: back + presence + connection ─────────────── */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link
          to="/"
          id="back-to-incidents"
          className="inline-flex items-center gap-1.5 text-sm text-slate-400 transition-colors hover:text-slate-200"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
          </svg>
          All incidents
        </Link>

        <div className="flex items-center gap-3">
          <PresenceIndicator peers={peers} live={status === 'live'} />
          <ConnectionBadge status={status} />
        </div>
      </div>

      {/* ── Incident header ────────────────────────────────────── */}
      <div className={`rounded-2xl bg-gradient-to-b ${SEV_ACCENT[incident.severity] ?? 'from-slate-900/20 via-transparent'} to-transparent p-px`}>
        <div className="rounded-2xl bg-slate-950/60 px-5 py-5">
          <div className="flex flex-wrap items-center gap-3">
            <SeverityBadge severity={incident.severity} />
            <StatusBadge status={incident.status} />
            {flash && (
              <span className="slide-up inline-flex items-center gap-1.5 rounded-full bg-sky-950/50 px-3 py-0.5 text-xs font-medium text-sky-300 ring-1 ring-sky-800/50">
                <svg className="h-3 w-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm.75-13a.75.75 0 00-1.5 0v5c0 .414.336.75.75.75h4a.75.75 0 000-1.5h-3.25V5z" clipRule="evenodd" />
                </svg>
                {flash}
              </span>
            )}
          </div>
          <h1
            className="mt-3 text-2xl font-bold tracking-tight text-slate-50 lg:text-3xl"
            data-testid="incident-title"
          >
            {incident.title}
          </h1>
          <p className="mt-1.5 text-sm text-slate-500">
            Opened {new Date(incident.createdAt).toLocaleString()} ·
            ID <span className="font-mono text-slate-400">{incident.id.slice(0, 8)}</span>
          </p>
        </div>
      </div>

      <ErrorBanner error={error} />

      {/* ── Main grid ─────────────────────────────────────────── */}
      <div className="grid gap-6 lg:grid-cols-3">

        {/* Left: shared notes */}
        <section className="space-y-3 lg:col-span-2">
          <div className="flex items-baseline justify-between">
            <h2 className="font-semibold text-slate-200">
              Shared notes
            </h2>
            <span className="text-xs text-slate-600">{connectionHint(status)}</span>
          </div>
          <SharedNotes room={room} />
        </section>

        {/* Right: sidebar */}
        <aside className="space-y-5">
          {/* Metadata card */}
          <div className="glass space-y-4 rounded-2xl p-5">
            <h2 className="text-sm font-semibold uppercase tracking-widest text-slate-500">Details</h2>

            <Field label="Status">
              <select
                id="incident-status-select"
                className={inputClass}
                value={incident.status}
                disabled={saving === 'status'}
                onChange={(e) => update('status', e.target.value)}
              >
                {STATUSES.map((s) => <option key={s}>{s}</option>)}
              </select>
              {saving === 'status' && <span className="mt-1 block text-xs text-slate-500">Saving…</span>}
            </Field>

            <Field label="Severity">
              <select
                id="incident-severity-select"
                className={inputClass}
                value={incident.severity}
                disabled={saving === 'severity'}
                onChange={(e) => update('severity', e.target.value)}
              >
                {SEVERITIES.map((s) => <option key={s}>{s}</option>)}
              </select>
              {saving === 'severity' && <span className="mt-1 block text-xs text-slate-500">Saving…</span>}
            </Field>

            <Field label="Owner">
              <select
                id="incident-owner-select"
                className={inputClass}
                value={incident.owner ?? ''}
                disabled={saving === 'owner'}
                onChange={(e) => update('owner', e.target.value)}
              >
                {!incident.owner && <option value="">Unassigned</option>}
                {ownerOptions.map((m) => (
                  <option key={m.id} value={m.email}>{m.displayName}</option>
                ))}
              </select>
              {saving === 'owner' && <span className="mt-1 block text-xs text-slate-500">Saving…</span>}
            </Field>
          </div>

          {/* Timeline */}
          <div>
            <h2 className="mb-4 text-sm font-semibold uppercase tracking-widest text-slate-500">Timeline</h2>
            <AuditTimeline events={audit} nameFor={nameFor} />
          </div>
        </aside>
      </div>
    </div>
  )
}
