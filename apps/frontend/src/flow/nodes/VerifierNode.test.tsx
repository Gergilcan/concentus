import { ReactFlowProvider } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { VerifierNodeData } from '../../api/types.ts'
import { useFlowStore } from '../../state/store.ts'
import type { VerifierRFNode } from '../nodeTypes.ts'
import { VerifierNode } from './VerifierNode.tsx'
import styles from './nodes.module.scss'

function verifierData(overrides: Partial<VerifierNodeData> = {}): VerifierNodeData {
  return {
    kind: 'verifier',
    name: 'Verifier',
    model: 'claude-sonnet-5',
    systemPrompt: '',
    maxTokens: 16000,
    effort: 'high',
    ...overrides,
  }
}

// VerifierNode renders the shared card scaffold (Handle needs a <ReactFlowProvider>) plus a live
// NodeStatusBadge keyed off useFlowStore.runExecByNode, so the store is reset between tests.
function renderVerifierNode(props: Partial<NodeProps<VerifierRFNode>> = {}) {
  const base = {
    id: 'v1',
    data: verifierData(),
    selected: false,
    type: 'verifier',
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<VerifierRFNode>
  return render(
    <ReactFlowProvider>
      <VerifierNode {...base} {...props} />
    </ReactFlowProvider>,
  )
}

describe('VerifierNode', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('renders the scales icon, name as title, a "VERIFY" badge and the model', () => {
    renderVerifierNode({ data: verifierData({ name: 'Adversario' }) })
    expect(screen.getByText('⚖')).toBeInTheDocument()
    expect(screen.getByText('Adversario')).toBeInTheDocument()
    expect(screen.getByText('VERIFY')).toBeInTheDocument()
    expect(screen.getByText('claude-sonnet-5')).toBeInTheDocument()
  })

  it('has two optional second outputs — on rejected lowest, on error above — both off until asked for', () => {
    const { container } = renderVerifierNode()
    expect(container.querySelector('[data-handleid="rejected"]')).toBeNull()
    expect(container.querySelector('[data-handleid="error"]')).toBeNull()
    expect(screen.getByRole('button', { name: '+ on rejected' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ on error' })).toBeInTheDocument()

    const { container: on } = renderVerifierNode({
      data: verifierData({ rejectedOutput: true, errorOutput: true }),
    })
    const rejected = on.querySelector('[data-handleid="rejected"]') as HTMLElement
    const error = on.querySelector('[data-handleid="error"]') as HTMLElement
    expect(rejected).not.toBeNull()
    expect(error).not.toBeNull()
    expect(rejected.style.top).toBe('calc(100% - 14px)')
    expect(error.style.top).toBe('calc(100% - 30px)')
  })

  it('falls back to "Verifier" when name is empty and applies its variant class', () => {
    const { container } = renderVerifierNode({ data: verifierData({ name: '' }) })
    expect(screen.getByText('Verifier')).toBeInTheDocument()
    expect(container.firstChild).toHaveClass(styles.verifier)
  })

  it('renders a live run-status badge when the store has exec data for this node id', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'v1',
          kind: 'agent',
          label: 'Verifier',
          status: 'passed',
          inputTokens: 1,
          outputTokens: 5,
          startedAt: 0,
          endedAt: 1,
        },
      ],
      totalInputTokens: 1,
      totalOutputTokens: 5,
    })

    renderVerifierNode({ id: 'v1' })

    expect(screen.getByText(/passed/)).toBeInTheDocument()
  })
})
