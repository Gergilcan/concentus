import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FlowEvalCase, FlowEvalResult } from '../api/types.ts'
import { versionHeadline } from './flowEvaluation.ts'
import { FlowEvaluationPanel } from './FlowEvaluationPanel.tsx'

const listEvalCases = vi.fn()
const saveEvalCase = vi.fn()
const deleteEvalCase = vi.fn()
const runEvaluation = vi.fn()
const listEvalResults = vi.fn()
const getEvalResult = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listEvalCases: (id: string) => listEvalCases(id),
    saveEvalCase: (id: string, c: unknown) => saveEvalCase(id, c),
    deleteEvalCase: (id: string, caseId: string) => deleteEvalCase(id, caseId),
    runEvaluation: (id: string) => runEvaluation(id),
    listEvalResults: (id: string) => listEvalResults(id),
    getEvalResult: (id: string, rid: string) => getEvalResult(id, rid),
  },
}))

function aCase(over: Partial<FlowEvalCase> = {}): FlowEvalCase {
  return {
    id: 'c1',
    flowId: 'f1',
    name: 'Invoice total',
    input: 'sum the invoices',
    expected: '120 EUR',
    judge: 'contains',
    createdAt: 1,
    ...over,
  }
}

function aResult(over: Partial<FlowEvalResult> = {}): FlowEvalResult {
  return {
    id: 'evr_1',
    flowId: 'f1',
    flowVersion: 8,
    startedAt: Date.now(),
    finishedAt: Date.now(),
    status: 'done',
    cases: [
      { caseId: 'c1', name: 'Invoice total', runId: 'run_a', passed: true, why: 'Found "120 EUR" in the output.', output: '120 EUR' },
      { caseId: 'c2', name: 'Lists them', runId: 'run_b', passed: false, why: '"INV-7" does not appear in the output.', output: 'none' },
    ],
    passed: 1,
    total: 2,
    ...over,
  }
}

async function renderPanel(onOpenRun?: (id: string) => void) {
  const pushError = vi.fn()
  render(<FlowEvaluationPanel flowId="f1" onOpenRun={onOpenRun} pushError={pushError} />)
  await waitFor(() => expect(listEvalCases).toHaveBeenCalledWith('f1'))
  await waitFor(() => expect(listEvalResults).toHaveBeenCalledWith('f1'))
  return pushError
}

describe('FlowEvaluationPanel', () => {
  beforeEach(() => {
    listEvalCases.mockResolvedValue([])
    listEvalResults.mockResolvedValue([])
    saveEvalCase.mockResolvedValue(aCase())
    deleteEvalCase.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  // ---------------------------------------------------------------- cases

  it('lists the cases with their judge and expectation', async () => {
    listEvalCases.mockResolvedValue([aCase(), aCase({ id: 'c2', name: 'Lists them', judge: 'regex', expected: 'INV-\\d+' })])

    await renderPanel()

    const list = await screen.findByRole('list', { name: 'Evaluation cases' })
    expect(within(list).getByText('Invoice total')).toBeInTheDocument()
    expect(within(list).getByText('contains')).toBeInTheDocument()
    expect(within(list).getByText('regex')).toBeInTheDocument()
    expect(within(list).getByText('INV-\\d+')).toBeInTheDocument()
  })

  it('adds a case with the fields the form collects', async () => {
    await renderPanel()
    fireEvent.click(screen.getByRole('button', { name: '+ Add case' }))

    // Nothing to save yet: a case with no expectation can neither pass nor fail.
    expect(screen.getByRole('button', { name: 'Save case' })).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Case name'), { target: { value: 'Invoice total' } })
    fireEvent.change(screen.getByLabelText('Input'), { target: { value: 'sum the invoices' } })
    fireEvent.change(screen.getByLabelText('Judge'), { target: { value: 'exact' } })
    fireEvent.change(screen.getByLabelText('Expected'), { target: { value: '120 EUR' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save case' }))

    await waitFor(() =>
      expect(saveEvalCase).toHaveBeenCalledWith('f1', {
        name: 'Invoice total',
        input: 'sum the invoices',
        expected: '120 EUR',
        judge: 'exact',
      }),
    )
    // Reloaded from the server rather than patched locally: the server owns ids and order.
    await waitFor(() => expect(listEvalCases).toHaveBeenCalledTimes(2))
  })

  it('edits a case under its own id', async () => {
    listEvalCases.mockResolvedValue([aCase()])
    await renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    fireEvent.change(screen.getByLabelText('Expected'), { target: { value: '121 EUR' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save case' }))

    await waitFor(() =>
      expect(saveEvalCase).toHaveBeenCalledWith('f1', expect.objectContaining({ id: 'c1', expected: '121 EUR' })),
    )
  })

  it('deletes a case after asking', async () => {
    listEvalCases.mockResolvedValue([aCase()])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(deleteEvalCase).toHaveBeenCalledWith('f1', 'c1'))
  })

  // ---------------------------------------------------------------- results

  it('shows each evaluation as a score per version, with its cases on demand', async () => {
    listEvalCases.mockResolvedValue([aCase()])
    listEvalResults.mockResolvedValue([aResult()])
    const onOpenRun = vi.fn()
    await renderPanel(onOpenRun)

    const list = await screen.findByRole('list', { name: 'Evaluation results' })
    expect(within(list).getByText('v8')).toBeInTheDocument()
    expect(within(list).getByText('1/2')).toBeInTheDocument()
    // Collapsed until asked: the row is the score; the cases are the explanation.
    expect(within(list).queryByText('Lists them')).toBeNull()

    fireEvent.click(within(list).getByRole('button', { name: 'Show cases' }))

    expect(within(list).getByText('Lists them')).toBeInTheDocument()
    expect(within(list).getByText('"INV-7" does not appear in the output.')).toBeInTheDocument()
    expect(within(list).getByLabelText('failed')).toBeInTheDocument()
    // A failed case is only actionable if its run can be read.
    fireEvent.click(within(list).getAllByRole('button', { name: 'Open run' })[1])
    expect(onOpenRun).toHaveBeenCalledWith('run_b')
  })

  it('puts two versions side by side as the headline', async () => {
    listEvalCases.mockResolvedValue([aCase()])
    listEvalResults.mockResolvedValue([
      aResult({ id: 'evr_3', flowVersion: 8, passed: 2, total: 2, startedAt: 300 }),
      // An older run of v8: a retry, not a data point — the newest score of a version is the one.
      aResult({ id: 'evr_2', flowVersion: 8, passed: 0, total: 2, startedAt: 200 }),
      aResult({ id: 'evr_1', flowVersion: 7, passed: 1, total: 2, startedAt: 100 }),
    ])
    await renderPanel()

    const headline = await screen.findByLabelText('Score per version')
    expect(headline.textContent).toContain('v7')
    expect(headline.textContent).toContain('1/2')
    expect(headline.textContent).toContain('→')
    expect(headline.textContent).toContain('v8')
    expect(headline.textContent).toContain('2/2')
    // Reads oldest → newest, the direction the edit went.
    expect(headline.textContent!.indexOf('v7')).toBeLessThan(headline.textContent!.indexOf('v8'))
  })

  it('starts an evaluation and polls it until it is done', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    listEvalCases.mockResolvedValue([aCase()])
    const started = aResult({ id: 'evr_9', status: 'running', cases: [], passed: 0, total: 1, finishedAt: null })
    runEvaluation.mockResolvedValue(started)
    getEvalResult.mockResolvedValue(aResult({ id: 'evr_9', status: 'done', passed: 1, total: 1 }))
    await renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: 'Run evaluation' }))

    await waitFor(() => expect(runEvaluation).toHaveBeenCalledWith('f1'))
    // Visible at once, running — the id is what gets polled.
    expect(await screen.findByText(/judged 0 of 1/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Running…' })).toBeDisabled()

    await vi.advanceTimersByTimeAsync(3_100)

    await waitFor(() => expect(getEvalResult).toHaveBeenCalledWith('f1', 'evr_9'))
    await waitFor(() => expect(screen.queryByText(/judged 0 of 1/)).toBeNull())
    expect(screen.getByRole('button', { name: 'Run evaluation' })).toBeEnabled()
  })

  it('cannot run an evaluation with no cases', async () => {
    await renderPanel()

    expect(await screen.findByRole('button', { name: 'Run evaluation' })).toBeDisabled()
    expect(screen.getByText(/No cases yet/)).toBeInTheDocument()
  })
})

describe('versionHeadline', () => {
  it('keeps the newest finished score of each of the two latest versions, oldest first', () => {
    const picked = versionHeadline([
      aResult({ id: 'running', flowVersion: 9, status: 'running', startedAt: 500 }),
      aResult({ id: 'v8-new', flowVersion: 8, startedAt: 400 }),
      aResult({ id: 'v8-old', flowVersion: 8, startedAt: 300 }),
      aResult({ id: 'v7', flowVersion: 7, startedAt: 200 }),
      aResult({ id: 'v6', flowVersion: 6, startedAt: 100 }),
    ])

    // A running evaluation has no score yet, so it is not a version to compare against.
    expect(picked.map((r) => r.id)).toEqual(['v7', 'v8-new'])
  })
})
