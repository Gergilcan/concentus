import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFlowStore } from '../state/store.ts'
import { NODE_DRAG_TYPE, Palette } from './Palette.tsx'

const kinds = () => useFlowStore.getState().nodes.map((n) => n.data.kind)

/** What a browser hands a dragstart handler, reduced to the two things the palette touches. */
function dataTransfer() {
  return { setData: vi.fn(), effectAllowed: 'none' }
}

// The palette adds blocks to the canvas, by click for keyboard users and by drag for everyone
// else. A flow has exactly one coordinator, and the palette says so by refusing a second.
describe('Palette', () => {
  beforeEach(() => useFlowStore.getState().newFlow())

  it('clicking a block adds it to the canvas and selects it', () => {
    render(<Palette />)

    fireEvent.click(screen.getByRole('button', { name: /Agent/ }))
    expect(kinds()).toEqual(['agent'])
    expect(useFlowStore.getState().selectedId).toBe(useFlowStore.getState().nodes[0].id)

    fireEvent.click(screen.getByRole('button', { name: /SQL source/ }))
    expect(kinds()).toEqual(['agent', 'sql'])
  })

  it('offers the coordinator once, and explains why it is greyed out afterwards', () => {
    render(<Palette />)
    const coordinator = screen.getByRole('button', { name: /Coordinator/ })
    expect(coordinator).toBeEnabled()
    expect(coordinator).toHaveAttribute('title', expect.stringContaining('Exactly one per flow'))
    expect(coordinator).toHaveAttribute('draggable', 'true')

    fireEvent.click(coordinator)
    expect(kinds()).toEqual(['coordinator'])

    expect(coordinator).toBeDisabled()
    expect(coordinator).toHaveAttribute('title', 'A flow has one coordinator, and this one already does.')
    // Disabled for the click, and not a drag source either: a drop is the other way to add one.
    expect(coordinator).not.toHaveAttribute('draggable')
  })

  it('greys the coordinator out when the flow being opened already has one', () => {
    useFlowStore.getState().addNode('coordinator')
    render(<Palette />)
    expect(screen.getByRole('button', { name: /Coordinator/ })).toBeDisabled()
    // Every other block is still on offer.
    expect(screen.getByRole('button', { name: /Agent/ })).toBeEnabled()
  })

  it('a drag carries the block kind under the private type, as a copy', () => {
    render(<Palette />)
    const dt = dataTransfer()

    fireEvent.dragStart(screen.getByRole('button', { name: /Repository/ }), { dataTransfer: dt })

    expect(dt.setData).toHaveBeenCalledWith(NODE_DRAG_TYPE, 'repo')
    expect(dt.effectAllowed).toBe('copy')
    // Starting a drag adds nothing: the drop does, where the hand lets go.
    expect(kinds()).toEqual([])
  })

  it('every block explains itself on hover', () => {
    render(<Palette />)
    const buttons = screen.getAllByRole('button')
    expect(buttons.length).toBeGreaterThanOrEqual(15)
    for (const b of buttons) expect(b.getAttribute('title')).toBeTruthy()

    expect(screen.getByRole('button', { name: /Condition/ })).toHaveAttribute(
      'title',
      expect.stringContaining('the branch runs only when the agent\'s answer passes the test'),
    )
    expect(screen.getByTitle(/Copy\/paste: Ctrl\/Cmd\+C/)).toHaveTextContent('Hover any button or field for help')
  })
})
