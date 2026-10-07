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
    <textarea
      ref={ref}
      disabled={!room}
      spellCheck={false}
      data-testid="shared-notes"
      placeholder={'Shared notes: everyone in this incident sees edits live.\n\n14:02 Checkout 500s started, ~30% of requests\n14:05 Suspect payments DB connection pool\n(paste logs here)'}
      className="h-[28rem] w-full resize-y rounded-lg border border-slate-800 bg-slate-900 p-4 font-mono text-sm leading-relaxed text-slate-100 placeholder:text-slate-600 focus:border-slate-600 focus:outline-none disabled:opacity-50"
    />
  )
}
