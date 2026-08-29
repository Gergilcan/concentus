import { render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentNodeData, LibraryAgent } from '../api/types.ts'
import { AgentInspector } from './AgentInspector.tsx'

const listAgentsMock = vi.fn<() => Promise<LibraryAgent[]>>()
const listPluginsMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listAgents: (...args: unknown[]) => listAgentsMock(...(args as [])),
    // The skills multiselect loads the installed list; empty keeps it out of these tests.
    listSkills: () => Promise.resolve([]),
    // Same for the facade-profile picker on sub-agents.
    listFacadeProfiles: () => Promise.resolve([]),
    // The model picker probes this for per-model rates.
    listModels: () =>
      Promise.resolve({ pricing: {}, fallback: { input: 3, output: 15 }, backends: [] }),
    // The per-agent plugin checkboxes load the installed list; empty keeps them out of a test.
    listPlugins: () => listPluginsMock(),
  },
}))

function coordinatorData(overrides: Partial<AgentNodeData> = {}): AgentNodeData {
  return {
    kind: 'coordinator',
    name: 'Coordinator',
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
    listPluginsMock.mockResolvedValue({ plugins: [], marketplaces: [] })
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
    render(<AgentInspector data={coordinatorData({ kind: 'coordinator' })} set={vi.fn()} />)
    await screen.findByLabelText('Name')
    expect(screen.queryByLabelText(/Delegate when/)).not.toBeInTheDocument()

    render(<AgentInspector data={coordinatorData({ kind: 'agent' })} set={vi.fn()} />)
    expect(await screen.findByLabelText(/Delegate when/)).toBeInTheDocument()
  })

  it('offers the escalation model to workers only, and off by default', async () => {
    // Workers are the one path with a verifier, and a verifier rejection is the only signal that
    // says the cheap answer was actually wrong. Offering it on a coordinator would be a control
    // that quietly does nothing.
    render(<AgentInspector data={coordinatorData({ kind: 'coordinator' })} set={vi.fn()} />)
    await screen.findByLabelText('Name')
    fireEvent.click(screen.getByText('Fine-tuning'))
    expect(screen.queryByLabelText(/Escalation model/)).not.toBeInTheDocument()

    const set = vi.fn()
    render(<AgentInspector data={coordinatorData({ kind: 'agent' })} set={set} />)
    fireEvent.click(screen.getAllByText('Fine-tuning')[1])
    const field = await screen.findByLabelText(/Escalation model/)
    expect(field).toHaveValue('')

    fireEvent.change(field, { target: { value: 'claude-opus-4-8' } })
    expect(set).toHaveBeenCalledWith({ fallbackModelId: 'claude-opus-4-8' })
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

  it('picks installed plugins in a dialog and writes the whole selection at once', async () => {
    listPluginsMock.mockResolvedValue({
      plugins: [{ id: 'caveman@caveman', enabled: true }],
      marketplaces: [],
    })
    const set = vi.fn()
    render(<AgentInspector data={coordinatorData()} set={set} />)

    fireEvent.click(await screen.findByText('Choose plugins…'))
    fireEvent.click(screen.getByText('☐ caveman@caveman'))
    fireEvent.click(screen.getByText('Load these 1'))

    expect(set).toHaveBeenCalledWith({ plugins: ['caveman@caveman'] })
  })

  it('shows no plugin section at all when none are installed', async () => {
    render(<AgentInspector data={coordinatorData()} set={vi.fn()} />)

    await screen.findByLabelText('Name')
    await waitFor(() => expect(listPluginsMock).toHaveBeenCalled())
    expect(screen.queryByText(/Plugins/)).not.toBeInTheDocument()
  })
})
