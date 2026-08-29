import { beforeEach, describe, expect, it } from 'vitest'
import { initialMinimap, MINIMAP_KEY, minimapColor, persistMinimap } from './minimap.ts'

/**
 * The minimap's choice: the window width decides the first time, the person decides after that,
 * and the choice survives a reload.
 */
describe('minimap preference', () => {
  beforeEach(() => localStorage.removeItem(MINIMAP_KEY))

  it('starts on for a wide window and off for a narrow one, when nobody has chosen', () => {
    expect(initialMinimap(1440)).toBe(true)
    expect(initialMinimap(900)).toBe(true)
    expect(initialMinimap(899)).toBe(false)
  })

  it('remembers the toggle, and the width never overrides it again', () => {
    persistMinimap(false)
    expect(localStorage.getItem(MINIMAP_KEY)).toBe('off')
    expect(initialMinimap(1920)).toBe(false)

    persistMinimap(true)
    expect(localStorage.getItem(MINIMAP_KEY)).toBe('on')
    expect(initialMinimap(640)).toBe(true)
  })

  it('shrugs off a value it did not write', () => {
    localStorage.setItem(MINIMAP_KEY, 'maybe')
    expect(initialMinimap(1440)).toBe(true)
    expect(initialMinimap(400)).toBe(false)
  })
})

describe('minimap colours', () => {
  it('paints each kind with the theme token its card wears, and the unknown in muted', () => {
    expect(minimapColor('agent')).toBe('var(--agent)')
    expect(minimapColor('coordinator')).toBe('var(--coordinator)')
    expect(minimapColor('flow')).toBe('var(--subflow)')
    expect(minimapColor('condition')).toBe('var(--gate)')
    expect(minimapColor('foreach')).toBe('var(--gate)')
    expect(minimapColor('input')).toBe('var(--ok)')
    expect(minimapColor('worker')).toBe('var(--agent)')
    expect(minimapColor('note')).toBe('var(--muted)')
    expect(minimapColor(undefined)).toBe('var(--muted)')
  })
})
