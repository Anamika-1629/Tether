/** "2 responders active" with initials avatars. Deliberately readable at a glance mid-outage. */
export default function PresenceIndicator({ peers, live }) {
  const count = peers.length
  return (
    <div className="flex items-center gap-3" aria-live="polite">
      {/* Pulse dot */}
      <span className="relative flex h-2.5 w-2.5 shrink-0">
        {live && count > 1 && (
          <span className="ping-slow absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-60" />
        )}
        <span className={`relative inline-flex h-2.5 w-2.5 rounded-full ${live ? 'bg-emerald-400' : 'bg-slate-500'}`} />
      </span>

      {/* Count text */}
      <span className="text-sm font-medium text-slate-200" data-testid="presence-count">
        {count} {count === 1 ? 'responder' : 'responders'} active
      </span>

      {/* Avatar stack — up to 4 shown */}
      {peers.length > 0 && (
        <div className="flex -space-x-2">
          {peers.slice(0, 4).map((p) => (
            <span
              key={p.id}
              title={`${p.name} (${p.email})`}
              style={{ backgroundColor: p.color }}
              className="flex h-7 w-7 items-center justify-center rounded-full text-[10px] font-bold text-white ring-2 ring-slate-950 transition-transform hover:z-10 hover:scale-110"
            >
              {initials(p.name)}
            </span>
          ))}
          {peers.length > 4 && (
            <span className="flex h-7 w-7 items-center justify-center rounded-full bg-slate-700 text-[10px] font-bold text-slate-300 ring-2 ring-slate-950">
              +{peers.length - 4}
            </span>
          )}
        </div>
      )}
    </div>
  )
}

function initials(name) {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('')
}
