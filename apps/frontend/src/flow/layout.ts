import dagre from '@dagrejs/dagre'

/**
 * "Tidy this up" for a canvas that grew by hand.
 *
 * Automatic placement already exists for the node being added; this is for the other case — a
 * flow assembled over a week, boxes nudged wherever there was room, that nobody wants to
 * re-arrange by hand. One pass of dagre puts the chain left to right; the capabilities an agent
 * was given are then hung underneath it, which is the house style the canvas itself draws
 * (see addNode in the store) and something a generic layered layout cannot express.
 *
 * Pure: takes nodes and edges, returns new positions by id. The store applies them (behind an
 * undo checkpoint — a layout you hate must be one Ctrl+Z away from gone).
 */

/** The structural slice of a canvas node this needs — kept local so the store can import this. */
export interface LayoutNode {
  id: string
  position: { x: number; y: number }
  measured?: { width?: number; height?: number }
  data: { kind: string }
}

export interface LayoutEdge {
  source: string
  target: string
}

/** Must match the card width in nodes.module.scss; measured sizes win when the canvas has them. */
const NODE_W = 214
const NODE_H = 110
/** Horizontal gap between a consumer's capabilities, same as the store's auto-placement. */
const CAP_GAP = 20
/** How far below its consumer a capability hangs, same as the store's auto-placement. */
const CAP_DROP = 150

/** Kinds that hang under a consumer rather than standing in the chain. The trigger is not one —
 * it feeds too, but it belongs at the head of the chain, which is where dagre will put it. */
const CAPABILITY_KINDS = new Set(['mcp', 'repo', 'sql', 'knowledge', 'api'])

export function tidyLayout(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
): Map<string, { x: number; y: number }> {
  const out = new Map<string, { x: number; y: number }>()
  if (nodes.length < 2) return out

  const byId = new Map(nodes.map((n) => [n.id, n]))
  const isCapability = (n: LayoutNode) => CAPABILITY_KINDS.has(n.data.kind)

  // Each capability under the first consumer it feeds; one with no consumer stays a chain node,
  // so it still lands somewhere visible instead of keeping a position the tidy-up ignored.
  const capConsumer = new Map<string, string>()
  for (const n of nodes) {
    if (!isCapability(n)) continue
    const edge = edges.find((e) => e.source === n.id && byId.has(e.target))
    if (edge) capConsumer.set(n.id, edge.target)
  }

  const chain = nodes.filter((n) => !capConsumer.has(n.id))
  const chainIds = new Set(chain.map((n) => n.id))

  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 48, ranksep: 90, marginx: 40, marginy: 40 })
  g.setDefaultEdgeLabel(() => ({}))
  for (const n of chain) {
    g.setNode(n.id, {
      width: n.measured?.width ?? NODE_W,
      height: n.measured?.height ?? NODE_H,
    })
  }
  for (const e of edges) {
    if (chainIds.has(e.source) && chainIds.has(e.target)) g.setEdge(e.source, e.target)
  }
  dagre.layout(g)

  for (const n of chain) {
    const placed = g.node(n.id)
    if (!placed) continue
    // dagre positions centres; the canvas positions top-left corners.
    out.set(n.id, {
      x: Math.round(placed.x - (n.measured?.width ?? NODE_W) / 2),
      y: Math.round(placed.y - (n.measured?.height ?? NODE_H) / 2),
    })
  }

  // Hang the capabilities under their consumers, in a stable order, shoulder to shoulder.
  const perConsumer = new Map<string, number>()
  for (const n of nodes) {
    const consumerId = capConsumer.get(n.id)
    if (!consumerId) continue
    const consumer = byId.get(consumerId)
    const base = out.get(consumerId) ?? consumer?.position
    if (!base) continue
    const index = perConsumer.get(consumerId) ?? 0
    perConsumer.set(consumerId, index + 1)
    out.set(n.id, { x: base.x + index * (NODE_W + CAP_GAP), y: base.y + CAP_DROP })
  }

  return out
}
