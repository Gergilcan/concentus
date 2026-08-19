import { renderHook, act, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { useFlowActions } from './useFlowActions.ts'

const duplicateFlow = vi.fn()
const saveFlow = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    duplicateFlow: (id: string) => duplicateFlow(id),
    saveFlow: (flow: BackendFlow) => saveFlow(flow),
  },
}))

function actions(pushError = vi.fn(), refreshFlows = vi.fn()) {
  const { result } = renderHook(() =>
    useFlowActions({
      flows: [],
      runs: [],
      refreshFlows,
      refreshRuns: vi.fn(),
      setView: vi.fn(),
      setSelectedRun: vi.fn(),
      pushError,
    }),
  )
  return { result, pushError, refreshFlows }
}

const flow = (over: Partial<BackendFlow> = {}): BackendFlow => ({
  id: 'flow_1',
  name: 'Daily briefing',
  mode: 'local',
  nodes: [],
  edges: [],
  ...over,
})

/**
 * Duplicating a flow used to be this hook re-posting the graph with its id removed, which copied
 * the parts that make a flow act: the schedule stayed enabled and the webhook secret came along.
 * The rules now live on the backend, so what is left to assert here is that the click asks for
 * them — and that a copy arriving paused is said out loud rather than discovered next morning.
 */
describe('duplicateFlow', () => {
  beforeEach(() => vi.clearAllMocks())

  it('asks the backend for the copy instead of re-posting the graph', async () => {
    duplicateFlow.mockResolvedValueOnce(flow({ id: 'flow_2', name: 'Daily briefing copy', enabled: false }))
    const { result, refreshFlows } = actions()

    await act(async () => {
      await result.current.duplicateFlow(flow())
    })

    expect(duplicateFlow).toHaveBeenCalledWith('flow_1')
    expect(saveFlow).not.toHaveBeenCalled()
    await waitFor(() => expect(refreshFlows).toHaveBeenCalled())
  })

  it('says the copy is paused, because nobody notices a flow that silently does not fire', async () => {
    duplicateFlow.mockResolvedValueOnce(flow({ id: 'flow_2', name: 'Daily briefing copy', enabled: false }))
    const pushError = vi.fn()
    const { result } = actions(pushError)

    await act(async () => {
      await result.current.duplicateFlow(flow())
    })

    expect(pushError).toHaveBeenCalledWith(expect.stringContaining('paused'))
    expect(pushError).toHaveBeenCalledWith(expect.stringContaining('Daily briefing copy'))
  })

  it('refuses an unsaved flow rather than sending a request that cannot work', async () => {
    const pushError = vi.fn()
    const { result } = actions(pushError)

    await act(async () => {
      await result.current.duplicateFlow(flow({ id: undefined }))
    })

    expect(duplicateFlow).not.toHaveBeenCalled()
    expect(pushError).toHaveBeenCalledWith(expect.stringContaining('Save this flow'))
  })
})
