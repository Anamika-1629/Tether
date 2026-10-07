import { timeAgo } from './time.js'

/** The Incident Service's append-only audit log, newest first. */
export default function AuditTimeline({ events, nameFor }) {
  if (!events.length) return <p className="text-sm text-slate-500">No changes yet.</p>
  return (
    <ol className="relative space-y-4 border-l border-slate-800 pl-5">
      {[...events].reverse().map((e) => (
        <li key={e.id} className="relative">
          <span className={`absolute -left-[1.6rem] top-1.5 h-2.5 w-2.5 rounded-full ring-4 ring-slate-950 ${e.action === 'CREATED' ? 'bg-sky-400' : 'bg-slate-500'}`} />
          <p className="text-sm text-slate-200">
            <span className="font-semibold">{nameFor(e.actor)}</span>{' '}
            {e.action === 'CREATED' ? 'opened the incident' : (
              <>
                changed <span className="text-slate-400">{e.field}</span>{' '}
                {e.oldValue ? <span className="text-slate-500 line-through">{nameFor(e.oldValue)}</span> : <span className="text-slate-500">none</span>}
                {' → '}<span className="font-medium text-slate-100">{nameFor(e.newValue)}</span>
              </>
            )}
          </p>
          <time className="text-xs text-slate-500" dateTime={e.at} title={new Date(e.at).toLocaleString()}>{timeAgo(e.at)}</time>
        </li>
      ))}
    </ol>
  )
}
