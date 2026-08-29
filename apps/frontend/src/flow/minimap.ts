/**
 * The minimap's on/off choice, and the colours it paints.
 *
 * Off by default on a narrow window: a 200×150 map is a real bite out of a laptop's canvas, and
 * on a wide screen it is the one thing that tells you where the rest of a big flow went. The
 * first toggle is remembered and the width never overrides it again.
 */

export const MINIMAP_KEY = 'concentus.minimap'

/** Below this many CSS pixels the map costs more canvas than it gives back. */
export const MINIMAP_MIN_WIDTH = 900

export function initialMinimap(width: number = window.innerWidth): boolean {
  const stored = localStorage.getItem(MINIMAP_KEY)
  if (stored === 'on') return true
  if (stored === 'off') return false
  return width >= MINIMAP_MIN_WIDTH
}

export function persistMinimap(on: boolean): void {
  localStorage.setItem(MINIMAP_KEY, on ? 'on' : 'off')
}

/** The theme token each kind's card wears on its left border (nodes.module.scss). */
const TOKEN_OF: Record<string, string> = {
  agent: 'agent',
  coordinator: 'coordinator',
  worker: 'agent',
  mcp: 'mcp',
  repo: 'repo',
  sql: 'sql',
  knowledge: 'knowledge',
  api: 'api',
  flow: 'subflow',
  input: 'ok',
  merge: 'merge',
  verifier: 'verifier',
  condition: 'gate',
  foreach: 'gate',
  note: 'muted',
  group: 'border',
}

/**
 * The swatch for a node on the map, as a CSS variable so the map follows the theme the cards
 * follow. React Flow writes it into the rect's inline style, where var() resolves.
 */
export function minimapColor(type: string | undefined): string {
  return `var(--${TOKEN_OF[type ?? ''] ?? 'muted'})`
}
