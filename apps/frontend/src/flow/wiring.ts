import type { NodeKind } from '../api/types.ts'

/**
 * What each kind of box can be at the end of a wire.
 *
 * `feeds` — it only ever hands something over: a trigger, and every capability. An arrow pointing
 * back at one says nothing true, which is why the canvas refuses to draw it.
 *
 * `both` — agents, obviously, and sub-flows. A sub-flow reading both ways is the point rather than
 * an exception: wired into an agent it runs first and its answer becomes context, wired out of one
 * it runs when the flow finishes. That is what let a "when does this run" dropdown disappear — the
 * drawing already said it.
 *
 * `receives` — it only ever takes delivery: a Send mail node. Nothing reads what a mailbox holds,
 * so an arrow leaving one says nothing true either, and the box has no source handle to draw it.
 *
 * `none` — annotations. A note and a frame are for the people reading the canvas; a wire to one
 * would claim the run reads it, and the run never does.
 */
export type WiringRole = 'feeds' | 'both' | 'receives' | 'none'

const ROLES: Record<NodeKind, WiringRole> = {
  input: 'feeds',
  mcp: 'feeds',
  repo: 'feeds',
  sql: 'feeds',
  knowledge: 'feeds',
  api: 'feeds',
  flow: 'both',
  agent: 'both',
  coordinator: 'both',
  merge: 'both',
  verifier: 'both',
  // Gates sit mid-wire: something feeds them, they feed what comes next. Reading both ways is
  // what they are for.
  condition: 'both',
  foreach: 'both',
  mail: 'receives',
  note: 'none',
  group: 'none',
}

/** Drawn for people, ignored by the run: no handles, no wires, nothing to compile. */
export function isAnnotation(kind: NodeKind): boolean {
  return ROLES[kind] === 'none'
}

/** Decision and iteration: they stand between a consumer and the branch they gate. */
const GATES: NodeKind[] = ['condition', 'foreach']

export function wiringRoleOf(kind: NodeKind): WiringRole {
  return ROLES[kind] ?? 'both'
}

/** The kinds that consume: they read what is wired into them and act on it. */
const CONSUMERS: NodeKind[] = ['agent', 'coordinator', 'merge', 'verifier']

/**
 * Whether a wire from one kind to another means anything.
 *
 * Two rules, and everything the canvas refuses follows from them. Only a consumer can be on the
 * receiving end of a capability — an agent cannot feed its MCP server, and a knowledge base cannot
 * feed a SQL source, because neither has anything to do with what arrives.
 *
 * A sub-flow and a Send mail node are the non-consumers that may be a target, and only from an
 * agent: that wire is the hand-off, "when this agent is done, run that flow" or "mail me what it
 * said". A knowledge base pointing at either would be a picture of nothing — and a mail node
 * pointing at anything is one too, so nothing may leave it.
 */
export function canConnect(source: NodeKind, target: NodeKind): boolean {
  if (wiringRoleOf(source) === 'receives') return false
  if (isAnnotation(source) || isAnnotation(target)) return false
  if (CONSUMERS.includes(target)) return true
  // A gate takes the place of the wire it stands in, so whatever could point at the branch may
  // point at the gate, and the gate may point wherever that wire was going. Gates chain: "for
  // each item, and only the ones that mention a deadline" is two boxes in a row.
  if (GATES.includes(target)) return CONSUMERS.includes(source) || GATES.includes(source)
  if (target === 'flow' || target === 'mail') return CONSUMERS.includes(source) || GATES.includes(source)
  return false
}
