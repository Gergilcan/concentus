import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { RecipesModal } from './RecipesModal.tsx'

const getFlowMock = vi.fn()
const saveFlowMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    getFlow: (id: string) => getFlowMock(id),
    saveFlow: (f: BackendFlow) => saveFlowMock(f),
    listCredentials: () => Promise.resolve([{ id: 'cred_1', label: 'Mail password', hint: '••1234' }]),
  },
}))

function sample(): BackendFlow {
  return {
    id: 'daily-briefing',
    name: 'Daily briefing (cron)',
    mode: 'local',
    enabled: false,
    folder: 'Samples',
    nodes: [
      { id: 'in-1', type: 'input', role: null, data: { mode: 'cron', prompt: 'placeholder', cron: '0 7 * * 1-5' } },
      { id: 'agent-1', type: 'agent', role: 'coordinator', data: { name: 'Briefing Writer' } },
    ],
    edges: [],
  } as unknown as BackendFlow
}

function open() {
  const onSaved = vi.fn()
  const onClose = vi.fn()
  render(<RecipesModal onClose={onClose} onSaved={onSaved} pushError={vi.fn()} />)
  return { onSaved, onClose }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('RecipesModal', () => {
  it('offers outcomes, phrased as what you get', () => {
    open()

    expect(screen.getByText('Send me a briefing every morning')).toBeInTheDocument()
    expect(screen.getByText('Triage my inbox and draft the replies')).toBeInTheDocument()
  })

  it('asks its questions and saves the sample with the answers filled in', async () => {
    getFlowMock.mockResolvedValue(sample())
    saveFlowMock.mockResolvedValue({ ...sample(), id: 'flow_new' })
    const { onSaved, onClose } = open()

    fireEvent.click(screen.getByText('Send me a briefing every morning'))
    expect(screen.getByText(/Question 1 of 3/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Topics'), { target: { value: 'Competitors and EU AI rules' } })
    fireEvent.click(screen.getByText('Next'))
    fireEvent.change(screen.getByLabelText('Schedule'), { target: { value: '30 6 * * *' } })
    fireEvent.click(screen.getByText('Next'))
    // Where it shouts if it fails — asked here rather than found out from the run that failed
    // at three in the morning with nobody watching. Left blank on purpose: optional means
    // optional, and the flow still has to be creatable without it.
    fireEvent.click(screen.getByText('Create the flow'))

    await waitFor(() => expect(saveFlowMock).toHaveBeenCalled())
    expect(getFlowMock).toHaveBeenCalledWith('daily-briefing')
    const saved = saveFlowMock.mock.calls[0][0] as BackendFlow
    const input = saved.nodes[0].data as Record<string, unknown>
    expect(input.prompt).toBe('Competitors and EU AI rules')
    expect(input.cron).toBe('30 6 * * *')
    // A new flow in the user's own list, paused until they say otherwise.
    expect(saved.id).toBeUndefined()
    expect(saved.enabled).toBe(false)
    expect(onSaved).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('starts the flow only when the user ticks it, and says what that means', async () => {
    getFlowMock.mockResolvedValue(sample())
    saveFlowMock.mockResolvedValue({ ...sample(), id: 'flow_new' })
    open()

    fireEvent.click(screen.getByText('Send me a briefing every morning'))
    fireEvent.click(screen.getByText('Next'))
    fireEvent.click(screen.getByText('Next'))
    expect(screen.getByText(/every run costs tokens/)).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText(/Start it now/))
    fireEvent.click(screen.getByText('Create the flow'))

    await waitFor(() => expect(saveFlowMock).toHaveBeenCalled())
    expect((saveFlowMock.mock.calls[0][0] as BackendFlow).enabled).toBe(true)
  })

  it('explains itself when the sample it builds on is gone', async () => {
    getFlowMock.mockRejectedValue(new Error('No such flow'))
    const { onClose } = open()

    fireEvent.click(screen.getByText('Send me a briefing every morning'))
    fireEvent.click(screen.getByText('Next'))
    fireEvent.click(screen.getByText('Next'))
    fireEvent.click(screen.getByText('Create the flow'))

    expect(await screen.findByText(/bundled "daily-briefing" sample/)).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('goes back to the list from the first question', () => {
    open()

    fireEvent.click(screen.getByText('Send me a briefing every morning'))
    fireEvent.click(screen.getByText('Back'))

    expect(screen.getByText('Triage my inbox and draft the replies')).toBeInTheDocument()
  })
})
