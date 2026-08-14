import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { SkillInfo } from '../api/types.ts'
import { SkillPicker } from './SkillPicker.tsx'

const skills: SkillInfo[] = [
  { id: 'sk_pdf', name: 'pdf-forms', description: 'Fill and read PDF forms', fileCount: 3 },
  { id: 'sk_brand', name: 'brand-voice', description: 'House style for anything customer-facing', fileCount: 1 },
  { id: 'sk_review', name: 'code-review', description: 'Review a diff against the team’s rules', fileCount: 2 },
]

const openDialog = () => fireEvent.click(screen.getByText('Choose skills…'))

describe('SkillPicker', () => {
  it('says what is assigned without opening anything', () => {
    render(<SkillPicker skills={skills} selectedIds={['sk_brand']} onChange={vi.fn()} />)

    // Names, not ids — the ids are paths nobody recognises.
    expect(screen.getByText('brand-voice', { exact: false })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('picks in a dialog and commits the whole selection at once', () => {
    const onChange = vi.fn()
    render(<SkillPicker skills={skills} selectedIds={[]} onChange={onChange} />)

    openDialog()
    fireEvent.click(screen.getByText('☐ pdf-forms'))
    fireEvent.click(screen.getByText('☐ code-review'))
    // Nothing is written to the node while the dialog is open.
    expect(onChange).not.toHaveBeenCalled()

    fireEvent.click(screen.getByText('Assign these 2'))
    expect(onChange).toHaveBeenCalledWith(['sk_pdf', 'sk_review'])
  })

  it('leaves the agent as it was when the dialog is cancelled', () => {
    const onChange = vi.fn()
    render(<SkillPicker skills={skills} selectedIds={['sk_pdf']} onChange={onChange} />)

    openDialog()
    fireEvent.click(screen.getByText('☐ brand-voice'))
    fireEvent.click(screen.getByText('Cancel'))

    expect(onChange).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('searches names and descriptions, and can show only what is assigned', () => {
    render(<SkillPicker skills={skills} selectedIds={['sk_brand']} onChange={vi.fn()} />)
    openDialog()

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'diff' } })
    expect(screen.getByText('☐ code-review')).toBeInTheDocument()
    expect(screen.queryByText('☐ pdf-forms')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: '' } })
    fireEvent.click(screen.getByText('Show only the 1 assigned'))
    expect(screen.getByText('☑ brand-voice')).toBeInTheDocument()
    expect(screen.queryByText('☐ pdf-forms')).not.toBeInTheDocument()
  })

  it('selects and deselects everything the filter shows, not the whole catalog', () => {
    const onChange = vi.fn()
    render(<SkillPicker skills={skills} selectedIds={[]} onChange={onChange} />)
    openDialog()

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'pdf' } })
    fireEvent.click(screen.getByText('Select these 1'))
    fireEvent.change(screen.getByLabelText('Search'), { target: { value: '' } })
    fireEvent.click(screen.getByText('Assign these 1'))

    expect(onChange).toHaveBeenCalledWith(['sk_pdf'])
  })

  it('clears from the sidebar, which is the whole of that decision', () => {
    const onChange = vi.fn()
    render(<SkillPicker skills={skills} selectedIds={['sk_pdf', 'sk_brand']} onChange={onChange} />)

    fireEvent.click(screen.getByText('Clear (2)'))

    expect(onChange).toHaveBeenCalledWith([])
  })

  it('keeps an id it cannot name — a skill missing here may exist on the machine that runs it', () => {
    render(<SkillPicker skills={skills} selectedIds={['sk_pdf', 'sk_gone']} onChange={vi.fn()} />)

    expect(screen.getByText('2 assigned:')).toBeInTheDocument()
    expect(screen.getByText('pdf-forms')).toBeInTheDocument()
  })
})
