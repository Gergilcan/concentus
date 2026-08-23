import { describe, expect, it } from 'vitest'
import { tidyLayout } from './layout'
import type { LayoutEdge, LayoutNode } from './layout'

/**
 * The tidy-up's contract, stated as geometry. The regression that made this file exist: MCPs were
 * hung under their agent at a fixed 150px drop — same column, blind to the agent's real height,
 * invisible to dagre — and tall agent cards sat on top of their own capabilities.
 */

const node = (id: string, height = 110, width = 214): LayoutNode => ({
  id,
  position: { x: 0, y: 0 },
  measured: { width, height },
})

const edge = (source: string, target: string): LayoutEdge => ({ source, target })

const right = (n: LayoutNode, placed: Map<string, { x: number; y: number }>) =>
  placed.get(n.id)!.x + (n.measured?.width ?? 214)
const bottom = (n: LayoutNode, placed: Map<string, { x: number; y: number }>) =>
  placed.get(n.id)!.y + (n.measured?.height ?? 110)

describe('tidyLayout', () => {
  it('lays a chain out left to right', () => {
    const nodes = [node('trigger'), node('agent'), node('output')]
    const placed = tidyLayout(nodes, [edge('trigger', 'agent'), edge('agent', 'output')])
    expect(placed.get('trigger')!.x).toBeLessThan(placed.get('agent')!.x)
    expect(placed.get('agent')!.x).toBeLessThan(placed.get('output')!.x)
  })

  it('puts everything that feeds a node fully to its left — never in its column', () => {
    const nodes = [node('trigger'), node('mcp'), node('knowledge'), node('agent')]
    const placed = tidyLayout(nodes, [
      edge('trigger', 'agent'),
      edge('mcp', 'agent'),
      edge('knowledge', 'agent'),
    ])
    for (const feeder of ['trigger', 'mcp', 'knowledge']) {
      const f = nodes.find((n) => n.id === feeder)!
      expect(right(f, placed)).toBeLessThanOrEqual(placed.get('agent')!.x)
    }
  })

  it('respects real measured heights: a tall node never overlaps its rank neighbours', () => {
    // The tall agent card was exactly the case the fixed 150px drop got wrong.
    const tallMcp = node('mcp', 320)
    const nodes = [node('trigger'), tallMcp, node('sql'), node('agent', 240)]
    const placed = tidyLayout(nodes, [
      edge('trigger', 'agent'),
      edge('mcp', 'agent'),
      edge('sql', 'agent'),
    ])
    // The three feeders share the left rank; sort them by y and demand real vertical clearance.
    const rank = [nodes[0], tallMcp, nodes[2]].sort((a, b) => placed.get(a.id)!.y - placed.get(b.id)!.y)
    expect(bottom(rank[0], placed)).toBeLessThanOrEqual(placed.get(rank[1].id)!.y)
    expect(bottom(rank[1], placed)).toBeLessThanOrEqual(placed.get(rank[2].id)!.y)
  })

  it('an unwired capability still gets a place instead of keeping its old corner', () => {
    const stray = node('mcp')
    const placed = tidyLayout([node('agent'), stray], [])
    expect(placed.has('mcp')).toBe(true)
  })

  it('does nothing for a single node', () => {
    expect(tidyLayout([node('only')], []).size).toBe(0)
  })
})
