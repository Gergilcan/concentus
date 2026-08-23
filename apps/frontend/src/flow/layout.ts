import dagre from '@dagrejs/dagre'

/**
 * "Tidy this up" for a canvas that grew by hand.
 *
 * Automatic placement already exists for the node being added; this is for the other case — a
 * flow assembled over a week, boxes nudged wherever there was room, that nobody wants to
 * re-arrange by hand. One pass of dagre puts everything left to right by rank: whatever feeds a
 * node — chain parents, MCPs, repos, knowledge, the trigger — lands in the column to its LEFT,
 * visibly upstream of it.
 *
 * Every node goes through dagre, capabilities included. They used to be plucked out and hung
 * underneath their consumer at a fixed drop (the convenience placement addNode still uses when a
 * capability is first created) — which put them in the SAME column as the agent, ignored the
 * agent's real height (tall cards overlapped their own capabilities), and hid them from dagre
 * entirely, so they collided with the next row too. Rank order is the tidy-up's whole statement:
 * feeders left, consumers right, with measured sizes deciding the spacing.
 *
 * Pure: takes nodes and edges, returns new positions by id. The store applies them (behind an
 * undo checkpoint — a layout you hate must be one Ctrl+Z away from gone).
 */

/** The structural slice of a canvas node this needs — kept local so the store can import this. */
export interface LayoutNode {
  id: string
  position: { x: number; y: number }
  measured?: { width?: number; height?: number }
}

export interface LayoutEdge {
  source: string
  target: string
}

/** Must match the card width in nodes.module.scss; measured sizes win when the canvas has them. */
const NODE_W = 214
const NODE_H = 110

export function tidyLayout(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
): Map<string, { x: number; y: number }> {
  const out = new Map<string, { x: number; y: number }>()
  if (nodes.length < 2) return out

  const byId = new Map(nodes.map((n) => [n.id, n]))

  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 48, ranksep: 90, marginx: 40, marginy: 40 })
  g.setDefaultEdgeLabel(() => ({}))
  for (const n of nodes) {
    g.setNode(n.id, {
      width: n.measured?.width ?? NODE_W,
      height: n.measured?.height ?? NODE_H,
    })
  }
  for (const e of edges) {
    if (byId.has(e.source) && byId.has(e.target)) g.setEdge(e.source, e.target)
  }
  dagre.layout(g)

  for (const n of nodes) {
    const placed = g.node(n.id)
    if (!placed) continue
    // dagre positions centres; the canvas positions top-left corners.
    out.set(n.id, {
      x: Math.round(placed.x - (n.measured?.width ?? NODE_W) / 2),
      y: Math.round(placed.y - (n.measured?.height ?? NODE_H) / 2),
    })
  }

  return out
}
