import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RunDiff } from '../api/types.ts'
import { type AppNode, useFlowStore } from '../state/store.ts'
import { Inspector } from './Inspector.tsx'

// The inspector reaches for several catalogues on mount (agents, models, MCP servers); none of
// them is what these tests are about. The few that are read as objects get an empty shape of
// the right kind; everything else answers with an empty list.
const shaped: Record<string, unknown> = {
  listModels: { pricing: {}, fallback: { input: 3, output: 15 }, backends: [] },
  listPlugins: { plugins: [], marketplaces: [] },
  mailStatus: { state: 'unknown' },
  mailSignInDefaults: { configured: false, tenantId: '', clientId: '' },
}
vi.mock('../api/client.ts', () => ({
  api: new Proxy({}, { get: (_t, name) => () => Promise.resolve(shaped[String(name)] ?? []) }),
  webhookUrl: (flowId: string) => `http://localhost/api/webhooks/${flowId}`,
  publicRunUrl: (flowId: string) => `http://localhost/api/public/flows/${flowId}/run`,
  publicChatUrl: (flowId: string, token: string) => `http://localhost/api/public/flows/${flowId}/chat?token=${token}`,
}))

const agent: AppNode = {
  id: 'writer',
  type: 'agent',
  position: { x: 0, y: 0 },
  data: {
    kind: 'agent',
    name: 'Writer',
    model: 'claude-sonnet-5',
    systemPrompt: '',
    maxTokens: 4096,
    effort: 'medium',
  },
}

const sql: AppNode = {
  id: 'db',
  type: 'sql',
  position: { x: 0, y: 0 },
  data: { kind: 'sql', label: 'Sales', jdbcUrl: 'jdbc:postgresql://x/y', query: 'select 1' } as AppNode['data'],
}

const PATCH = 'diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1,2 @@\n one\n+two\n'

const diff = (over: Partial<RunDiff> = {}): RunDiff => ({
  nodeId: 'writer',
  label: 'Writer',
  folder: 'concentus',
  patch: PATCH,
  stats: { files: 1, additions: 1, deletions: 0 },
  note: null,
  takenAt: 1,
  ...over,
})

/**
 * The Changes tab on a block: there when this run's read of the block's checkouts found
 * something, absent otherwise. An empty tab would be a promise the run did not keep, and a tab
 * on a block that cannot clone anything would be a lie about what the block is.
 */
describe('Inspector · Changes tab', () => {
  beforeEach(() => {
    useFlowStore.setState({
      nodes: [agent, sql],
      edges: [],
      selectedId: 'writer',
      activeRunId: 'run_1',
      runDiffs: [],
    })
  })

  it('has no Changes tab while the run has no diff for this block', () => {
    render(<Inspector />)

    expect(screen.getByRole('button', { name: 'Output' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Changes' })).not.toBeInTheDocument()
  })

  it('stays absent for a checkout that was read and found unchanged', () => {
    useFlowStore.setState({ runDiffs: [diff({ patch: null, stats: { files: 0, additions: 0, deletions: 0 } })] })
    render(<Inspector />)

    expect(screen.queryByRole('button', { name: 'Changes' })).not.toBeInTheDocument()
  })

  it("ignores other blocks' diffs", () => {
    useFlowStore.setState({ runDiffs: [diff({ nodeId: 'merge', label: 'Merge' })] })
    render(<Inspector />)

    expect(screen.queryByRole('button', { name: 'Changes' })).not.toBeInTheDocument()
  })

  it('appears once the block changed a repository, and opens on its diff', () => {
    useFlowStore.setState({ runDiffs: [diff()] })
    render(<Inspector />)

    fireEvent.click(screen.getByRole('button', { name: 'Changes' }))

    expect(screen.getByRole('region', { name: 'Writer · ./concentus' })).toBeInTheDocument()
    expect(screen.getByText('README.md')).toBeInTheDocument()
    expect(screen.getByText('+two')).toBeInTheDocument()
  })

  it('shows a note-only entry too: a gone directory is something to say, not nothing', () => {
    useFlowStore.setState({ runDiffs: [diff({ patch: null, note: 'The checkout directory no longer exists, and no change was read from it before it went.' })] })
    render(<Inspector />)

    fireEvent.click(screen.getByRole('button', { name: 'Changes' }))
    expect(screen.getByText(/no change was read from it/)).toBeInTheDocument()
  })

  it('never offers Changes on a block that cannot clone a repository', () => {
    useFlowStore.setState({ selectedId: 'db', runDiffs: [diff({ nodeId: 'db' })] })
    render(<Inspector />)

    expect(screen.getByRole('button', { name: 'Output' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Changes' })).not.toBeInTheDocument()
  })

  it('lists a plan-born worker’s changes under its output', () => {
    useFlowStore.setState({
      selectedId: 'worker:w1',
      runExecByNode: {
        'worker:w1': {
          nodeId: 'worker:w1',
          kind: 'agent',
          label: 'Data worker',
          status: 'passed',
          inputTokens: 1,
          outputTokens: 1,
          startedAt: 0,
          endedAt: 0,
          output: 'done',
        },
      },
      runDiffs: [diff({ nodeId: 'worker:w1', label: 'Data worker' })],
    })
    render(<Inspector />)

    expect(screen.getByRole('heading', { name: 'Changes' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Data worker · ./concentus' })).toBeInTheDocument()
  })
})

const trigger: AppNode = {
  id: 'trigger',
  type: 'input',
  position: { x: 0, y: 0 },
  data: { kind: 'input', mode: 'manual', prompt: '', cron: '', secret: '', authParam: '' },
}

const gate: AppNode = {
  id: 'gate',
  type: 'condition',
  position: { x: 0, y: 0 },
  data: { kind: 'condition', label: 'if', test: 'not_empty', value: '', caseSensitive: false },
}

const endpoint: AppNode = {
  id: 'api',
  type: 'api',
  position: { x: 0, y: 0 },
  data: { kind: 'api', label: 'api', specUrl: '', ops: [], mode: 'endpoint' },
}

const exec = (nodeId: string, kind: string, input: string | null) => ({
  nodeId,
  kind,
  label: nodeId,
  status: 'passed' as const,
  inputTokens: 1,
  outputTokens: 1,
  startedAt: 0,
  endedAt: 0,
  input,
  output: 'done',
})

/**
 * The host: one panel per kind, and the run tabs only where a run has something to show for
 * that kind. A gate has no input or output of its own, and only agents write to the console.
 */
describe('Inspector · host', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
    useFlowStore.setState({
      nodes: [agent, sql, trigger, gate, endpoint],
      edges: [],
      selectedId: null,
      activeRunId: null,
      runExecByNode: {},
      runDiffs: [],
    })
  })

  it('with nothing selected hosts the flow: a prompt to pick a node, and Versions once the flow is saved', () => {
    render(<Inspector />)

    expect(screen.getByRole('heading', { name: 'Flow' })).toBeInTheDocument()
    expect(screen.getByText('Select a node to edit its settings.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Versions' }))
    expect(screen.getByText('Save this flow to start its version history.')).toBeInTheDocument()
  })

  it('an agent gets Properties, Input, Output and Logs, with its own panel under Properties', () => {
    useFlowStore.setState({ selectedId: 'writer' })
    render(<Inspector />)

    expect(screen.getByRole('heading', { name: 'Agent' })).toBeInTheDocument()
    for (const name of ['Properties', 'Input', 'Output', 'Logs']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(screen.getByLabelText('Name')).toHaveValue('Writer')
  })

  it('an edit in the panel lands on the node in the store', () => {
    useFlowStore.setState({ selectedId: 'writer' })
    render(<Inspector />)

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Author' } })
    expect(useFlowStore.getState().nodes.find((n) => n.id === 'writer')?.data).toMatchObject({ name: 'Author' })
  })

  it('Duplicate makes a named copy; Delete removes the block and frees the panel', () => {
    useFlowStore.setState({ selectedId: 'writer' })
    const { rerender } = render(<Inspector />)

    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    const names = useFlowStore.getState().nodes.map((n) => (n.data as { name?: string }).name)
    expect(names).toContain('Writer copy')

    useFlowStore.setState({ selectedId: 'writer' })
    rerender(<Inspector />)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(useFlowStore.getState().nodes.some((n) => n.id === 'writer')).toBe(false)
    expect(screen.getByRole('heading', { name: 'Flow' })).toBeInTheDocument()
  })

  it('the trigger has an Output but no Input; a SQL source has both but no Logs', () => {
    useFlowStore.setState({ selectedId: 'trigger' })
    const { rerender } = render(<Inspector />)
    expect(screen.getByRole('heading', { name: 'Input / trigger' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Output' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Input' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Logs' })).not.toBeInTheDocument()

    useFlowStore.setState({ selectedId: 'db' })
    rerender(<Inspector />)
    expect(screen.getByRole('heading', { name: 'SQL source' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Input' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Output' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Logs' })).not.toBeInTheDocument()
  })

  it('a gate has no run tabs at all: its properties are the whole panel', () => {
    useFlowStore.setState({ selectedId: 'gate' })
    render(<Inspector />)

    expect(screen.getByRole('heading', { name: 'Condition' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Properties' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Output' })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Run the branch when the answer/)).toBeInTheDocument()
  })

  it('names an endpoint node for what it is, apart from a spec node', () => {
    useFlowStore.setState({ selectedId: 'api' })
    const { rerender } = render(<Inspector />)
    expect(screen.getByRole('heading', { name: 'API endpoint' })).toBeInTheDocument()

    useFlowStore.getState().updateNodeData('api', { mode: 'spec' })
    rerender(<Inspector />)
    expect(screen.getByRole('heading', { name: 'API (OpenAPI)' })).toBeInTheDocument()
  })

  it('without a run, the data tabs say to pick one', () => {
    useFlowStore.setState({ selectedId: 'writer' })
    render(<Inspector />)

    fireEvent.click(screen.getByRole('button', { name: 'Output' }))
    expect(screen.getByText('Select a run below to see its data.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Logs' }))
    expect(screen.getByText("Select a run below to see this agent's output.")).toBeInTheDocument()
  })

  it('offers to run a block again only for an agent whose input the run recorded', () => {
    useFlowStore.setState({
      selectedId: 'writer',
      activeRunId: 'run_1',
      runExecByNode: { writer: exec('writer', 'agent', 'draft it'), db: exec('db', 'sql', 'select 1') },
    })
    const { rerender } = render(<Inspector />)

    fireEvent.click(screen.getByRole('button', { name: 'Input' }))
    expect(screen.getByRole('button', { name: 'Run this block again…' })).toBeInTheDocument()

    // A capability block has no instruction to run again, recorded input or not.
    useFlowStore.setState({ selectedId: 'db' })
    rerender(<Inspector />)
    expect(screen.queryByRole('button', { name: 'Run this block again…' })).not.toBeInTheDocument()

    // And an agent whose input was never recorded has nothing to reproduce.
    useFlowStore.setState({ selectedId: 'writer', runExecByNode: { writer: exec('writer', 'agent', null) } })
    rerender(<Inspector />)
    expect(screen.queryByRole('button', { name: 'Run this block again…' })).not.toBeInTheDocument()
  })

  it('falls back to Properties when the selection moves to a kind without the open tab', () => {
    useFlowStore.setState({ selectedId: 'writer' })
    const { rerender } = render(<Inspector />)
    fireEvent.click(screen.getByRole('button', { name: 'Logs' }))
    expect(screen.getByText("Select a run below to see this agent's output.")).toBeInTheDocument()

    useFlowStore.setState({ selectedId: 'db' })
    rerender(<Inspector />)
    expect(screen.getByLabelText('JDBC URL')).toHaveValue('jdbc:postgresql://x/y')
  })
})
