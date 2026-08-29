/**
 * What the command palette can do, and how typing narrows it down.
 *
 * The matching is deliberately a SUBSEQUENCE match ("mtr" finds "Mail triage"), not a substring
 * one: the palette's promise is that you can reach anything with a few keystrokes, and that only
 * holds if the keystrokes can be the initials of what you want rather than a prefix of it.
 *
 * Kept apart from the component because the ranking is the part that can be quietly wrong — a
 * palette that finds the right thing but puts it fourth is a palette people stop using.
 */

import type { AppNodeData } from '../api/types.ts'
import { blockNameOf, KIND_LABEL } from '../flow/kindNames.ts'

export interface Command {
  id: string
  /** What the row says. Matched against. */
  label: string
  /** The group heading — "Go to", "Flows", "Runs", "Theme". Also matched, so "flow d" works. */
  group: string
  /** Extra context on the right of the row (a status, a time). Not matched. */
  hint?: string
  run: () => void
}

/** The shape of react-i18next's `t` this module needs: a key and its placeholders. */
type Translate = (key: string, values?: Record<string, unknown>) => string

/**
 * One "Go to block" row per block of the flow open in the Studio. Choosing one hands the id to
 * `focus`, which is the store's focus request — the palette never needs a React Flow instance,
 * and the canvas answers the request when it is on screen.
 */
export function blockCommands(
  nodes: { id: string; data: AppNodeData }[],
  t: Translate,
  focus: (id: string) => void,
): Command[] {
  return nodes.map((n) => {
    const kind = t(KIND_LABEL[n.data.kind])
    return {
      id: `block:${n.id}`,
      group: t('Blocks'),
      label: t('Go to block: {{name}} ({{kind}})', { name: blockNameOf(n.data) || kind, kind }),
      run: () => focus(n.id),
    }
  })
}

interface Scored {
  command: Command
  score: number
}

/**
 * Commands that match the query, best first. An empty query keeps the natural order — the palette
 * opens on the things its caller thought were most useful, not on an alphabetical list.
 */
export function filterCommands(commands: Command[], query: string): Command[] {
  const q = query.trim().toLowerCase()
  if (!q) return commands

  const scored: Scored[] = []
  commands.forEach((command, index) => {
    const score = scoreOf(`${command.group} ${command.label}`.toLowerCase(), q)
    // Ties broken by the caller's order, so the palette's own idea of importance survives.
    if (score !== null) scored.push({ command, score: score * 1000 + index })
  })
  return scored.sort((a, b) => a.score - b.score).map((s) => s.command)
}

/**
 * How well a haystack matches a subsequence query — lower is better, null is no match.
 *
 * The cost is the DISTANCE the match spans: a query whose letters land close together (and,
 * ideally, at the start of words) scores better than one scattered across a long label. That is
 * what puts "Mail triage" above "Monthly transfer report" for "mtr" when both technically match.
 */
function scoreOf(haystack: string, query: string): number | null {
  let at = 0
  let first = -1
  let last = -1
  let wordStarts = 0
  for (const ch of query) {
    if (ch === ' ') continue // spaces separate words in the query, they are not letters to find
    const found = haystack.indexOf(ch, at)
    if (found < 0) return null
    if (first < 0) first = found
    last = found
    if (found === 0 || haystack[found - 1] === ' ' || haystack[found - 1] === '-') wordStarts++
    at = found + 1
  }
  if (first < 0) return 0
  const span = last - first
  // A letter landing at the start of a word is worth more than one in the middle: it is what
  // makes initials work, which is how people actually type into a palette.
  return span - wordStarts * 2 + first
}
