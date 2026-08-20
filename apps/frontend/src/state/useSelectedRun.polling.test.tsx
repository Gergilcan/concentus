import { render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSelectedRun } from './useSelectedRun.ts'

const getRunNodes = vi.fn()

vi.mock('../api/client.ts', async () => {
  const actual = await vi.importActual<typeof import('../api/client.ts')>('../api/client.ts')
  return {
    // The real ApiError, because what is under test is the hook telling "gone" from "went wrong",
    // and a stand-in class would make `instanceof` pass for the wrong reason.
    ApiError: actual.ApiError,
    api: { getRunNodes: (id: string) => getRunNodes(id) },
  }
})

const pushError = vi.fn()

function Probe() {
  useSelectedRun(pushError, [])
  return null
}

/**
 * A run that no longer exists.
 *
 * The selection is deliberately persisted across refreshes, which is right until the run behind it
 * is gone — deleted, or left behind by a move to another database. Before this, the poll kept
 * asking for it every second, raised an error toast every time, and there was no way out of the
 * loop from inside the app.
 */
describe('useSelectedRun — a run that is no longer there', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('concentus.selectedRun', 'run_gone')
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('drops the selection instead of asking again forever', async () => {
    const { ApiError } = await import('../api/client.ts')
    getRunNodes.mockRejectedValue(new ApiError('No such run', 404))

    render(<Probe />)

    await waitFor(() => expect(localStorage.getItem('concentus.selectedRun')).toBeNull())
    // Not an incident anybody can act on: the run is gone, the screen moves on.
    expect(pushError).not.toHaveBeenCalled()
  })

  // A database briefly unreachable is not the same as a run that never comes back, and forgetting
  // the selection over one bad request would lose the user's place for a hiccup.
  it('keeps the selection when the request merely failed', async () => {
    const { ApiError } = await import('../api/client.ts')
    getRunNodes.mockRejectedValue(new ApiError('Internal server error', 500))

    render(<Probe />)

    await waitFor(() => expect(pushError).toHaveBeenCalled())
    expect(localStorage.getItem('concentus.selectedRun')).toBe('run_gone')
  })
})
