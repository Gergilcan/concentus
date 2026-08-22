import { describe, expect, it } from 'vitest'
import type { NodeChange } from '@xyflow/react'
import { applyHelperLines } from './helperLines.ts'

const SIZE = { width: 214, height: 110 }

function nodeAt(id: string, x: number, y: number) {
  return { id, position: { x, y }, measured: { ...SIZE } }
}

function dragging(id: string, x: number, y: number): NodeChange {
  return { id, type: 'position', dragging: true, position: { x, y } }
}

describe('alignment guides while dragging', () => {
  it('snaps the dragged node to a neighbour left edge and reports the line', () => {
    const nodes = [nodeAt('a', 100, 0), nodeAt('b', 500, 400)]
    const change = dragging('b', 104, 400) // 4 units off a's left edge — inside the snap
    const lines = applyHelperLines([change], nodes)

    expect(lines.vertical).toBe(100)
    expect((change as { position: { x: number } }).position.x).toBe(100)
  })

  it('snaps centres, not only edges', () => {
    // Different heights, so only the CENTRES can align: a's centre y is 155, b's is y + 75.
    const nodes = [
      nodeAt('a', 100, 100),
      { id: 'b', position: { x: 500, y: 400 }, measured: { width: 214, height: 150 } },
    ]
    const change = dragging('b', 500, 84) // centre at 159 — 4 units off a's 155
    const lines = applyHelperLines([change], nodes)

    expect(lines.horizontal).toBe(155)
    expect((change as { position: { y: number } }).position.y).toBe(80)
  })

  it('reports nothing when nothing is within reach', () => {
    const nodes = [nodeAt('a', 0, 0), nodeAt('b', 1000, 1000)]
    const change = dragging('b', 900, 900)
    const lines = applyHelperLines([change], nodes)

    expect(lines.vertical).toBeNull()
    expect(lines.horizontal).toBeNull()
  })

  it('stays out of multi-node drags — guides would pull a selection apart', () => {
    const nodes = [nodeAt('a', 100, 0), nodeAt('b', 104, 400), nodeAt('c', 300, 200)]
    const lines = applyHelperLines([dragging('b', 104, 400), dragging('c', 300, 200)], nodes)

    expect(lines.vertical).toBeNull()
    expect(lines.horizontal).toBeNull()
  })

  it('ignores non-drag changes like selection and dimensions', () => {
    const nodes = [nodeAt('a', 100, 0), nodeAt('b', 104, 400)]
    const change: NodeChange = { id: 'b', type: 'select', selected: true }
    const lines = applyHelperLines([change], nodes)

    expect(lines.vertical).toBeNull()
  })
})
