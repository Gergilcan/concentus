import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { DescribeFlowModal } from './DescribeFlowModal.tsx'

const generateFlowMock = vi.fn()

vi.mock('../api/client.ts', () => ({ api: { generateFlow: (d: string) => generateFlowMock(d) } }))

const draft: BackendFlow = {
  // No id: the whole promise of this dialog is that nothing was saved.
  name: 'Overnight Sentry digest',
  mode: 'local',
  nodes: [],
  edges: [],
} as unknown as BackendFlow

afterEach(() => {
  vi.clearAllMocks()
})

function open() {
  const onGenerated = vi.fn()
  const onClose = vi.fn()
  render(<DescribeFlowModal onClose={onClose} onGenerated={onGenerated} pushError={vi.fn()} />)
  return { onGenerated, onClose }
}

describe('DescribeFlowModal', () => {
  it('will not generate from an empty description', () => {
    open()

    expect(screen.getByText('Generate')).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/Describe what you want automated/), {
      target: { value: 'summarise my inbox' },
    })
    expect(screen.getByText('Generate')).toBeEnabled()
  })

  it('hands the generated draft to the caller and closes', async () => {
    generateFlowMock.mockResolvedValue(draft)
    const { onGenerated, onClose } = open()

    fireEvent.change(screen.getByLabelText(/Describe what you want automated/), {
      target: { value: '  summarise my inbox  ' },
    })
    fireEvent.click(screen.getByText('Generate'))

    // Trimmed: leading whitespace is not part of what the user asked for.
    await waitFor(() => expect(generateFlowMock).toHaveBeenCalledWith('summarise my inbox'))
    expect(onGenerated).toHaveBeenCalledWith(draft)
    expect(onClose).toHaveBeenCalled()
  })

  it('says nothing is saved until the user saves', () => {
    open()

    expect(screen.getByText(/Nothing is saved/)).toBeInTheDocument()
  })

  it('keeps the description on screen when generation fails, with the reason', async () => {
    // The next thing to do is edit the sentence and try again, so the dialog stays open with the
    // text still in it — closing it would make the user retype what they just wrote.
    generateFlowMock.mockRejectedValue(new Error('The model did not answer with JSON.'))
    const { onGenerated, onClose } = open()

    fireEvent.change(screen.getByLabelText(/Describe what you want automated/), {
      target: { value: 'do something impossible' },
    })
    fireEvent.click(screen.getByText('Generate'))

    expect(await screen.findByText(/did not answer with JSON/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Describe what you want automated/)).toHaveValue('do something impossible')
    expect(onGenerated).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })
})
