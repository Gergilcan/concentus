import type { Edge } from '@xyflow/react'

/**
 * The selected block's immediate neighbours, split by direction.
 *
 * <p>One hop, deliberately. Lighting up the whole ancestry would colour most of a real flow and
 * answer no question at all; what people ask standing in front of a graph is local — what feeds
 * this box, and what does it feed. Those are two different questions, which is why the two sets
 * are kept apart instead of merged into "connected": one is what this block depends on, the
 * other is what changing it will affect.
 */
export interface Kinship {
  /** Sources of edges arriving here — upstream, what this block reads. */
  inbound: Set<string>
  /** Targets of edges leaving here — downstream, what this block feeds. */
  outbound: Set<string>
}

const NONE: Kinship = { inbound: new Set(), outbound: new Set() }

export function kinship(edges: Edge[], selectedId: string | null): Kinship {
  if (!selectedId) return NONE
  const inbound = new Set<string>()
  const outbound = new Set<string>()
  for (const e of edges) {
    if (e.target === selectedId) inbound.add(e.source)
    if (e.source === selectedId) outbound.add(e.target)
  }
  return { inbound, outbound }
}

/**
 * The class a node wears, or undefined for one that is not a neighbour.
 *
 * <p>Inbound wins when a box is both — which happens in a loop, and in the diamond where one
 * block both feeds the selected one and is fed by it. Either answer is defensible; picking one
 * and saying so beats leaving it to the order the edges happen to be in.
 */
export function kinClass(kin: Kinship, id: string): 'kin-in' | 'kin-out' | undefined {
  if (kin.inbound.has(id)) return 'kin-in'
  if (kin.outbound.has(id)) return 'kin-out'
  return undefined
}

/** The same for a wire: an edge arriving at the selection is inbound, one leaving it outbound. */
export function edgeKinClass(edge: Edge, selectedId: string | null): 'kin-in' | 'kin-out' | undefined {
  if (!selectedId) return undefined
  if (edge.target === selectedId) return 'kin-in'
  if (edge.source === selectedId) return 'kin-out'
  return undefined
}
