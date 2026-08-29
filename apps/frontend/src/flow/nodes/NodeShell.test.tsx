import { ReactFlowProvider } from '@xyflow/react'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFlowStore } from '../../state/store.ts'
import { NodeShell } from './NodeShell.tsx'
import type { AltHandle } from './NodeShell.tsx'
import styles from './nodes.module.scss'

// NodeShell is the shared card scaffold (border color, header, handles) every node type
// (Agent/Mcp/Repo/Sql/Input) renders through. `<Handle>` reads React Flow's internal
// zustand store via context, so it must be rendered inside a <ReactFlowProvider>.
function renderShell(props: Partial<React.ComponentProps<typeof NodeShell>> = {}) {
  return render(
    <ReactFlowProvider>
      <NodeShell variant="agent" icon="◆" title="My Agent" badge="subagent" {...props}>
        <div>child content</div>
      </NodeShell>
    </ReactFlowProvider>,
  )
}

describe('NodeShell', () => {
  it('renders the header icon, title, and badge', () => {
    renderShell()
    expect(screen.getByText('◆')).toBeInTheDocument()
    expect(screen.getByText('My Agent')).toBeInTheDocument()
    expect(screen.getByText('subagent')).toBeInTheDocument()
  })

  it('renders children', () => {
    renderShell()
    expect(screen.getByText('child content')).toBeInTheDocument()
  })

  it('shows both handles by default', () => {
    const { container } = renderShell()
    expect(container.querySelectorAll('.react-flow__handle')).toHaveLength(2)
  })

  it('hides the target handle when showTargetHandle is false', () => {
    const { container } = renderShell({ showTargetHandle: false })
    const handles = container.querySelectorAll('.react-flow__handle')
    expect(handles).toHaveLength(1)
    expect(container.querySelector('.react-flow__handle-right')).not.toBeNull()
  })

  it('hides the source handle when showSourceHandle is false', () => {
    const { container } = renderShell({ showSourceHandle: false })
    const handles = container.querySelectorAll('.react-flow__handle')
    expect(handles).toHaveLength(1)
    expect(container.querySelector('.react-flow__handle-left')).not.toBeNull()
  })

  it('applies the variant class matching the `variant` prop', () => {
    const { container } = renderShell({ variant: 'sql' })
    expect(container.firstChild).toHaveClass(styles.sql)
  })

  it('applies the coordinator class (alongside the variant class) and badge styling when coordinator is set', () => {
    const { container } = renderShell({ coordinator: true })
    const root = container.firstChild as HTMLElement
    expect(root).toHaveClass(styles.coordinator)
    expect(root).toHaveClass(styles.agent)
    const badge = screen.getByText('subagent')
    expect(badge).toHaveClass(styles.badgeCoord)
  })

  it('does not apply the coordinator class when coordinator is false/unset', () => {
    const { container } = renderShell()
    const root = container.firstChild as HTMLElement
    expect(root).not.toHaveClass(styles.coordinator)
    const badge = screen.getByText('subagent')
    expect(badge).not.toHaveClass(styles.badgeCoord)
  })
})

/**
 * A block's second outputs. Optional ones stay off the card until the author asks for them or
 * a wire needs them — the whole point is a canvas where a dot means somebody wanted it.
 */
describe('NodeShell second outputs', () => {
  const onError = (enabled: boolean): AltHandle => ({
    id: 'error',
    label: 'on error',
    tone: 'error',
    optional: { flag: 'errorOutput', enabled },
  })
  const update = vi.fn()

  beforeEach(() => {
    useFlowStore.getState().newFlow()
    update.mockReset()
    useFlowStore.setState({ updateNodeData: update })
  })

  it('draws a non-optional output (a condition\'s else) unconditionally', () => {
    const { container } = renderShell({ id: 'n1', altHandles: [{ id: 'else', label: 'else', tone: 'else' }] })
    expect(container.querySelector('[data-handleid="else"]')).not.toBeNull()
    expect(screen.getByText('else')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('hides an optional output by default and offers a chip to turn it on', () => {
    const { container } = renderShell({ id: 'n1', altHandles: [onError(false)] })
    expect(container.querySelector('[data-handleid="error"]')).toBeNull()
    const chip = screen.getByRole('button', { name: '+ on error' })
    fireEvent.click(chip)
    expect(update).toHaveBeenCalledWith('n1', { errorOutput: true })
  })

  it('draws an optional output the author turned on, and its label puts it away again', () => {
    const { container } = renderShell({ id: 'n1', altHandles: [onError(true)] })
    expect(container.querySelector('[data-handleid="error"]')).not.toBeNull()
    expect(screen.queryByRole('button', { name: '+ on error' })).toBeNull()
    fireEvent.click(screen.getByText('on error'))
    expect(update).toHaveBeenCalledWith('n1', { errorOutput: false })
  })

  it('draws an optional output the author never turned on when a wire already leaves it', () => {
    useFlowStore.setState({ edges: [{ id: 'e1', source: 'n1', target: 'n2', sourceHandle: 'error' }] })
    const { container } = renderShell({ id: 'n1', altHandles: [onError(false)] })
    expect(container.querySelector('[data-handleid="error"]')).not.toBeNull()
    // Wired: the label is not a way to hide it — deleting the wire is.
    const label = screen.getByText('on error')
    expect(label).toHaveAttribute('title', 'Delete the wire to hide this output')
    fireEvent.click(label)
    expect(update).not.toHaveBeenCalled()
  })

  it('a wire on another block\'s error output does not show this one', () => {
    useFlowStore.setState({ edges: [{ id: 'e1', source: 'other', target: 'n2', sourceHandle: 'error' }] })
    const { container } = renderShell({ id: 'n1', altHandles: [onError(false)] })
    expect(container.querySelector('[data-handleid="error"]')).toBeNull()
  })

  it('stacks two outputs lowest-first and offers one chip per hidden one', () => {
    const rejected: AltHandle = {
      id: 'rejected',
      label: 'on rejected',
      tone: 'rejected',
      optional: { flag: 'rejectedOutput', enabled: false },
    }
    const { container } = renderShell({ id: 'v1', altHandles: [rejected, onError(true)] })
    expect(container.querySelector('[data-handleid="rejected"]')).toBeNull()
    expect(container.querySelector('[data-handleid="error"]')).not.toBeNull()
    expect(screen.getByRole('button', { name: '+ on rejected' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ on error' })).toBeNull()
  })
})
