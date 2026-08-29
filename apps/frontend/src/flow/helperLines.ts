import type { NodeChange } from '@xyflow/react'
import { NODE_H, NODE_W } from './layout.ts'

/**
 * Figma-style alignment guides while dragging one node.
 *
 * The dragged node snaps to a neighbour's edge or centre when it comes within {@link SNAP} canvas
 * units, and the returned lines say where to draw the guide. Both live in FLOW coordinates — the
 * canvas renders them inside the viewport, so zoom is somebody else's problem.
 *
 * The hook point is the node-change stream rather than a drag event: position changes are applied
 * through onNodesChange, so adjusting the change before it is applied is what makes the node
 * actually stick to the line instead of the line merely appearing near it.
 */

export interface HelperLines {
  /** x of the vertical guide to draw, or null for none. */
  vertical: number | null
  /** y of the horizontal guide. */
  horizontal: number | null
}

export const NO_LINES: HelperLines = { vertical: null, horizontal: null }

/** How close, in flow units, counts as "meant to be aligned". */
const SNAP = 6

interface Placed {
  id: string
  position: { x: number; y: number }
  measured?: { width?: number; height?: number }
  width?: number
  height?: number
  /** Set on a node inside a frame, whose position is relative to the frame rather than the canvas. */
  parentId?: string
}

function sizeOf(n: Placed): { w: number; h: number } {
  return { w: n.measured?.width ?? n.width ?? NODE_W, h: n.measured?.height ?? n.height ?? NODE_H }
}

/**
 * Adjusts a single in-flight position change so it snaps to aligned neighbours, and returns the
 * guides to draw. Mutating the change is the point — it is how the snap reaches the canvas.
 * Anything that is not exactly one node being dragged returns no lines: guides on a multi-drag
 * would pull the selection apart.
 */
export function applyHelperLines(changes: NodeChange[], nodes: Placed[]): HelperLines {
  if (changes.length !== 1) return NO_LINES
  const change = changes[0]
  if (change.type !== 'position' || !change.dragging || !change.position) return NO_LINES

  const moving = nodes.find((n) => n.id === change.id)
  // Inside a frame the positions are in the frame's coordinates, and a guide computed in the
  // canvas's would be drawn where nothing is aligned. No guides there rather than wrong ones.
  if (!moving || moving.parentId) return NO_LINES
  const { w, h } = sizeOf(moving)

  const x = change.position.x
  const y = change.position.y
  // The three vertical lines of the dragged card, and the three horizontal ones.
  const movingXs = [x, x + w / 2, x + w]
  const movingYs = [y, y + h / 2, y + h]

  let best: HelperLines = { vertical: null, horizontal: null }
  let bestDx = SNAP + 1
  let bestDy = SNAP + 1

  for (const other of nodes) {
    if (other.id === change.id) continue
    const { w: ow, h: oh } = sizeOf(other)
    const otherXs = [other.position.x, other.position.x + ow / 2, other.position.x + ow]
    const otherYs = [other.position.y, other.position.y + oh / 2, other.position.y + oh]

    for (let i = 0; i < 3; i++) {
      for (const ox of otherXs) {
        const d = Math.abs(movingXs[i] - ox)
        if (d < bestDx) {
          bestDx = d
          best = { ...best, vertical: ox }
          change.position.x = i === 0 ? ox : i === 1 ? ox - w / 2 : ox - w
        }
      }
      for (const oy of otherYs) {
        const d = Math.abs(movingYs[i] - oy)
        if (d < bestDy) {
          bestDy = d
          best = { ...best, horizontal: oy }
          change.position.y = i === 0 ? oy : i === 1 ? oy - h / 2 : oy - h
        }
      }
    }
  }

  return best
}
