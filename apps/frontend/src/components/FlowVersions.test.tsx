import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow, FlowVersionInfo } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { FlowVersions } from './FlowVersions.tsx'

const listFlowVersions = vi.fn()
const getFlowVersion = vi.fn()
const restoreFlowVersion = vi.fn()
const getFlow = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listFlowVersions: (id: string) => listFlowVersions(id),
    getFlowVersion: (id: string, v: number) => getFlowVersion(id, v),
    restoreFlowVersion: (id: string, v: number) => restoreFlowVersion(id, v),
    getFlow: (id: string) => getFlow(id),
  },
}))

function version(over: Partial<FlowVersionInfo> = {}): FlowVersionInfo {
  return {
    version: 2,
    name: 'My flow',
    createdAt: Date.now(),
    author: 'gerard@example.com',
    nodeCount: 3,
    edgeCount: 2,
    nodesDelta: 1,
    edgesDelta: 0,
    renamed: false,
    ...over,
  }
}

function flow(id: string): BackendFlow {
  return { id, name: 'My flow', mode: 'managed', nodes: [], edges: [] } as unknown as BackendFlow
}

beforeEach(() => {
  vi.clearAllMocks()
  // The canvas has to be on this flow for Preview/"Back to latest" to be offered at all.
  useFlowStore.setState({ flowId: 'f1', previewVersion: null })
})

describe('FlowVersions', () => {
  it('lists revisions with their author and a diff hint against the previous one', async () => {
    listFlowVersions.mockResolvedValue([
      version({ version: 2, nodesDelta: 1, edgesDelta: -1 }),
      version({ version: 1, author: null, nodesDelta: 0, edgesDelta: 0 }),
    ])

    render(<FlowVersions flowId="f1" pushError={vi.fn()} />)

    expect(await screen.findByText('v2')).toBeInTheDocument()
    expect(screen.getByText('gerard@example.com')).toBeInTheDocument()
    expect(screen.getByText(/\+1 nodes · -1 links/)).toBeInTheDocument()
    // A revision saved before authorship existed says so rather than borrowing the current user.
    expect(screen.getByText('—')).toBeInTheDocument()
    // The newest revision is the current one; only older ones can be restored.
    expect(screen.getByText('current')).toBeInTheDocument()
    expect(screen.getAllByText('Restore')).toHaveLength(1)
  })

  it('restores an older revision and puts the result on the canvas', async () => {
    listFlowVersions.mockResolvedValue([version({ version: 2 }), version({ version: 1 })])
    restoreFlowVersion.mockResolvedValue(flow('f1'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const loadBackendFlow = vi.spyOn(useFlowStore.getState(), 'loadBackendFlow')

    render(<FlowVersions flowId="f1" pushError={vi.fn()} />)
    fireEvent.click(await screen.findByText('Restore'))

    await waitFor(() => expect(restoreFlowVersion).toHaveBeenCalledWith('f1', 1))
    expect(loadBackendFlow).toHaveBeenCalled()
  })

  it('offers "Back to latest" only while the canvas shows a previewed revision', async () => {
    listFlowVersions.mockResolvedValue([version({ version: 2 }), version({ version: 1 })])
    getFlowVersion.mockResolvedValue(flow('f1'))

    render(<FlowVersions flowId="f1" pushError={vi.fn()} />)
    await screen.findByText('v2')
    expect(screen.queryByText('Back to latest')).not.toBeInTheDocument()

    fireEvent.click(screen.getAllByText('Preview')[1]) // the older revision

    expect(await screen.findByText('Back to latest')).toBeInTheDocument()
    expect(getFlowVersion).toHaveBeenCalledWith('f1', 1)
    // Leaving the preview is loading the saved flow again, which clears the banner.
    getFlow.mockResolvedValue(flow('f1'))
    fireEvent.click(screen.getByText('Back to latest'))
    await waitFor(() => expect(screen.queryByText('Back to latest')).not.toBeInTheDocument())
  })

  it('says so plainly when a flow has no history yet', async () => {
    listFlowVersions.mockResolvedValue([])

    render(<FlowVersions flowId="f1" pushError={vi.fn()} />)

    expect(await screen.findByText(/No history yet/)).toBeInTheDocument()
  })
})
