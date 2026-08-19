import { render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useFlowsAndRuns } from './useFlowsAndRuns.ts'
import { FLOWS_POLL_INTERVAL_MS } from '../constants.ts'

const listFlows = vi.fn()
const listRuns = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listFlows: () => listFlows(),
    listRuns: () => listRuns(),
  },
}))

function Probe() {
  useFlowsAndRuns('studio', () => {})
  return null
}

/**
 * The dashboard's own idea of what exists.
 *
 * It used to ask for the flow list once, at mount, and then poll only runs — which was fine while
 * the only way to create a flow was this very screen. It stopped being fine the day the backend
 * became an MCP server: an agent writes a flow from outside, the store has it, and the window
 * shows a dashboard that quietly predates it.
 */
describe('useFlowsAndRuns', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    listFlows.mockResolvedValue([])
    listRuns.mockResolvedValue([])
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  // The first fetch happens in the mount effect, which React has already flushed by the time
  // render() returns — asserted directly rather than through waitFor, whose polling never gets
  // anywhere under fake timers and simply burns the test's whole timeout.

  it('picks up a flow that appeared while the window was open', async () => {
    render(<Probe />)
    expect(listFlows).toHaveBeenCalledTimes(1)

    // Long enough for a slower flow tick, whatever multiple of the run tick it is.
    await vi.advanceTimersByTimeAsync(FLOWS_POLL_INTERVAL_MS * 8)

    expect(listFlows.mock.calls.length).toBeGreaterThan(1)
  })

  it('asks again when the window is brought back to the front', async () => {
    // The cheap case: someone alt-tabs to the browser, signs a flow in from elsewhere, comes back.
    render(<Probe />)
    expect(listFlows).toHaveBeenCalledTimes(1)

    window.dispatchEvent(new Event('focus'))

    expect(listFlows).toHaveBeenCalledTimes(2)
  })

  it('polls runs more often than flows, because that is what actually changes second to second', async () => {
    render(<Probe />)
    expect(listRuns).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(FLOWS_POLL_INTERVAL_MS * 4)

    expect(listRuns.mock.calls.length).toBeGreaterThan(listFlows.mock.calls.length)
  })
})
