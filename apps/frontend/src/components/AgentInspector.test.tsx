import { render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentNodeData, LibraryAgent } from '../api/types.ts'
import { AgentInspector } from './AgentInspector.tsx'

const listAgentsMock = vi.fn<() => Promise<LibraryAgent[]>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listAgents: (...args: unknown[]) => listAgentsMock(...(args as [])),
    // The model picker probes this to flag providers with no credential configured.
    listProviders: () => Promise.resolve({ configured: [] as string[] }),
  },
}))

function coordinatorData(overrides: Partial<AgentNodeData> = {}): AgentNodeData {
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

// AgentInspector edits an agent node's data via the shared Field/SelectField/TextArea
// components and additionally offers a "load from library" shortcut backed by api.listAgents().
describe('AgentInspector', () => {
  beforeEach(() => {
    listAgentsMock.mockResolvedValue([])
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('renders the core fields seeded from `data` and forwards edits via `set`', async () => {
    const set = vi.fn()
    render(<AgentInspector data={coordinatorData({ name: 'Lead' })} set={set} />)

    expect(await screen.findByLabelText('Name')).toHaveValue('Lead')
    expect(screen.getByLabelText('Model')).toHaveValue('claude-opus-4-8')

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Renamed' } })
    expect(set).toHaveBeenCalledWith({ name: 'Renamed' })
  })

  it('only shows the "Delegate when…" routing field for subagents, not coordinators', async () => {
    render(<AgentInspector data={coordinatorData({ role: 'coordinator' })} set={vi.fn()} />)
    await screen.findByLabelText('Name')
    expect(screen.queryByLabelText(/Delegate when/)).not.toBeInTheDocument()

    render(<AgentInspector data={coordinatorData({ role: 'subagent' })} set={vi.fn()} />)
    expect(await screen.findByLabelText(/Delegate when/)).toBeInTheDocument()
  })

  it('does not show the library dropdown until listAgents resolves with entries', async () => {
    listAgentsMock.mockResolvedValue([
      { id: 'a1', name: 'Researcher', model: 'claude-opus-4-8', effort: 'high', maxTokens: 8000, systemPrompt: 'x' },
    ])
    render(<AgentInspector data={coordinatorData()} set={vi.fn()} />)

    expect(screen.queryByLabelText('Load from library')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Load from library')).toBeInTheDocument())
    expect(screen.getByText('Researcher (claude-opus-4-8)')).toBeInTheDocument()
  })

  it('applies a chosen library agent onto the node via `set`', async () => {
    const libraryAgent: LibraryAgent = {
      id: 'a1',
      name: 'Researcher',
      model: 'claude-3-5',
      effort: 'medium',
      maxTokens: 8000,
      systemPrompt: 'You research things.',
      description: 'good at research',
    }
    listAgentsMock.mockResolvedValue([libraryAgent])
    const set = vi.fn()
    render(<AgentInspector data={coordinatorData()} set={set} />)

    const select = await screen.findByLabelText('Load from library')
    fireEvent.change(select, { target: { value: 'a1' } })

    expect(set).toHaveBeenCalledWith({
      name: 'Researcher',
      model: 'claude-3-5',
      effort: 'medium',
      maxTokens: 8000,
      systemPrompt: 'You research things.',
      description: 'good at research',
    })
  })

  it('falls back to an empty library (no dropdown) when listAgents rejects', async () => {
    listAgentsMock.mockRejectedValue(new Error('network down'))
    render(<AgentInspector data={coordinatorData()} set={vi.fn()} />)

    await screen.findByLabelText('Name')
    expect(screen.queryByLabelText('Load from library')).not.toBeInTheDocument()
  })
})
