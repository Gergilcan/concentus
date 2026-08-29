import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ModelCatalog } from '../api/types.ts'
import { ModelField } from './ModelField.tsx'

const listModelsMock = vi.fn<() => Promise<ModelCatalog>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listModels: () => listModelsMock(),
  },
}))

const catalog = (over: Partial<ModelCatalog> = {}): ModelCatalog => ({
  pricing: { 'claude-opus-4-8': { input: 5, output: 25 }, 'claude-haiku-4-5': { input: 0.8, output: 4 } },
  fallback: { input: 3, output: 15 },
  backends: [],
  ...over,
})

// The model picker is a shortcut, not a whitelist: a model absent from the list is still
// typeable, and the rate shown while picking is the one the run's estimate will use.
describe('ModelField', () => {
  // Block body on purpose: `mockResolvedValue` returns the mock, and a function returned from
  // beforeEach is a teardown vitest calls after the test — which would call the mock once more.
  beforeEach(() => {
    listModelsMock.mockResolvedValue(catalog())
  })

  it('lists the Claude models with their rates and forwards a pick', async () => {
    const onChange = vi.fn()
    render(<ModelField value="claude-opus-4-8" onChange={onChange} />)

    const select = screen.getByLabelText('Model')
    expect(select).toHaveValue('claude-opus-4-8')
    expect(await screen.findByRole('option', { name: 'claude-opus-4-8 — $5 / $25 / 1M' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'claude-haiku-4-5 — $0.8 / $4 / 1M' })).toBeInTheDocument()
    // Not priced: just the id, and the run will use the fallback for it.
    expect(screen.getByRole('option', { name: 'claude-sonnet-5' })).toBeInTheDocument()

    fireEvent.change(select, { target: { value: 'claude-sonnet-5' } })
    expect(onChange).toHaveBeenCalledWith('claude-sonnet-5')
  })

  it('frames the rate as an estimate, and says which fallback applies when there is none', async () => {
    const { rerender } = render(<ModelField value="claude-opus-4-8" onChange={vi.fn()} />)
    expect(await screen.findByText(/Costs shown against a run are an estimate at/)).toBeInTheDocument()
    expect(screen.getByText('$5 / $25')).toBeInTheDocument()

    rerender(<ModelField value="claude-sonnet-5" onChange={vi.fn()} />)
    expect(screen.getByText(/No rate configured for this model, so cost is estimated at the fallback/)).toBeInTheDocument()
    expect(screen.getByText(/\$3 \/ \$15/)).toBeInTheDocument()
  })

  it('opens in custom mode for a saved id it does not know, rather than resetting it', () => {
    const onChange = vi.fn()
    render(<ModelField value="claude-opus-5-preview" onChange={onChange} />)

    expect(screen.getByLabelText('Model')).toHaveValue('__custom__')
    const id = screen.getByLabelText('Model id')
    expect(id).toHaveValue('claude-opus-5-preview')
    fireEvent.change(id, { target: { value: 'claude-opus-5' } })
    expect(onChange).toHaveBeenCalledWith('claude-opus-5')
  })

  it('keeps the id box while it is being edited, even through an empty or a listed id', () => {
    const onChange = vi.fn()
    const { rerender } = render(<ModelField value="claude-opus-5-preview" onChange={onChange} />)

    fireEvent.change(screen.getByLabelText('Model id'), { target: { value: '' } })
    expect(onChange).toHaveBeenCalledWith('')
    rerender(<ModelField value="" onChange={onChange} />)
    expect(screen.getByLabelText('Model id')).toHaveValue('')

    fireEvent.change(screen.getByLabelText('Model id'), { target: { value: 'claude-opus-4-8' } })
    rerender(<ModelField value="claude-opus-4-8" onChange={onChange} />)
    expect(screen.getByLabelText('Model id')).toHaveValue('claude-opus-4-8')
    expect(screen.getByLabelText('Model')).toHaveValue('__custom__')
  })

  it('"Custom…" reveals the id box without changing the value until something is typed', () => {
    const onChange = vi.fn()
    render(<ModelField value="claude-opus-4-8" onChange={onChange} />)

    expect(screen.queryByLabelText('Model id')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: '__custom__' } })
    expect(screen.getByLabelText('Model id')).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()

    // Picking a listed model again puts the box away.
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'claude-haiku-4-5' } })
    expect(onChange).toHaveBeenCalledWith('claude-haiku-4-5')
    expect(screen.queryByLabelText('Model id')).not.toBeInTheDocument()
  })

  it('adds the models your own server reports under "On your hardware", and says they bill nothing', async () => {
    listModelsMock.mockResolvedValue(catalog({ localModels: ['llama3:8b'] }))
    render(<ModelField value="llama3:8b" onChange={vi.fn()} />)

    expect(await screen.findByRole('option', { name: 'llama3:8b — runs locally' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'On your hardware' })).toBeInTheDocument()
    expect(screen.getByLabelText('Model')).toHaveValue('llama3:8b')
    expect(screen.getByText(/there is no per-token bill and nothing leaves it/)).toBeInTheDocument()
    expect(screen.getByText('Bash and repository nodes do not')).toBeInTheDocument()
    expect(screen.queryByText(/Costs shown against a run/)).not.toBeInTheDocument()
  })

  it('read-only shows the model without letting it change; "none" is an option only when asked for', () => {
    render(<ModelField value="claude-opus-4-8" onChange={vi.fn()} readOnly />)
    expect(screen.getByLabelText('Model')).toBeDisabled()
    expect(screen.queryByRole('option', { name: '— none —' })).not.toBeInTheDocument()

    render(<ModelField value="" onChange={vi.fn()} allowNone label="Escalation model" />)
    expect(screen.getByLabelText('Escalation model')).toHaveValue('')
    expect(screen.getByRole('option', { name: '— none —' })).toBeInTheDocument()
  })

  it('still works as a plain picker when the catalogue cannot be fetched', async () => {
    listModelsMock.mockRejectedValue(new Error('offline'))
    const onChange = vi.fn()
    render(<ModelField value="claude-opus-4-8" onChange={onChange} />)

    await waitFor(() => expect(listModelsMock).toHaveBeenCalled())
    expect(screen.getByRole('option', { name: 'claude-opus-4-8' })).toBeInTheDocument()
    expect(screen.queryByText(/estimate/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'claude-haiku-4-5' } })
    expect(onChange).toHaveBeenCalledWith('claude-haiku-4-5')
  })
})
