import { ReactFlowProvider } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { SqlNodeData } from '../../api/types.ts'
import { useFlowStore } from '../../state/store.ts'
import type { SqlRFNode } from '../nodeTypes.ts'
import { SqlNode } from './SqlNode.tsx'
import styles from './nodes.module.scss'

function sqlData(overrides: Partial<SqlNodeData> = {}): SqlNodeData {
  return {
    kind: 'sql',
    label: 'orders db',
    jdbcUrl: 'jdbc:postgresql://localhost/orders',
    username: 'app',
    passwordEnv: 'DB_PASSWORD',
    query: 'select * from orders',
    maxRows: 100,
    ...overrides,
  }
}

// SqlNode renders the shared card scaffold (Handle needs a <ReactFlowProvider>) plus a live
// NodeStatusBadge keyed off useFlowStore.runExecByNode, so the store is reset between tests.
function renderSqlNode(props: Partial<NodeProps<SqlRFNode>> = {}) {
  const base = {
    id: 'sql-1',
    data: sqlData(),
    selected: false,
    type: 'sql',
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<SqlRFNode>
  return render(
    <ReactFlowProvider>
      <SqlNode {...base} {...props} />
    </ReactFlowProvider>,
  )
}

describe('SqlNode', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('renders the database icon, label as title, and a "SQL" badge', () => {
    renderSqlNode({ data: sqlData({ label: 'Reporting DB' }) })
    expect(screen.getByText('🗄')).toBeInTheDocument()
    expect(screen.getByText('Reporting DB')).toBeInTheDocument()
    expect(screen.getByText('SQL')).toBeInTheDocument()
  })

  it('falls back to "sql" when label is empty', () => {
    renderSqlNode({ data: sqlData({ label: '' }) })
    expect(screen.getByText('sql')).toBeInTheDocument()
  })

  it('shows the query, or a "no query" placeholder when empty', () => {
    renderSqlNode({ data: sqlData({ query: 'select 1' }) })
    expect(screen.getByText('select 1')).toBeInTheDocument()

    renderSqlNode({ data: sqlData({ query: '' }) })
    expect(screen.getByText('no query')).toBeInTheDocument()
  })

  it('applies the sql variant class to the root', () => {
    const { container } = renderSqlNode()
    expect(container.firstChild).toHaveClass(styles.sql)
  })

  it('renders a live run-status badge when the store has exec data for this node id', () => {
    useFlowStore.getState().setActiveRun('run-1')
    useFlowStore.getState().setRunExec({
      nodes: [
        {
          nodeId: 'sql-1',
          kind: 'sql',
          label: 'orders db',
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

    renderSqlNode({ id: 'sql-1' })

    expect(screen.getByText(/failed/)).toBeInTheDocument()
  })
})
