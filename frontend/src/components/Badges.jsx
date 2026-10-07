const STATUS_STYLES = {
  OPEN:          'bg-red-500/15 text-red-300 ring-1 ring-inset ring-red-500/30',
  INVESTIGATING: 'bg-amber-500/15 text-amber-300 ring-1 ring-inset ring-amber-500/30',
  MITIGATED:     'bg-sky-500/15 text-sky-300 ring-1 ring-inset ring-sky-500/30',
  RESOLVED:      'bg-emerald-500/15 text-emerald-300 ring-1 ring-inset ring-emerald-500/30',
}

const STATUS_DOTS = {
  OPEN:          'bg-red-400',
  INVESTIGATING: 'bg-amber-400',
  MITIGATED:     'bg-sky-400',
  RESOLVED:      'bg-emerald-400',
}

const SEVERITY_STYLES = {
  SEV1: 'bg-red-600/90 text-white shadow-lg shadow-red-900/30',
  SEV2: 'bg-orange-500/90 text-white shadow-lg shadow-orange-900/20',
  SEV3: 'bg-yellow-400/90 text-slate-900 shadow-sm',
  SEV4: 'bg-slate-600/90 text-slate-200',
}

export function StatusBadge({ status }) {
  return (
    <span className={`status-badge ${STATUS_STYLES[status] ?? ''}`}>
      <span className={`inline-block h-1.5 w-1.5 rounded-full ${STATUS_DOTS[status] ?? 'bg-slate-400'}`} />
      {status?.toLowerCase()}
    </span>
  )
}

export function SeverityBadge({ severity }) {
  return (
    <span className={`sev-badge ${SEVERITY_STYLES[severity] ?? ''}`}>
      {severity}
    </span>
  )
}
