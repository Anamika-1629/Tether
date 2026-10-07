const STATES = {
  live: { label: 'Live', dot: 'bg-emerald-400', text: 'text-emerald-300', hint: 'Edits sync to everyone instantly.' },
  connecting: { label: 'Connecting…', dot: 'bg-amber-400 animate-pulse', text: 'text-amber-300', hint: 'Reaching the Sync Service. You can keep typing.' },
  offline: { label: 'Offline', dot: 'bg-slate-400', text: 'text-slate-300', hint: 'Notes are saved on this device and will merge automatically when you reconnect.' },
  denied: { label: 'No sync access', dot: 'bg-red-500', text: 'text-red-300', hint: 'The Sync Service rejected your session. Sign in again.' },
}

export default function ConnectionBadge({ status }) {
  const s = STATES[status] ?? STATES.connecting
  return (
    <span title={s.hint} className={`inline-flex items-center gap-2 rounded-full bg-slate-900 px-3 py-1 text-xs font-medium ring-1 ring-slate-800 ${s.text}`} data-testid="connection-status">
      <span className={`h-2 w-2 rounded-full ${s.dot}`} />
      {s.label}
    </span>
  )
}

export function connectionHint(status) {
  return (STATES[status] ?? STATES.connecting).hint
}
