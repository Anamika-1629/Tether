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

  return (
    <div className="space-y-6">
      <form onSubmit={create} className="flex flex-col gap-3 rounded-xl border border-slate-800 bg-slate-900/60 p-4 sm:flex-row">
        <input
          className={`${inputClass} flex-1`}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="What's broken? e.g. Checkout API returning 500s"
          maxLength={255}
          required
        />
        <select className={`${inputClass} sm:w-28`} value={severity} onChange={(e) => setSeverity(e.target.value)} aria-label="Severity">
          {SEVERITIES.map((s) => <option key={s}>{s}</option>)}
        </select>
        <button className={`${buttonClass} bg-red-600 hover:bg-red-500`} disabled={creating || !title.trim()}>
          {creating ? 'Opening…' : 'Declare incident'}
        </button>
      </form>

      <div className="flex flex-wrap items-center gap-2">
        <h1 className="mr-4 text-xl font-semibold">Incidents</h1>
        {['', ...STATUSES].map((s) => (
          <button
            key={s || 'all'}
            onClick={() => setFilter(s)}
            className={`rounded-full px-3 py-1 text-xs font-medium ${filter === s ? 'bg-slate-100 text-slate-900' : 'bg-slate-800 text-slate-300 hover:bg-slate-700'}`}
          >
            {s ? s.toLowerCase() : 'all'}
          </button>
        ))}
      </div>

      <ErrorBanner error={error} />

      {incidents === null && !error && <p className="text-sm text-slate-500">Loading incidents…</p>}
      {incidents?.length === 0 && (
        <div className="rounded-xl border border-dashed border-slate-800 py-16 text-center text-slate-500">
          No {filter ? filter.toLowerCase() : ''} incidents. All quiet. 🌙
        </div>
      )}

      {incidents?.length > 0 && (
      <ul className="divide-y divide-slate-800 overflow-hidden rounded-xl border border-slate-800">
        {incidents.map((i) => (
          <li key={i.id}>
            <Link to={`/incidents/${i.id}`} className="flex items-center gap-4 bg-slate-900/40 px-4 py-3 hover:bg-slate-900">
              <SeverityBadge severity={i.severity} />
              <span className="flex-1 truncate font-medium">{i.title}</span>
              <span className="hidden text-sm text-slate-400 sm:block">{i.owner ?? 'unassigned'}</span>
              <StatusBadge status={i.status} />
              <span className="w-20 text-right text-xs text-slate-500">{timeAgo(i.createdAt)}</span>
            </Link>
          </li>
        ))}
      </ul>
      )}
    </div>
  )
}
