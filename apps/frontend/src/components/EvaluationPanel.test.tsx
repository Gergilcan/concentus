import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { EvalCase, EvalRun } from '../api/types.ts'
import { EvaluationPanel } from './EvaluationPanel.tsx'

const listEvals = vi.fn()
const addEval = vi.fn()
const deleteEval = vi.fn()
const runEvals = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listEvals: (id: string) => listEvals(id),
    addEval: (id: string, q: string, docs: string[]) => addEval(id, q, docs),
    deleteEval: (id: string, caseId: string) => deleteEval(id, caseId),
    runEvals: (id: string, topK: number, rerank: boolean) => runEvals(id, topK, rerank),
  },
}))

function evalCase(over: Partial<EvalCase> = {}): EvalCase {
  return {
    id: 'c1',
    question: '¿cuánto tardan los reembolsos?',
    expectedDocs: ['reembolsos.pdf'],
    createdAt: 1,
    ...over,
  }
}

function run(over: Partial<EvalRun> = {}): EvalRun {
  return {
    topK: 5,
    reranked: true,
    cases: 1,
    hitRate: 1,
    recall: 1,
    mrr: 1,
    results: [
      {
        id: 'c1',
        question: '¿cuánto tardan los reembolsos?',
        expectedDocs: ['reembolsos.pdf'],
        rank: 1,
        found: ['reembolsos.pdf'],
        returned: ['reembolsos.pdf'],
      },
    ],
    ...over,
  }
}

async function open(docs = ['reembolsos.pdf', 'comedor.txt']) {
  render(<EvaluationPanel baseId="kb1" docs={docs} />)
  fireEvent.click(screen.getByRole('button', { name: /Measure this base/ }))
  await waitFor(() => expect(listEvals).toHaveBeenCalledWith('kb1'))
}

describe('EvaluationPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listEvals.mockResolvedValue([])
    addEval.mockResolvedValue(evalCase())
    deleteEval.mockResolvedValue(undefined)
    runEvals.mockResolvedValue(run())
  })

  it('stays out of the way until asked for', () => {
    render(<EvaluationPanel baseId="kb1" docs={[]} />)

    // Collapsed by default: most visits to a base are to add a document, not to measure it.
    expect(screen.queryByText('Measuring this base')).toBeNull()
    expect(listEvals).not.toHaveBeenCalled()
  })

  it('refuses a question with no expected answer', async () => {
    await open()

    fireEvent.change(screen.getByLabelText('Question'), { target: { value: '¿y esto?' } })

    // A case with no expected document can neither pass nor fail, so it cannot be added.
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled()
  })

  it('adds a question against the documents it names', async () => {
    await open()
    fireEvent.change(screen.getByLabelText('Question'), { target: { value: '¿reembolsos?' } })
    fireEvent.change(screen.getByLabelText('Document that answers it'), {
      target: { value: 'reembolsos.pdf' },
    })

    fireEvent.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() =>
      expect(addEval).toHaveBeenCalledWith('kb1', '¿reembolsos?', ['reembolsos.pdf']),
    )
  })

  it('runs the set twice so the reranker can be judged on these documents', async () => {
    listEvals.mockResolvedValue([evalCase()])
    runEvals.mockResolvedValueOnce(run()).mockResolvedValueOnce(run({ reranked: false, mrr: 0.5 }))
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'Run the questions' }))

    await waitFor(() => expect(runEvals).toHaveBeenCalledTimes(2))
    expect(runEvals).toHaveBeenNthCalledWith(1, 'kb1', 5, true)
    expect(runEvals).toHaveBeenNthCalledWith(2, 'kb1', 5, false)
    // And it says which way the comparison went, rather than leaving two numbers side by side.
    await screen.findByText(/earning its disk space/)
  })

  it('says nothing about a reranker that is not installed', async () => {
    listEvals.mockResolvedValue([evalCase()])
    // Identical numbers both ways: there is no reranker, so there is nothing to compare — and a
    // comparison of a thing with itself would read as "the reranker does nothing".
    runEvals.mockResolvedValue(run())
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'Run the questions' }))

    await waitFor(() => expect(runEvals).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(/Without the reranker/)).toBeNull()
  })

  it('shows a miss with what came back instead', async () => {
    listEvals.mockResolvedValue([evalCase()])
    runEvals.mockResolvedValue(
      run({
        hitRate: 0,
        recall: 0,
        mrr: 0,
        results: [
          {
            id: 'c1',
            question: '¿cuánto tardan los reembolsos?',
            expectedDocs: ['reembolsos.pdf'],
            rank: 0,
            found: [],
            returned: ['comedor.txt'],
          },
        ],
      }),
    )
    await open()

    fireEvent.click(screen.getByRole('button', { name: 'Run the questions' }))

    // A miss is only actionable if you can see what won instead.
    await screen.findByText('miss')
    expect(screen.getByText(/got comedor\.txt/)).toBeTruthy()
  })

  it('drops stale numbers when the set they described changes', async () => {
    listEvals.mockResolvedValue([evalCase()])
    await open()
    fireEvent.click(screen.getByRole('button', { name: 'Run the questions' }))
    await screen.findByText('Answered ⓘ')

    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))

    // Numbers from a set that no longer exists are how a stale measurement gets quoted as current.
    await waitFor(() => expect(screen.queryByText('Answered ⓘ')).toBeNull())
  })
})
