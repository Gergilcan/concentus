import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RunDiff } from '../api/types.ts'
import { type AppNode, useFlowStore } from '../state/store.ts'
import { Inspector } from './Inspector.tsx'

// The inspector reaches for several catalogues on mount (agents, models, MCP servers); none of
// them is what these tests are about.
vi.mock('../api/client.ts', () => ({
  api: new Proxy({}, { get: () => () => Promise.resolve([]) }),
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
