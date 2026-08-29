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
 */
export type WiringRole = 'feeds' | 'both'

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
 * A sub-flow is the one non-consumer that may be a target, and only from an agent: that wire is
 * the hand-off, "when this agent is done, run that flow". A knowledge base pointing at a sub-flow
 * would be a picture of nothing.
 */
export function canConnect(source: NodeKind, target: NodeKind): boolean {
  if (CONSUMERS.includes(target)) return true
  // A gate takes the place of the wire it stands in, so whatever could point at the branch may
  // point at the gate, and the gate may point wherever that wire was going. Gates chain: "for
  // each item, and only the ones that mention a deadline" is two boxes in a row.
  if (GATES.includes(target)) return CONSUMERS.includes(source) || GATES.includes(source)
  if (target === 'flow') return CONSUMERS.includes(source) || GATES.includes(source)
  return false
}
