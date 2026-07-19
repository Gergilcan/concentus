import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Field, SelectField, TextArea } from './fields.tsx'

// Field/SelectField/TextArea are the shared label+input building blocks every inspector
// (AgentInspector, McpInspector, SqlInspector, ...) is built from — label association via
// `htmlFor`/`useId` and the onChange plumbing must hold for all three.
describe('Field', () => {
  it('associates the label with the input via getByLabelText and renders its value', () => {
    render(<Field label="Name" value="Coordinator" onChange={() => {}} />)
    const input = screen.getByLabelText('Name') as HTMLInputElement
    expect(input.value).toBe('Coordinator')
  })

  it('fires onChange with the new value when the input changes', () => {
    const onChange = vi.fn()
    render(<Field label="Name" value="" onChange={onChange} />)

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Renamed' } })

    expect(onChange).toHaveBeenCalledWith('Renamed')
  })

  it('respects readOnly and placeholder props', () => {
    render(<Field label="Model" value="" onChange={() => {}} readOnly placeholder="claude-opus-4-8" />)
    const input = screen.getByLabelText('Model') as HTMLInputElement
    expect(input).toHaveAttribute('readonly')
    expect(input).toHaveAttribute('placeholder', 'claude-opus-4-8')
  })

  it('does not throw when rendered/changed without an onChange handler', () => {
    render(<Field label="Name" value="fixed" />)
    expect(() => fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'x' } })).not.toThrow()
  })
})

describe('SelectField', () => {
  it('associates the label with the select and fires onChange with the new value', () => {
    const onChange = vi.fn()
    render(
      <SelectField label="Role" value="coordinator" onChange={onChange}>
        <option value="coordinator">coordinator</option>
        <option value="subagent">subagent</option>
      </SelectField>,
    )

    const select = screen.getByLabelText('Role') as HTMLSelectElement
    expect(select.value).toBe('coordinator')

    fireEvent.change(select, { target: { value: 'subagent' } })

    expect(onChange).toHaveBeenCalledWith('subagent')
  })
})

describe('TextArea', () => {
  it('associates the label with the textarea, renders its value/rows/placeholder, and fires onChange', () => {
    const onChange = vi.fn()
    render(<TextArea label="System prompt" value="" onChange={onChange} rows={6} placeholder="You are..." />)

    const textarea = screen.getByLabelText('System prompt') as HTMLTextAreaElement
    expect(textarea).toHaveAttribute('rows', '6')
    expect(textarea).toHaveAttribute('placeholder', 'You are...')

    fireEvent.change(textarea, { target: { value: 'You are a helpful agent.' } })

    expect(onChange).toHaveBeenCalledWith('You are a helpful agent.')
  })
})
