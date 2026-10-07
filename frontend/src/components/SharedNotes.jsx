import { useEffect, useRef } from 'react'
import { diffText, transformIndex } from '../sync/textDiff.js'

const LOCAL = Symbol('textarea')

/**
 * A plain textarea bound to a Y.Text. Typing becomes a minimal Yjs edit; remote edits update the
 * text while keeping your cursor on what you were typing. No rich-text editor: fewer moving parts
 * to fail during an outage, and pasted logs stay exactly as pasted.
 */
export default function SharedNotes({ room }) {
  const ref = useRef(null)

  useEffect(() => {
    const el = ref.current
    if (!room || !el) return
    const { ydoc, notes } = room
    el.value = notes.toString()

    const onRemote = (event, transaction) => {
      if (transaction.origin === LOCAL) return
      const focused = document.activeElement === el
      const { selectionStart, selectionEnd } = el
      el.value = notes.toString()
      if (focused) el.setSelectionRange(transformIndex(selectionStart, event.delta), transformIndex(selectionEnd, event.delta))
    }
    const onInput = () => {
      const { index, remove, insert } = diffText(notes.toString(), el.value)
      if (!remove && !insert) return
      ydoc.transact(() => {
        if (remove) notes.delete(index, remove)
        if (insert) notes.insert(index, insert)
      }, LOCAL)
    }

    notes.observe(onRemote)
    el.addEventListener('input', onInput)
    return () => {
      notes.unobserve(onRemote)
      el.removeEventListener('input', onInput)
    }
  }, [room])

  return (
    <div className="relative">
      {!room && (
        <div className="absolute inset-0 z-10 flex items-center justify-center rounded-lg bg-slate-950/50 backdrop-blur-sm">
          <div className="flex items-center gap-2 text-sm text-slate-400">
            <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            Connecting to sync…
          </div>
        </div>
      )}
      <textarea
        ref={ref}
        disabled={!room}
        spellCheck={false}
        data-testid="shared-notes"
        placeholder={'Shared notes — everyone in this incident sees edits live.\n\n14:02  Checkout 500s started, ~30% of requests\n14:05  Suspect payments DB connection pool\n\n(paste logs, commands, or findings here)'}
        className="notes-area"
      />
    </div>
  )
}
