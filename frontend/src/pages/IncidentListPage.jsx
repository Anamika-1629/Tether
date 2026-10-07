import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { SEVERITIES, STATUSES, incidentsApi } from '../api/incidents.js'
import { SeverityBadge, StatusBadge } from '../components/Badges.jsx'
import { ErrorBanner, buttonClass, inputClass } from '../components/Field.jsx'
import { timeAgo } from '../components/time.js'

export default function IncidentListPage() {
  const navigate = useNavigate()
  const [incidents, setIncidents] = useState(null)
  const [filter, setFilter] = useState('')
  const [error, setError] = useState(null)
  const [title, setTitle] = useState('')
  const [severity, setSeverity] = useState('SEV2')
  const [creating, setCreating] = useState(false)

  const load = useCallback(async () => {
    try {
      setIncidents(await incidentsApi.list(filter || undefined))
      setError(null)
    } catch (err) {
      setError(err.message)
    }
  }, [filter])

  useEffect(() => {
    load()
    const timer = setInterval(load, 15_000)
    return () => clearInterval(timer)
  }, [load])

  const create = async (e) => {
    e.preventDefault()
    setCreating(true)
    try {
      const incident = await incidentsApi.create({ title: title.trim(), severity })
      navigate(`/incidents/${incident.id}`)
    } catch (err) {
      setError(err.message)
      setCreating(false)
    }
  }

  const SEVERITY_BG = {
    SEV1: 'bg-red-600/10 border-red-900/40 hover:border-red-700/60',
    SEV2: 'bg-orange-500/10 border-orange-900/40 hover:border-orange-700/60',
    SEV3: 'bg-yellow-400/10 border-yellow-900/40 hover:border-yellow-700/60',
    SEV4: 'bg-slate-500/10 border-slate-700/40 hover:border-slate-600/60',
  }

  return (
    <div className="space-y-8 fade-in">
      {/* ── Declare incident form ──────────────────────────────── */}
      <section>
        <div className="mb-3 flex items-center gap-2">
          <div className="h-px flex-1 bg-gradient-to-r from-transparent via-slate-800 to-transparent" />
          <span className="text-xs font-semibold uppercase tracking-widest text-slate-500">Declare incident</span>
          <div className="h-px flex-1 bg-gradient-to-r from-transparent via-slate-800 to-transparent" />
        </div>

        <form
          id="declare-incident-form"
          onSubmit={create}
          className="glass flex flex-col gap-3 rounded-2xl p-5 sm:flex-row sm:items-end"
        >
          <div className="flex-1">
            <label className="mb-1.5 block text-xs font-semibold uppercase tracking-widest text-slate-500">
              What's broken?
            </label>
            <input
              id="incident-title-input"
              className={inputClass}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="e.g. Checkout API returning 500s"
              maxLength={255}
              required
            />
          </div>

          <div>
            <label className="mb-1.5 block text-xs font-semibold uppercase tracking-widest text-slate-500">
              Severity
            </label>
            <select
              id="incident-severity-select"
              className={`${inputClass} sm:w-28`}
              value={severity}
              onChange={(e) => setSeverity(e.target.value)}
              aria-label="Severity"
            >
              {SEVERITIES.map((s) => <option key={s}>{s}</option>)}
            </select>
          </div>

          <button
            id="declare-incident-btn"
            className="btn-danger shrink-0"
            disabled={creating || !title.trim()}
          >
            {creating ? (
              <span className="flex items-center gap-2">
                <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                Opening…
              </span>
            ) : (
              <span className="flex items-center gap-2">
                <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
                </svg>
                Declare incident
              </span>
            )}
          </button>
        </form>
      </section>

      {/* ── Incident list ──────────────────────────────────────── */}
      <section>
        {/* Header + filters */}
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <h1 className="mr-3 text-xl font-bold tracking-tight text-slate-100">Active Incidents</h1>
          {/* Auto-refresh indicator */}
          <span className="flex items-center gap-1 text-xs text-slate-600" title="Refreshes every 15 seconds">
            <svg className="h-3 w-3 animate-spin" fill="none" viewBox="0 0 24 24">
              <path className="opacity-40" stroke="currentColor" strokeWidth="2.5" d="M12 3v3m0 12v3M3 12h3m12 0h3" />
            </svg>
            live
          </span>
          <div className="ml-auto flex flex-wrap gap-1.5">
            {['', ...STATUSES].map((s) => (
              <button
                key={s || 'all'}
                id={`filter-${s || 'all'}`}
                onClick={() => setFilter(s)}
                className={`rounded-full px-3 py-1 text-xs font-semibold transition-all duration-150 ${
                  filter === s
                    ? 'bg-sky-600 text-white shadow-md shadow-sky-900/30'
                    : 'bg-slate-800/80 text-slate-400 hover:bg-slate-700 hover:text-slate-200'
                }`}
              >
                {s ? s.toLowerCase() : 'all'}
              </button>
            ))}
          </div>
        </div>

        <ErrorBanner error={error} />

        {/* Loading skeleton */}
        {incidents === null && !error && (
          <div className="space-y-2">
            {[...Array(3)].map((_, i) => (
              <div
                key={i}
                className="h-14 animate-pulse rounded-xl bg-slate-800/40"
                style={{ animationDelay: `${i * 100}ms` }}
              />
            ))}
          </div>
        )}

        {/* Empty state */}
        {incidents?.length === 0 && (
          <div className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-slate-800 py-20 text-center">
            <div className="mb-3 text-4xl" aria-hidden>🌙</div>
            <p className="text-slate-400">
              No {filter ? <span className="text-slate-300">{filter.toLowerCase()}</span> : ''} incidents
            </p>
            <p className="mt-1 text-sm text-slate-600">All quiet on the western front.</p>
          </div>
        )}

        {/* Incident cards */}
        {incidents?.length > 0 && (
          <ul
            className="overflow-hidden rounded-2xl ring-1 ring-slate-800"
            role="list"
          >
            {incidents.map((i, idx) => (
              <li key={i.id} className="slide-up" style={{ animationDelay: `${idx * 50}ms` }}>
                <Link
                  to={`/incidents/${i.id}`}
                  id={`incident-${i.id}`}
                  className={`incident-card ${SEVERITY_BG[i.severity] ?? ''} border-b last:border-b-0`}
                >
                  {/* Severity */}
                  <SeverityBadge severity={i.severity} />

                  {/* Title */}
                  <span className="min-w-0 flex-1 truncate font-semibold text-slate-100">
                    {i.title}
                  </span>

                  {/* Owner */}
                  <span className="hidden shrink-0 text-sm text-slate-500 sm:block">
                    {i.owner ?? <span className="italic">unassigned</span>}
                  </span>

                  {/* Status */}
                  <StatusBadge status={i.status} />

                  {/* Time */}
                  <span className="w-20 shrink-0 text-right text-xs text-slate-600">
                    {timeAgo(i.createdAt)}
                  </span>

                  {/* Chevron */}
                  <svg className="h-4 w-4 shrink-0 text-slate-600" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                  </svg>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
