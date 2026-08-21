import { describe, expect, it } from 'vitest'
import type { Edge } from '@xyflow/react'
import { edgeKinClass, kinClass, kinship } from './kinship.ts'

const edge = (source: string, target: string): Edge => ({ id: `${source}->${target}`, source, target })

/**
 * Which boxes light up when you select one.
 *
 * <p>The point of the feature is answering two questions at a glance — what feeds this block, and
 * what does it feed — so the thing worth pinning down is that the two never blur into each other.
 * A single "connected" set would be easier and would tell you nothing about which way a change
 * travels.
 */
describe('kinship — the selected block’s neighbours', () => {
  const graph = [edge('a', 'b'), edge('b', 'c'), edge('x', 'b'), edge('b', 'y'), edge('c', 'd')]

  it('separates what feeds the block from what it feeds', () => {
    const kin = kinship(graph, 'b')

    expect([...kin.inbound]).toEqual(['a', 'x'])
    expect([...kin.outbound]).toEqual(['c', 'y'])
  })

  // One hop. 'd' is downstream of 'b' through 'c', and colouring it too would, on any real flow,
  // end up colouring almost everything — which is the same as colouring nothing.
  it('stops at the immediate neighbours', () => {
    const kin = kinship(graph, 'b')

    expect(kin.outbound.has('d')).toBe(false)
    expect(kinClass(kin, 'd')).toBeUndefined()
  })

  it('lights nothing up when nothing is selected', () => {
    const kin = kinship(graph, null)

    expect(kin.inbound.size).toBe(0)
    expect(kin.outbound.size).toBe(0)
  })

  it('leaves the selected block itself to the selection outline', () => {
    expect(kinClass(kinship(graph, 'b'), 'b')).toBeUndefined()
  })

  it('gives each direction its own class', () => {
    const kin = kinship(graph, 'b')

    expect(kinClass(kin, 'a')).toBe('kin-in')
    expect(kinClass(kin, 'y')).toBe('kin-out')
  })

  // A loop, and the diamond where one box both feeds the selection and is fed by it. Either
  // answer is defensible; what would be wrong is for it to depend on edge order.
  it('calls a block that is both directions inbound, whichever way the edges are listed', () => {
    const loop = [edge('b', 'p'), edge('p', 'b')]

    expect(kinClass(kinship(loop, 'b'), 'p')).toBe('kin-in')
    expect(kinClass(kinship([...loop].reverse(), 'b'), 'p')).toBe('kin-in')
  })

  // The wires matter as much as the boxes: on a canvas wide enough to scroll, an edge is the
  // thing you follow to find the far end.
  it('colours the wires by the direction they run', () => {
    expect(edgeKinClass(edge('a', 'b'), 'b')).toBe('kin-in')
    expect(edgeKinClass(edge('b', 'c'), 'b')).toBe('kin-out')
    expect(edgeKinClass(edge('c', 'd'), 'b')).toBeUndefined()
    expect(edgeKinClass(edge('a', 'b'), null)).toBeUndefined()
  })
})
