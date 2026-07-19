import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { useFlowStore } from '../../state/store.ts'
import { NodeStatusBadge } from './NodeStatusBadge.tsx'
import styles from './nodes.module.scss'

// NodeStatusBadge is a thin read-only view over useFlowStore.runExecByNode[id] — it renders
// nothing until a run overlay exists for that node id, then shows status (+ output tokens).
describe('NodeStatusBadge', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('renders nothing when there is no exec entry for this node id', () => {
    const { container } = render(<NodeStatusBadge id="missing-node" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the status and output-token count when an exec entry exists', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'n1',
          kind: 'agent',
          label: 'Coordinator',
          status: 'passed',
          inputTokens: 10,
          outputTokens: 1234,
          startedAt: 0,
          endedAt: 1,
        },
      ],
      totalInputTokens: 10,
      totalOutputTokens: 1234,
    })

    render(<NodeStatusBadge id="n1" />)

    expect(screen.getByText(/passed/)).toBeInTheDocument()
    expect(screen.getByText((1234).toLocaleString() + 't', { exact: false })).toBeInTheDocument()
  })

  it('omits the token count when outputTokens is 0', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'n1',
          kind: 'agent',
          label: 'Coordinator',
          status: 'running',
          inputTokens: 5,
          outputTokens: 0,
          startedAt: 0,
          endedAt: 0,
        },
      ],
      totalInputTokens: 5,
      totalOutputTokens: 0,
    })

    render(<NodeStatusBadge id="n1" />)

    const badge = screen.getByText(/running/)
    expect(badge.textContent).toBe('running')
  })

  it('applies the status-specific eb_<status> class', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'n1',
          kind: 'agent',
          label: 'Coordinator',
          status: 'failed',
          inputTokens: 1,
          outputTokens: 0,
          startedAt: 0,
          endedAt: 1,
        },
      ],
      totalInputTokens: 1,
      totalOutputTokens: 0,
    })

    render(<NodeStatusBadge id="n1" />)

    expect(screen.getByText(/failed/)).toHaveClass(styles.eb_failed)
  })
})
