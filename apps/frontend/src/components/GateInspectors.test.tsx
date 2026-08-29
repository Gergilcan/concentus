import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ConditionNodeData, ForEachNodeData } from '../api/types.ts'
import { ConditionInspector, ForEachInspector } from './GateInspectors.tsx'

const condition: ConditionNodeData = { kind: 'condition', label: 'if', test: 'not_empty', value: '', caseSensitive: false }
const forEach: ForEachNodeData = { kind: 'foreach', label: 'for each', source: 'lines', limit: 25 }

// A condition is a rule measured from the answer. Its panel asks only what the chosen test
// needs: "is not empty" has nothing to compare against, so the text and case fields stay away.
describe('ConditionInspector', () => {
  it('seeds the label and the test, and explains what the test is measured against', () => {
    const set = vi.fn()
    render(<ConditionInspector data={condition} set={set} />)

    expect(screen.getByLabelText('Label')).toHaveValue('if')
    expect(screen.getByLabelText(/Run the branch when the answer/)).toHaveValue('not_empty')
    expect(screen.getByTitle(/Tested against the answer of the agent this gate is wired to/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Label'), { target: { value: 'has result' } })
    expect(set).toHaveBeenCalledWith({ label: 'has result' })
    fireEvent.change(screen.getByLabelText(/Run the branch when the answer/), { target: { value: 'contains' } })
    expect(set).toHaveBeenCalledWith({ test: 'contains' })
  })

  it('asks for nothing more while the test is "is not empty"', () => {
    render(<ConditionInspector data={condition} set={vi.fn()} />)
    expect(screen.queryByLabelText('Text')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Case sensitive')).not.toBeInTheDocument()
  })

  it('a text test asks for the text and whether case matters, and forwards both', () => {
    const set = vi.fn()
    render(<ConditionInspector data={{ ...condition, test: 'contains', value: 'DONE' }} set={set} />)

    expect(screen.getByLabelText('Text')).toHaveValue('DONE')
    fireEvent.change(screen.getByLabelText('Text'), { target: { value: 'ok' } })
    expect(set).toHaveBeenCalledWith({ value: 'ok' })

    expect(screen.getByLabelText('Case sensitive')).not.toBeChecked()
    fireEvent.click(screen.getByLabelText('Case sensitive'))
    expect(set).toHaveBeenCalledWith({ caseSensitive: true })
  })

  it('a regular-expression test names the field as such and warns that a bad pattern fails the gate, not the run', () => {
    render(<ConditionInspector data={{ ...condition, test: 'matches', value: '^\\d+$' }} set={vi.fn()} />)

    expect(screen.getByLabelText('Regular expression')).toHaveValue('^\\d+$')
    expect(screen.queryByLabelText('Text')).not.toBeInTheDocument()
    expect(screen.getByText(/A pattern that does not compile fails the gate rather than the run/)).toBeInTheDocument()
  })

  it('does not mention patterns for a plain text test', () => {
    render(<ConditionInspector data={{ ...condition, test: 'equals', value: 'x' }} set={vi.fn()} />)
    expect(screen.queryByText(/does not compile/)).not.toBeInTheDocument()
  })
})

// A for-each starts one run per item, so its ceiling is the one number that protects a machine
// from a list nobody expected to be long — it is clamped, never taken as typed.
describe('ForEachInspector', () => {
  it('seeds the label, the list shape and the ceiling, and forwards edits', () => {
    const set = vi.fn()
    render(<ForEachInspector data={forEach} set={set} />)

    expect(screen.getByLabelText('Label')).toHaveValue('for each')
    expect(screen.getByLabelText(/Read the list as/)).toHaveValue('lines')
    expect(screen.getByLabelText('At most')).toHaveValue(25)
    expect(screen.getByTitle(/JSON is the reliable shape/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Read the list as/), { target: { value: 'json' } })
    expect(set).toHaveBeenCalledWith({ source: 'json' })
    fireEvent.change(screen.getByLabelText('At most'), { target: { value: '40' } })
    expect(set).toHaveBeenCalledWith({ limit: 40 })
  })

  it('keeps the ceiling between 1 and 500, and falls back to 25 for nonsense', () => {
    const set = vi.fn()
    render(<ForEachInspector data={forEach} set={set} />)
    const limit = screen.getByLabelText('At most')

    fireEvent.change(limit, { target: { value: '5000' } })
    expect(set).toHaveBeenLastCalledWith({ limit: 500 })
    fireEvent.change(limit, { target: { value: '-3' } })
    expect(set).toHaveBeenLastCalledWith({ limit: 1 })
    fireEvent.change(limit, { target: { value: '' } })
    expect(set).toHaveBeenLastCalledWith({ limit: 25 })
  })

  it('points at the condition gate as the way to run only some items', () => {
    render(<ForEachInspector data={forEach} set={vi.fn()} />)
    expect(screen.getByText(/Each item starts its own run of the flow behind this gate/)).toBeInTheDocument()
    expect(screen.getByText('condition')).toBeInTheDocument()
  })
})
