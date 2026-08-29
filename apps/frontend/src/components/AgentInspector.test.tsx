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

/** The library's researcher, at the version a test says. */
function researcher(version: number): LibraryAgent {
  return {
    id: 'a1',
    name: 'Researcher',
    model: 'claude-3-5',
    effort: 'medium',
    maxTokens: 8000,
    systemPrompt: 'You research things.',
    description: 'good at research',
    version,
  }
}

/** A block linked to the researcher, carrying the copy it took at `stampedVersion`. */
function linkedData(stampedVersion: number, overrides: Partial<AgentNodeData> = {}): AgentNodeData {
  return coordinatorData({
    kind: 'agent',
    libraryAgentId: 'a1',
    libraryVersion: stampedVersion,
    name: 'Researcher',
    model: 'claude-3-5',
    effort: 'medium',
    maxTokens: 8000,
    systemPrompt: 'You research things.',
    description: 'good at research',
    ...overrides,
  })
}

// AgentInspector edits an agent node's data via the shared Field/SelectField/TextArea
// components, and offers the agent library two ways: a live link (the block follows the library
// agent) or a one-off copy of its fields, both backed by api.listAgents().
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

  it('does not show the library dropdowns until listAgents resolves with entries', async () => {
    listAgentsMock.mockResolvedValue([
      { id: 'a1', name: 'Researcher', model: 'claude-opus-4-8', effort: 'high', maxTokens: 8000, systemPrompt: 'x' },
    ])
    render(<AgentInspector data={coordinatorData()} set={vi.fn()} />)

    expect(screen.queryByLabelText(/Link to a library agent/)).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText(/Link to a library agent/)).toBeInTheDocument())
    expect(screen.getByLabelText(/Copy fields once/)).toBeInTheDocument()
    expect(screen.getAllByText('Researcher (claude-opus-4-8)')).toHaveLength(2)
  })

  it('"Copy fields once" applies a library agent onto the node and remembers nothing', async () => {
    listAgentsMock.mockResolvedValue([researcher(3)])
    const set = vi.fn()
    render(<AgentInspector data={coordinatorData()} set={set} />)

    const select = await screen.findByLabelText(/Copy fields once/)
    fireEvent.change(select, { target: { value: 'a1' } })

    // The old behaviour, exactly: six fields, no id, no version.
    expect(set).toHaveBeenCalledWith({
      name: 'Researcher',
      model: 'claude-3-5',
      effort: 'medium',
      maxTokens: 8000,
      systemPrompt: 'You research things.',
      description: 'good at research',
    })
  })

  it('"Link to a library agent" stamps the id and the version alongside a copy of the fields', async () => {
    listAgentsMock.mockResolvedValue([researcher(3)])
    const set = vi.fn()
    render(<AgentInspector data={coordinatorData()} set={set} />)

    const select = await screen.findByLabelText(/Link to a library agent/)
    fireEvent.change(select, { target: { value: 'a1' } })

    // The copy is for the card and this panel; the run reads the library through the id.
    expect(set).toHaveBeenCalledWith({
      libraryAgentId: 'a1',
      libraryVersion: 3,
      name: 'Researcher',
      model: 'claude-3-5',
      effort: 'medium',
      maxTokens: 8000,
      systemPrompt: 'You research things.',
      description: 'good at research',
    })
  })

  it('falls back to an empty library (no dropdowns) when listAgents rejects', async () => {
    listAgentsMock.mockRejectedValue(new Error('network down'))
    render(<AgentInspector data={coordinatorData()} set={vi.fn()} />)

    await screen.findByLabelText('Name')
    expect(screen.queryByLabelText(/Link to a library agent/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Copy fields once/)).not.toBeInTheDocument()
  })

  // ------------------------------------------------------------------ linked blocks

  it('a linked block shows the chip, freezes the definition fields and leaves per-flow ones editable', async () => {
    listAgentsMock.mockResolvedValue([researcher(3)])
    const set = vi.fn()
    render(<AgentInspector data={linkedData(3)} set={set} />)

    expect(await screen.findByText(/linked to library · v3/)).toBeInTheDocument()
    // No picker on a block that already follows an agent: the chip is the picker's answer.
    expect(screen.queryByLabelText(/Link to a library agent/)).not.toBeInTheDocument()

    // What the agent IS comes from the library, so it is shown here and not edited here.
    expect(screen.getByLabelText('Name')).toHaveAttribute('readonly')
    expect(screen.getByLabelText('Model')).toBeDisabled()
    expect(screen.getByLabelText(/Delegate when/)).toHaveAttribute('readonly')
    expect(screen.getByLabelText('System prompt')).toHaveAttribute('readonly')
    fireEvent.click(screen.getByText('Fine-tuning'))
    expect(screen.getByLabelText('Effort')).toBeDisabled()
    expect(screen.getByLabelText('Max tokens')).toHaveAttribute('readonly')

    // What it gets to use HERE is the flow's business, link or no link.
    const tools = screen.getByLabelText(/Allowed tools/)
    expect(tools).not.toHaveAttribute('readonly')
    fireEvent.change(tools, { target: { value: 'Read, Grep' } })
    expect(set).toHaveBeenCalledWith({ tools: ['Read', 'Grep'] })
  })

  it('"Unlink (keep a copy)" drops the reference and keeps the values as the block\'s own', async () => {
    listAgentsMock.mockResolvedValue([researcher(3)])
    const set = vi.fn()
    render(<AgentInspector data={linkedData(3)} set={set} />)

    fireEvent.click(await screen.findByText('Unlink (keep a copy)'))

    expect(set).toHaveBeenCalledWith({
      libraryAgentId: undefined,
      libraryVersion: undefined,
      name: 'Researcher',
      model: 'claude-3-5',
      effort: 'medium',
      maxTokens: 8000,
      systemPrompt: 'You research things.',
      description: 'good at research',
    })
  })

  it('a block stamped behind the library lists what changed, and "Take the current version" re-stamps it', async () => {
    // The library moved to v3 and rewrote the prompt; the block still carries v1's copy.
    listAgentsMock.mockResolvedValue([researcher(3)])
    const set = vi.fn()
    render(<AgentInspector data={linkedData(1, { systemPrompt: 'old prompt' })} set={set} />)

    expect(await screen.findByText(/v1 → v3/)).toBeInTheDocument()
    // One line per field that differs — and only those: the name did not change, so no line.
    // (The read-only textarea below also holds "old prompt"; the diff's struck-out copy is the
    // one this is about.)
    expect(screen.getByText('old prompt', { selector: 's' })).toBeInTheDocument()
    expect(screen.getByText(/→ You research things\./)).toBeInTheDocument()
    expect(screen.queryByText('Researcher', { selector: 's' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('Take the current version'))

    expect(set).toHaveBeenCalledWith({
      libraryVersion: 3,
      name: 'Researcher',
      model: 'claude-3-5',
      effort: 'medium',
      maxTokens: 8000,
      systemPrompt: 'You research things.',
      description: 'good at research',
    })
  })

  it('a block at the current version offers nothing to take', async () => {
    listAgentsMock.mockResolvedValue([researcher(3)])
    render(<AgentInspector data={linkedData(3)} set={vi.fn()} />)

    await screen.findByText(/linked to library · v3/)
    expect(screen.queryByText('Take the current version')).not.toBeInTheDocument()
  })

  it('says so when the linked library agent no longer exists, once the library has answered', async () => {
    listAgentsMock.mockResolvedValue([])
    render(<AgentInspector data={linkedData(2, { libraryAgentId: 'gone' })} set={vi.fn()} />)

    expect(await screen.findByText(/no longer exists \(gone\)/)).toBeInTheDocument()
    // The way out is still offered; the fields it keeps are the block's copy.
    expect(screen.getByText('Unlink (keep a copy)')).toBeInTheDocument()
  })

  it('does not call the linked agent deleted when the library could not be fetched', async () => {
    listAgentsMock.mockRejectedValue(new Error('network down'))
    render(<AgentInspector data={linkedData(2)} set={vi.fn()} />)

    await screen.findByText(/linked to library · v2/)
    await waitFor(() => expect(listAgentsMock).toHaveBeenCalled())
    expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument()
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
