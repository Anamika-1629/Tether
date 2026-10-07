/**
 * Pure helpers that bind a plain <textarea> to a Y.Text without a rich-text editor.
 * Positions are UTF-16 code units, which is what both Y.Text and textarea selections use.
 */

/** The single edit that turns oldText into newText: delete `remove` chars at `index`, then insert `insert`. */
export function diffText(oldText, newText) {
  const max = Math.min(oldText.length, newText.length)
  let start = 0
  while (start < max && oldText[start] === newText[start]) start++
  let oldEnd = oldText.length
  let newEnd = newText.length
  while (oldEnd > start && newEnd > start && oldText[oldEnd - 1] === newText[newEnd - 1]) {
    oldEnd--
    newEnd--
  }
  return { index: start, remove: oldEnd - start, insert: newText.slice(start, newEnd) }
}

/**
 * Where a caret at `index` should move after a remote change described by a Yjs delta
 * ([{retain}, {insert}, {delete}]), so your cursor stays on the text you were typing.
 * Text inserted exactly at the caret lands after it.
 */
export function transformIndex(index, delta) {
  let oldPos = 0
  let result = index
  for (const op of delta) {
    if (oldPos > index) break
    if (op.retain != null) {
      oldPos += op.retain
    } else if (op.insert != null) {
      if (oldPos < index) result += typeof op.insert === 'string' ? op.insert.length : 1
    } else if (op.delete != null) {
      result -= Math.max(0, Math.min(op.delete, index - oldPos))
      oldPos += op.delete
    }
  }
  return result
}
