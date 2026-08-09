import { describe, expect, it } from 'vitest'
import { runBelongsToOpenFlow } from './useSelectedRun.ts'

describe('runBelongsToOpenFlow', () => {
  it('drops a run belonging to another flow', () => {
    expect(runBelongsToOpenFlow('flow-a', 'flow-b', true)).toBe(false)
  })

  it('keeps a run belonging to the open flow', () => {
    expect(runBelongsToOpenFlow('flow-a', 'flow-a', true)).toBe(true)
  })

  it('keeps an ad-hoc run on the unsaved canvas it came from', () => {
    // Both null: a run started from a canvas that was never saved belongs to that canvas.
    expect(runBelongsToOpenFlow(null, null, true)).toBe(true)
  })

  it('drops an ad-hoc run once a saved flow is opened', () => {
    expect(runBelongsToOpenFlow(null, 'flow-a', true)).toBe(false)
  })

  it('keeps a run it knows nothing about yet', () => {
    // The runs list arrives asynchronously. Clearing on absence would wipe the selection during
    // the load race on every page refresh — the exact thing the persistence exists to survive.
    expect(runBelongsToOpenFlow(undefined, 'flow-a', false)).toBe(true)
  })
})
