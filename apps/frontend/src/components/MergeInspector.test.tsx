import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FacadeProfile, MergeNodeData } from '../api/types.ts'
import { MergeInspector } from './MergeInspector.tsx'

const listFacadeProfilesMock = vi.fn<() => Promise<FacadeProfile[]>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listFacadeProfiles: () => listFacadeProfilesMock(),
    // The model picker probes this for per-model rates.
    listModels: () => Promise.resolve({ pricing: {}, fallback: { input: 3, output: 15 }, backends: [] }),
  },
}))

const data: MergeNodeData = {
  kind: 'merge',
  name: 'Merge',
  model: 'claude-opus-4-8',
  systemPrompt: 'Run the tests first.',
  maxTokens: 16000,
  effort: 'high',
}

const profile = (over: Partial<FacadeProfile>): FacadeProfile =>
  ({ id: 'p', name: 'Profile', readOnly: false, dryRun: false, ...over }) as FacadeProfile

// The merge is the process that speaks last: it reconciles the workers and is the one step that
// may run commands. Its panel is the instructions plus, under Fine-tuning, the knobs of a worker.
describe('MergeInspector', () => {
  beforeEach(() => {
    listFacadeProfilesMock.mockResolvedValue([])
  })

  it('seeds name, model and instructions, and forwards edits', () => {
    const set = vi.fn()
    render(<MergeInspector data={data} set={set} />)

    expect(screen.getByLabelText('Name')).toHaveValue('Merge')
    expect(screen.getByLabelText('Model')).toHaveValue('claude-opus-4-8')
    expect(screen.getByLabelText(/Merge instructions/)).toHaveValue('Run the tests first.')

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Reconcile' } })
    expect(set).toHaveBeenCalledWith({ name: 'Reconcile' })
    fireEvent.change(screen.getByLabelText(/Merge instructions/), { target: { value: 'Diff everything.' } })
    expect(set).toHaveBeenCalledWith({ systemPrompt: 'Diff everything.' })
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'claude-sonnet-5' } })
    expect(set).toHaveBeenCalledWith({ model: 'claude-sonnet-5' })
  })

  it('explains in the tooltip that the merge runs after every worker and may run commands', () => {
    render(<MergeInspector data={data} set={vi.fn()} />)
    expect(screen.getByTitle(/runs after every worker, receives all their reports/)).toBeInTheDocument()
  })

  it('keeps effort, max tokens, retries and the facade profile behind Fine-tuning', () => {
    const set = vi.fn()
    render(<MergeInspector data={data} set={set} />)

    expect(screen.queryByLabelText('Effort')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Fine-tuning'))

    expect(screen.getByLabelText('Effort')).toHaveValue('high')
    fireEvent.change(screen.getByLabelText('Effort'), { target: { value: 'max' } })
    expect(set).toHaveBeenCalledWith({ effort: 'max' })

    expect(screen.getByLabelText('Max tokens')).toHaveValue(16000)
    fireEvent.change(screen.getByLabelText('Max tokens'), { target: { value: '8000' } })
    expect(set).toHaveBeenCalledWith({ maxTokens: 8000 })

    expect(screen.getByLabelText(/Facade profile/)).toHaveValue('')
  })

  it('retries: blank means the default, and a negative is clamped to zero', () => {
    const set = vi.fn()
    render(<MergeInspector data={{ ...data, retries: 2 }} set={set} />)
    fireEvent.click(screen.getByText('Fine-tuning'))

    const retries = screen.getByLabelText(/Retries after a failure/)
    expect(retries).toHaveValue(2)
    fireEvent.change(retries, { target: { value: '' } })
    expect(set).toHaveBeenLastCalledWith({ retries: undefined })
    fireEvent.change(retries, { target: { value: '-4' } })
    expect(set).toHaveBeenLastCalledWith({ retries: 0 })
    fireEvent.change(retries, { target: { value: '3' } })
    expect(set).toHaveBeenLastCalledWith({ retries: 3 })
  })

  it('lists the facade profiles with what each withholds, and forwards the choice', async () => {
    listFacadeProfilesMock.mockResolvedValue([
      profile({ id: 'ro', name: 'Readers', readOnly: true }),
      profile({ id: 'dry', name: 'Rehearsal', dryRun: true }),
      profile({ id: 'all', name: 'Everything', dryRun: false }),
    ])
    const set = vi.fn()
    render(<MergeInspector data={data} set={set} />)
    fireEvent.click(screen.getByText('Fine-tuning'))

    expect(await screen.findByRole('option', { name: 'Readers (read-only)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Rehearsal (dry-run writes)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Everything' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '— none: everything wired to this node —' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Facade profile/), { target: { value: 'ro' } })
    expect(set).toHaveBeenCalledWith({ facadeProfileId: 'ro' })
  })
})
