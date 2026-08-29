import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { VerifierNodeData } from '../api/types.ts'
import { VerifierInspector } from './VerifierInspector.tsx'

vi.mock('../api/client.ts', () => ({
  api: {
    // The model picker probes this for per-model rates.
    listModels: () => Promise.resolve({ pricing: {}, fallback: { input: 3, output: 15 }, backends: [] }),
  },
}))

const data: VerifierNodeData = {
  kind: 'verifier',
  name: 'Verifier',
  model: 'claude-opus-4-8',
  systemPrompt: 'Reject unproven claims.',
  maxTokens: 16000,
  effort: 'high',
}

// The verifier's job is the workers' inverse — find why each output should be rejected — and the
// panel says so where the criteria are typed, because a reviewer who thinks it is a summariser
// writes the wrong instructions.
describe('VerifierInspector', () => {
  it('seeds name, model and rejection criteria, and forwards edits', () => {
    const set = vi.fn()
    render(<VerifierInspector data={data} set={set} />)

    expect(screen.getByLabelText('Name')).toHaveValue('Verifier')
    expect(screen.getByLabelText('Model')).toHaveValue('claude-opus-4-8')
    expect(screen.getByLabelText(/Rejection criteria/)).toHaveValue('Reject unproven claims.')

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Judge' } })
    expect(set).toHaveBeenCalledWith({ name: 'Judge' })
    fireEvent.change(screen.getByLabelText(/Rejection criteria/), { target: { value: 'Reject numbers without a source.' } })
    expect(set).toHaveBeenCalledWith({ systemPrompt: 'Reject numbers without a source.' })
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'claude-haiku-4-5' } })
    expect(set).toHaveBeenCalledWith({ model: 'claude-haiku-4-5' })
  })

  it('tells the author the verifier runs before the merge and that a rejection is real', () => {
    render(<VerifierInspector data={data} set={vi.fn()} />)
    const tip = screen.getByTitle(/runs after every worker and BEFORE the merge/)
    expect(tip).toHaveAttribute('title', expect.stringContaining('the kill is real'))
  })

  it('keeps effort, max tokens and retries behind Fine-tuning', () => {
    const set = vi.fn()
    render(<VerifierInspector data={data} set={set} />)

    expect(screen.queryByLabelText('Effort')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Fine-tuning'))

    expect(screen.getByLabelText('Effort')).toHaveValue('high')
    fireEvent.change(screen.getByLabelText('Effort'), { target: { value: 'low' } })
    expect(set).toHaveBeenCalledWith({ effort: 'low' })

    expect(screen.getByLabelText('Max tokens')).toHaveValue(16000)
    fireEvent.change(screen.getByLabelText('Max tokens'), { target: { value: '4000' } })
    expect(set).toHaveBeenCalledWith({ maxTokens: 4000 })
  })

  it('retries: shown blank when unset', () => {
    render(<VerifierInspector data={data} set={vi.fn()} />)
    fireEvent.click(screen.getByText('Fine-tuning'))
    expect(screen.getByLabelText(/Retries after a failure/)).toHaveValue(null)
  })

  it('retries: blank means the default, negatives clamp to zero', () => {
    const set = vi.fn()
    render(<VerifierInspector data={{ ...data, retries: 1 }} set={set} />)
    fireEvent.click(screen.getByText('Fine-tuning'))

    const retries = screen.getByLabelText(/Retries after a failure/)
    expect(retries).toHaveValue(1)
    fireEvent.change(retries, { target: { value: '' } })
    expect(set).toHaveBeenLastCalledWith({ retries: undefined })
    fireEvent.change(retries, { target: { value: '-1' } })
    expect(set).toHaveBeenLastCalledWith({ retries: 0 })
    fireEvent.change(retries, { target: { value: '2' } })
    expect(set).toHaveBeenLastCalledWith({ retries: 2 })
  })
})
