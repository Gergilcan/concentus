import { ReactFlowProvider } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { McpNodeData } from '../../api/types.ts'
import { useFlowStore } from '../../state/store.ts'
import type { McpRFNode } from '../nodeTypes.ts'
import { McpNode } from './McpNode.tsx'
import styles from './nodes.module.scss'

function mcpData(overrides: Partial<McpNodeData> = {}): McpNodeData {
  return { kind: 'mcp', name: 'GitHub MCP', url: 'https://mcp.example.com', tokenEnv: 'GH_TOKEN', ...overrides }
}

// McpNode renders the shared card scaffold (Handle needs a <ReactFlowProvider>) plus a live
// NodeStatusBadge keyed off useFlowStore.runExecByNode, so the store is reset between tests.
function renderMcpNode(props: Partial<NodeProps<McpRFNode>> = {}) {
  const base = {
    id: 'mcp-1',
    data: mcpData(),
    selected: false,
    type: 'mcp',
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<McpRFNode>
  return render(
    <ReactFlowProvider>
      <McpNode {...base} {...props} />
    </ReactFlowProvider>,
  )
}

describe('McpNode', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('renders the gear icon, name as title, and an "MCP" badge', () => {
    renderMcpNode({ data: mcpData({ name: 'My MCP' }) })
    expect(screen.getByText('⚙')).toBeInTheDocument()
    expect(screen.getByText('My MCP')).toBeInTheDocument()
    expect(screen.getByText('MCP')).toBeInTheDocument()
  })

  it('falls back to "mcp" when name is empty', () => {
    renderMcpNode({ data: mcpData({ name: '' }) })
    expect(screen.getByText('mcp')).toBeInTheDocument()
  })

  it('shows the url, or a "no url" placeholder when empty', () => {
    renderMcpNode({ data: mcpData({ url: 'https://mcp.example.com' }) })
    expect(screen.getByText('https://mcp.example.com')).toBeInTheDocument()

    renderMcpNode({ data: mcpData({ url: '' }) })
    expect(screen.getByText('no url')).toBeInTheDocument()
  })

  it('applies the mcp variant class to the root', () => {
    const { container } = renderMcpNode()
    expect(container.firstChild).toHaveClass(styles.mcp)
  })

  it('renders a live run-status badge when the store has exec data for this node id', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'mcp-1',
          kind: 'mcp',
          label: 'My MCP',
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

    renderMcpNode({ id: 'mcp-1' })

    expect(screen.getByText(/passed/)).toBeInTheDocument()
  })
})
