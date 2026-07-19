import { ReactFlowProvider } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { AgentNodeData } from '../../api/types.ts'
import { useFlowStore } from '../../state/store.ts'
import type { AgentRFNode } from '../nodeTypes.ts'
import { AgentNode } from './AgentNode.tsx'

function agentData(overrides: Partial<AgentNodeData> = {}): AgentNodeData {
  return {
    kind: 'agent',
    name: 'Coordinator',
    role: 'coordinator',
    model: 'claude-opus-4-8',
    description: '',
    systemPrompt: '',
    maxTokens: 16000,
    effort: 'high',
    ...overrides,
  }
}

// AgentNode renders through NodeShell (which mounts a live NodeStatusBadge reading the
// run overlay from useFlowStore), so the store must be reset between tests.
function renderAgentNode(props: Partial<NodeProps<AgentRFNode>> = {}) {
  const base = {
    id: 'agent-1',
    data: agentData(),
    selected: false,
    type: 'agent',
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<AgentRFNode>
  return render(
    <ReactFlowProvider>
      <AgentNode {...base} {...props} />
    </ReactFlowProvider>,
  )
}

describe('AgentNode', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('shows the star icon and purple "coordinator" styling for a coordinator', () => {
    renderAgentNode({ data: agentData({ role: 'coordinator', name: 'Lead' }) })
    expect(screen.getByText('★')).toBeInTheDocument()
    expect(screen.getByText('Lead')).toBeInTheDocument()
    expect(screen.getByText('coordinator')).toBeInTheDocument()
  })

  it('shows the diamond icon for a subagent', () => {
    renderAgentNode({ data: agentData({ role: 'subagent', name: 'Helper' }) })
    expect(screen.getByText('◆')).toBeInTheDocument()
    expect(screen.getByText('Helper')).toBeInTheDocument()
    expect(screen.getByText('subagent')).toBeInTheDocument()
  })

  it('falls back to "Agent" when name is empty', () => {
    renderAgentNode({ data: agentData({ name: '' }) })
    expect(screen.getByText('Agent')).toBeInTheDocument()
  })

  it('shows the model and the whole system prompt, leaving the clamp to CSS', () => {
    const systemPrompt = 'x'.repeat(120)
    renderAgentNode({ data: agentData({ model: 'claude-3-5', systemPrompt }) })

    expect(screen.getByText('claude-3-5')).toBeInTheDocument()
    const snippet = screen.getByText(systemPrompt)
    expect(snippet).toBeInTheDocument()
    expect(snippet).toHaveAttribute('title', systemPrompt)
  })

  it('shows the muted "no system prompt" placeholder when systemPrompt is empty', () => {
    renderAgentNode({ data: agentData({ systemPrompt: '' }) })
    expect(screen.getByText('no system prompt')).toBeInTheDocument()
  })

  it('renders a live run-status badge when the store has exec data for this node id', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'agent-1',
          kind: 'agent',
          label: 'Coordinator',
          status: 'passed',
          inputTokens: 10,
          outputTokens: 20,
          startedAt: 0,
          endedAt: 1,
        },
      ],
      totalInputTokens: 10,
      totalOutputTokens: 20,
    })

    renderAgentNode({ id: 'agent-1' })

    expect(screen.getByText(/passed/)).toBeInTheDocument()
    expect(screen.getByText(/20t/)).toBeInTheDocument()
  })
})
