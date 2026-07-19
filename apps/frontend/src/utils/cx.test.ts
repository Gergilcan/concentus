import { describe, expect, it } from 'vitest'
import { cx } from './cx.ts'

// `cx` replaces manual `${a} ${cond ? b : ''}` concatenation across the node/inspector
// components — it must drop every falsy input and join the rest with a single space.
describe('cx', () => {
  it('returns an empty string when called with no args', () => {
    expect(cx()).toBe('')
  })

  it('joins multiple truthy classes with a single space', () => {
    expect(cx('a', 'b', 'c')).toBe('a b c')
  })

  it('filters out false, null, undefined, and empty-string values', () => {
    expect(cx('a', false, 'b', null, 'c', undefined, '', 'd')).toBe('a b c d')
  })

  it('returns an empty string when every input is falsy', () => {
    expect(cx(false, null, undefined, '')).toBe('')
  })

  it('supports the conditional-class pattern used by callers', () => {
    const selected = true
    const coordinator = false
    expect(cx('node', selected && 'selected', coordinator && 'coordinator')).toBe('node selected')
  })
})
