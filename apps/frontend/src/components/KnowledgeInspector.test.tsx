import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { KnowledgeDef, KnowledgeNodeData } from '../api/types.ts'
import { KnowledgeInspector } from './KnowledgeInspector.tsx'

const listKnowledgeMock = vi.fn<() => Promise<KnowledgeDef[]>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listKnowledge: () => listKnowledgeMock(),
  },
}))

const data: KnowledgeNodeData = { kind: 'knowledge', label: 'docs', baseId: '', topK: 5 }

// The knowledge node points at a base managed elsewhere; the panel's job is to list what exists,
// say where to make one when nothing does, and keep the passage count within what a prompt bears.
describe('KnowledgeInspector', () => {
  beforeEach(() => {
    listKnowledgeMock.mockResolvedValue([
      { id: 'kb1', name: 'Product manuals' },
      { id: 'kb2', name: 'Support tickets' },
    ])
  })

  it('lists the bases, seeds the label and forwards the choice', async () => {
    const set = vi.fn()
    render(<KnowledgeInspector data={data} set={set} />)

    expect(screen.getByLabelText('Label')).toHaveValue('docs')
    expect(await screen.findByRole('option', { name: 'Support tickets' })).toBeInTheDocument()
    expect(screen.getByTitle(/the passages most relevant to the run's prompt are injected/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Knowledge base/), { target: { value: 'kb2' } })
    expect(set).toHaveBeenCalledWith({ baseId: 'kb2' })
    fireEvent.change(screen.getByLabelText('Label'), { target: { value: 'manuals' } })
    expect(set).toHaveBeenCalledWith({ label: 'manuals' })
  })

  it('shows the base already chosen as selected', async () => {
    render(<KnowledgeInspector data={{ ...data, baseId: 'kb1' }} set={vi.fn()} />)
    await screen.findByRole('option', { name: 'Product manuals' })
    expect(screen.getByLabelText(/Knowledge base/)).toHaveValue('kb1')
  })

  it('says where to create a base when there is none, and once the list comes back empty', async () => {
    listKnowledgeMock.mockResolvedValue([])
    render(<KnowledgeInspector data={data} set={vi.fn()} />)

    await waitFor(() => expect(listKnowledgeMock).toHaveBeenCalled())
    expect(screen.getByText(/No bases yet — create one under/)).toBeInTheDocument()
    expect(screen.getByText('Resources → Knowledge')).toBeInTheDocument()
  })

  it('hides that hint once bases exist', async () => {
    render(<KnowledgeInspector data={data} set={vi.fn()} />)
    await screen.findByRole('option', { name: 'Product manuals' })
    expect(screen.queryByText(/No bases yet/)).not.toBeInTheDocument()
  })

  it('treats a failed listing as an empty one rather than breaking the panel', async () => {
    listKnowledgeMock.mockRejectedValue(new Error('offline'))
    render(<KnowledgeInspector data={data} set={vi.fn()} />)

    expect(await screen.findByText(/No bases yet/)).toBeInTheDocument()
    expect(screen.getByLabelText('Label')).toBeInTheDocument()
  })

  it('keeps top-K between 1 and 20 under Fine-tuning, defaulting to 5 for a blank', () => {
    const set = vi.fn()
    render(<KnowledgeInspector data={data} set={set} />)

    expect(screen.queryByLabelText(/Passages to inject/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Fine-tuning'))
    const topK = screen.getByLabelText(/Passages to inject/)
    expect(topK).toHaveValue(5)

    fireEvent.change(topK, { target: { value: '8' } })
    expect(set).toHaveBeenLastCalledWith({ topK: 8 })
    fireEvent.change(topK, { target: { value: '50' } })
    expect(set).toHaveBeenLastCalledWith({ topK: 20 })
    fireEvent.change(topK, { target: { value: '0' } })
    expect(set).toHaveBeenLastCalledWith({ topK: 5 })
  })
})
