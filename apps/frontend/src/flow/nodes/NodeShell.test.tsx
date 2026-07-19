import { ReactFlowProvider } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { NodeShell } from './NodeShell.tsx'
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
