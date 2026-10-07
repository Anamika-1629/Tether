/** "2 responders active" plus initials. Deliberately plain: it has to be readable at a glance mid-outage. */
export default function PresenceIndicator({ peers, live }) {
  const count = peers.length
  return (
    <div className="flex items-center gap-3" aria-live="polite">
      <span className="relative flex h-2.5 w-2.5">
        {live && count > 1 && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />}
        <span className={`relative inline-flex h-2.5 w-2.5 rounded-full ${live ? 'bg-emerald-400' : 'bg-slate-500'}`} />
      </span>
      <span className="text-sm font-medium text-slate-200" data-testid="presence-count">
        {count} {count === 1 ? 'responder' : 'responders'} active
      </span>
      <div className="flex -space-x-2">
        {peers.map((p) => (
          <span
            key={p.id}
            title={`${p.name} (${p.email})`}
            style={{ backgroundColor: p.color }}
            className="flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold text-white ring-2 ring-slate-950"
          >
            {initials(p.name)}
          </span>
        ))}
      </div>
    </div>
  )
}

function initials(name) {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('')
}
