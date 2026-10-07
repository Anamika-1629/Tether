import { describe, expect, it } from 'vitest'
import * as Y from 'yjs'
import { diffText, transformIndex } from './textDiff.js'

describe('diffText', () => {
  it('finds a typed character', () => {
    expect(diffText('helo', 'hello')).toEqual({ index: 3, remove: 0, insert: 'l' })
  })
  it('finds a deletion', () => {
    expect(diffText('hello world', 'hello')).toEqual({ index: 5, remove: 6, insert: '' })
  })
  it('finds a replaced selection', () => {
    expect(diffText('status: OPEN', 'status: RESOLVED')).toEqual({ index: 8, remove: 4, insert: 'RESOLVED' })
  })
  it('handles repeated characters without over-matching', () => {
    expect(diffText('aaa', 'aaaa')).toEqual({ index: 3, remove: 0, insert: 'a' })
  })
  it('is a no-op for identical text', () => {
    expect(diffText('same', 'same')).toEqual({ index: 4, remove: 0, insert: '' })
  })
  it('applied to a Y.Text, always reproduces the new text', () => {
    const cases = [['', 'abc'], ['abc', ''], ['abcdef', 'abXYef'], ['line1\nline2', 'line1\nnew\nline2'], ['🔥 fire', '🔥🔥 fire']]
    for (const [from, to] of cases) {
      const doc = new Y.Doc()
      const text = doc.getText('t')
      text.insert(0, from)
      const { index, remove, insert } = diffText(from, to)
      text.delete(index, remove)
      text.insert(index, insert)
      expect(text.toString()).toBe(to)
    }
  })
})

describe('transformIndex', () => {
  it('moves the caret right when text is inserted before it', () => {
    expect(transformIndex(5, [{ retain: 2 }, { insert: 'abc' }])).toBe(8)
  })
  it('keeps the caret when text is inserted after it', () => {
    expect(transformIndex(5, [{ retain: 7 }, { insert: 'abc' }])).toBe(5)
  })
  it('keeps the caret before text inserted exactly at it', () => {
    expect(transformIndex(5, [{ retain: 5 }, { insert: 'abc' }])).toBe(5)
  })
  it('moves the caret left when text before it is deleted', () => {
    expect(transformIndex(10, [{ retain: 2 }, { delete: 3 }])).toBe(7)
  })
  it('clamps the caret to the start of a deletion that covers it', () => {
    expect(transformIndex(5, [{ retain: 3 }, { delete: 10 }])).toBe(3)
  })
  it('handles a remote insert at the very start', () => {
    expect(transformIndex(0, [{ insert: 'x' }])).toBe(0)
    expect(transformIndex(4, [{ insert: 'xy' }])).toBe(6)
  })
  it('matches the real delta Yjs reports for a concurrent edit', () => {
    const a = new Y.Doc()
    const b = new Y.Doc()
    a.getText('t').insert(0, 'hello world')
    Y.applyUpdate(b, Y.encodeStateAsUpdate(a))
    let delta
    b.getText('t').observe((e) => { delta = e.delta })
    // Bob's caret is after "world" (index 11); Alice types at the start
    a.getText('t').insert(0, '>> ')
    Y.applyUpdate(b, Y.encodeStateAsUpdate(a, Y.encodeStateVector(b)))
    expect(b.getText('t').toString().slice(0, transformIndex(11, delta))).toBe('>> hello world')
  })
})
