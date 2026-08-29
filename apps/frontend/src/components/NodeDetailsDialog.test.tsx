import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { type AppNode, useFlowStore } from '../state/store.ts'
import { NodeDetailsDialog } from './NodeDetailsDialog.tsx'

// The inspector reaches for several catalogues on mount (agents, models, MCP servers). None of
// them is what these tests are about, and listing them one by one would make this file fail every
// time the inspector learns about another one.
vi.mock('../api/client.ts', () => ({
  api: new Proxy({}, { get: () => () => Promise.resolve([]) }),
}))

const agent: AppNode = {
  id: 'writer',
  type: 'agent',
  position: { x: 0, y: 0 },
  data: {
    kind: 'agent',
    name: 'Redactor',
    model: 'claude-sonnet-5',
    systemPrompt: 'Escribe el resumen.',
    maxTokens: 4096,
    effort: 'medium',
  },
}

/**
 * The block's properties opened as a dialog, by double-clicking it on the canvas.
 *
 * <p>It exists for one reason: a system prompt is a paragraph, and the side panel it used to be
 * edited in is a 300px column. What the dialog must not become is a second editor with its own
 * copy of every field — so what these tests actually pin down is that it shows the SAME inspector,
 * for the selected block, and that it cannot outlive the thing it is showing.
 */
describe('NodeDetailsDialog', () => {
  beforeEach(() => {
    useFlowStore.setState({ nodes: [agent], edges: [], selectedId: 'writer', detailsOpen: true })
  })

  it('shows the selected block, titled by its own name', () => {
    render(<NodeDetailsDialog />)

    expect(screen.getByRole('dialog', { name: 'Redactor' })).toBeInTheDocument()
    // The inspector itself, not a copy of it: its fields are the ones on screen.
    expect(screen.getByLabelText(/system prompt/i)).toHaveValue('Escribe el resumen.')
  })

  // Editing here is editing the flow — the dialog is a window onto the same store, not a form
  // with a Save button whose absence would quietly lose a rewritten prompt.
  it('writes edits straight to the flow', () => {
    render(<NodeDetailsDialog />)

    fireEvent.change(screen.getByLabelText(/system prompt/i), {
      target: { value: 'Escribe el resumen en catalán.' },
    })

    const data = useFlowStore.getState().nodes.find((n) => n.id === 'writer')?.data
    expect(data && 'systemPrompt' in data ? data.systemPrompt : null)
      .toBe('Escribe el resumen en catalán.')
  })

  // Always the same height, whatever the block is. Sized to content, the dialog changed height
  // with every node kind, so the field you were aiming at moved between two visits to the same
  // screen. jsdom computes no vh, so what is checked here is that the box wears the class that
  // carries it — the 90% itself was measured in a real browser.
  it('asks for the fixed-height frame rather than sizing to its contents', () => {
    render(<NodeDetailsDialog />)

    expect(screen.getByRole('dialog').className).toMatch(/modalTall/)
  })

  it('stays shut until something opens it', () => {
    useFlowStore.setState({ detailsOpen: false })
    render(<NodeDetailsDialog />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  // Deleting the block from inside the dialog clears the selection, and a dialog left hanging
  // over a node that no longer exists would be showing an empty inspector titled after a ghost.
  it('closes itself when the block it was showing is gone', () => {
    useFlowStore.setState({ nodes: [], selectedId: null })
    render(<NodeDetailsDialog />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('closes on the dialog’s own control', () => {
    render(<NodeDetailsDialog />)

    fireEvent.click(screen.getByRole('button', { name: /close/i }))

    expect(useFlowStore.getState().detailsOpen).toBe(false)
  })

  // A box the coordinator's plan created during a run. It is on the canvas but was never drawn,
  // so it is not in `nodes` — and with the side panel gone, this dialog is the only way to read
  // what it produced.
  it('opens a plan-born worker, which no stored node backs', () => {
    useFlowStore.setState({
      nodes: [],
      selectedId: 'worker:3',
      detailsOpen: true,
      runExecByNode: {
        'worker:3': { nodeId: 'worker:3', label: 'Backend worker', status: 'COMPLETED' },
      } as never,
    })

    render(<NodeDetailsDialog />)

    expect(screen.getByRole('dialog', { name: 'Backend worker' })).toBeInTheDocument()
  })
})
