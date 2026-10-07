import { timeAgo } from './time.js'

const ACTION_COLORS = {
  CREATED: { dot: 'bg-sky-400', text: 'text-sky-300' },
  _default: { dot: 'bg-slate-600', text: 'text-slate-400' },
}

/** The Incident Service's append-only audit log, newest first. */
export default function AuditTimeline({ events, nameFor }) {
  if (!events.length) {
    return (
      <p className="flex items-center gap-2 text-sm text-slate-500">
        <span className="inline-block h-px flex-1 bg-slate-800" />
        No changes yet
        <span className="inline-block h-px flex-1 bg-slate-800" />
      </p>
    )
  }

  return (
    <ol className="relative space-y-5 border-l border-slate-800 pl-5">
      {[...events].reverse().map((e, idx) => {
        const color = ACTION_COLORS[e.action] ?? ACTION_COLORS._default
        return (
          <li key={e.id} className="relative fade-in" style={{ animationDelay: `${idx * 40}ms` }}>
            <span className={`timeline-dot ${color.dot} ring-4 ring-[#020617]`} />
            <p className="text-sm leading-snug text-slate-200">
              <span className="font-semibold text-slate-100">{nameFor(e.actor)}</span>{' '}
              {e.action === 'CREATED' ? (
                <span className="text-sky-300">opened the incident</span>
              ) : (
                <>
                  changed{' '}
                  <span className="rounded bg-slate-800 px-1 py-0.5 font-mono text-xs text-slate-300">{e.field}</span>{' '}
                  {e.oldValue
                    ? <span className="text-slate-500 line-through">{nameFor(e.oldValue)}</span>
                    : <span className="text-slate-600">—</span>
                  }
                  {' → '}
                  <span className="font-semibold text-slate-100">{nameFor(e.newValue)}</span>
                </>
              )}
            </p>
            <time
              className={`mt-0.5 block text-xs ${color.text}`}
              dateTime={e.at}
              title={new Date(e.at).toLocaleString()}
            >
              {timeAgo(e.at)}
            </time>
          </li>
        )
      })}
    </ol>
  )
}
