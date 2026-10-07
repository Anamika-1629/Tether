const STATUS_STYLES = {
  OPEN: 'bg-red-500/15 text-red-300 ring-red-500/30',
  INVESTIGATING: 'bg-amber-500/15 text-amber-300 ring-amber-500/30',
  MITIGATED: 'bg-sky-500/15 text-sky-300 ring-sky-500/30',
  RESOLVED: 'bg-emerald-500/15 text-emerald-300 ring-emerald-500/30',
}

const SEVERITY_STYLES = {
  SEV1: 'bg-red-600 text-white',
  SEV2: 'bg-orange-500 text-white',
  SEV3: 'bg-yellow-400 text-slate-900',
  SEV4: 'bg-slate-500 text-white',
}

export function StatusBadge({ status }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset ${STATUS_STYLES[status] ?? ''}`}>
      {status}
    </span>
  )
}

export function SeverityBadge({ severity }) {
  return (
    <span className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-bold tracking-wide ${SEVERITY_STYLES[severity] ?? ''}`}>
      {severity}
    </span>
  )
}
