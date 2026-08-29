import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * run-notifications.ts: a toast when a run ends or stops to ask — once, and never for what
 * had already happened before the poller was looking.
 */

interface Toast { title: string; body: string; urgency?: string }

const mocks = vi.hoisted(() => ({
  listRuns: vi.fn(),
  shown: [] as Array<{ title: string; body: string; urgency?: string }>,
  clicks: [] as Array<() => void>,
  failToShow: false,
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('electron', () => ({
  Notification: class {
    constructor(private readonly options: { title: string; body: string; urgency?: string }) {}
    on(event: string, handler: () => void) { if (event === 'click') mocks.clicks.push(handler) }
    show() {
      if (mocks.failToShow) throw new Error('no notification daemon')
      mocks.shown.push(this.options)
    }
  },
}))
vi.mock('../src/backend-api', () => ({ backendApi: { listRuns: mocks.listRuns } }))
vi.mock('../src/log', () => ({ log: mocks.log }))

import { resetRunNotifications, startRunNotifications, stopRunNotifications, toastFor } from '../src/run-notifications'

interface Run { id: string; status: string; flowName: string | null; error?: string | null }

const run = (id: string, status: string, extra: Partial<Run> = {}): Run => ({ id, status, flowName: `Flow ${id}`, ...extra })

const options = {
  port: vi.fn<() => number | null>(() => 8734),
  onClick: vi.fn(),
  isWindowFocused: vi.fn(() => false),
}

/** The backend's answer to the next poll, then the poll itself. */
async function poll(runs: Run[] | Error): Promise<void> {
  if (runs instanceof Error) mocks.listRuns.mockRejectedValueOnce(runs)
  else mocks.listRuns.mockResolvedValueOnce(runs)
  await vi.advanceTimersByTimeAsync(15_000)
}

const titles = () => mocks.shown.map((t) => t.title)

beforeEach(() => {
  vi.useFakeTimers()
  mocks.shown.length = 0
  mocks.clicks.length = 0
  mocks.failToShow = false
  options.port.mockReturnValue(8734)
  options.isWindowFocused.mockReturnValue(false)
  startRunNotifications(options)
})

afterEach(() => {
  stopRunNotifications()
  vi.useRealTimers()
})

describe('the first poll', () => {
  it('seeds silently, however many runs already ended — no stack of stale toasts at startup', async () => {
    await poll([run('a', 'TERMINATED'), run('b', 'ERROR'), run('c', 'AWAITING_APPROVAL')])

    expect(mocks.shown).toEqual([])
    expect(mocks.listRuns).toHaveBeenCalledWith(8734)
  })

  it('does not happen at all while there is no backend port', async () => {
    options.port.mockReturnValue(null)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(mocks.listRuns).not.toHaveBeenCalled()
  })
})

describe('later polls', () => {
  beforeEach(() => poll([run('a', 'RUNNING'), run('b', 'RUNNING'), run('c', 'RUNNING'), run('d', 'RUNNING')]))

  it('notify on the transition into TERMINATED, ERROR and AWAITING_*', async () => {
    await poll([
      run('a', 'TERMINATED'),
      run('b', 'ERROR', { error: 'boom' }),
      run('c', 'AWAITING_APPROVAL'),
      run('d', 'AWAITING_ANSWER'),
    ])

    expect(mocks.shown).toEqual([
      { title: 'Execution finished', body: 'Flow a', urgency: 'normal' },
      { title: 'Execution failed', body: 'Flow b — boom', urgency: 'critical' },
      { title: 'Approval needed', body: 'Flow c has a plan waiting for you. Nothing has changed yet.', urgency: 'critical' },
      { title: 'The agent asked you something', body: 'Flow d is waiting for your reply.', urgency: 'critical' },
    ])
  })

  it('never twice: a run that sits in the same state is not re-announced every fifteen seconds', async () => {
    await poll([run('a', 'TERMINATED'), run('c', 'AWAITING_ANSWER')])
    await poll([run('a', 'TERMINATED'), run('c', 'AWAITING_ANSWER')])
    await poll([run('a', 'TERMINATED'), run('c', 'AWAITING_ANSWER')])

    expect(titles()).toEqual(['Execution finished', 'The agent asked you something'])
  })

  it('a second question in the same run passes through RUNNING, so it IS news again', async () => {
    await poll([run('c', 'AWAITING_ANSWER')])
    await poll([run('c', 'RUNNING')])
    await poll([run('c', 'AWAITING_ANSWER')])

    expect(titles()).toEqual(['The agent asked you something', 'The agent asked you something'])
  })

  it('say nothing for RUNNING or IDLE — waiting for input is not an ending', async () => {
    await poll([run('a', 'IDLE'), run('b', 'RUNNING')])
    expect(mocks.shown).toEqual([])
  })

  it('stay quiet about endings while the window is focused — the console shows the same thing', async () => {
    options.isWindowFocused.mockReturnValue(true)

    await poll([run('a', 'TERMINATED'), run('b', 'ERROR')])

    expect(mocks.shown).toEqual([])
  })

  it('...but a run that stopped to ask interrupts even a focused window: it will wait forever otherwise', async () => {
    options.isWindowFocused.mockReturnValue(true)

    await poll([run('c', 'AWAITING_APPROVAL'), run('d', 'AWAITING_ANSWER'), run('a', 'TERMINATED')])

    expect(titles()).toEqual(['Approval needed', 'The agent asked you something'])
  })

  it('a run first seen already finished was missed while the poller was down — still worth a toast', async () => {
    await poll([run('a', 'RUNNING'), run('new', 'ERROR', { error: 'while you were away' })])

    expect(mocks.shown).toEqual([{ title: 'Execution failed', body: 'Flow new — while you were away', urgency: 'critical' }])
  })

  it('a backend mid-restart is skipped silently, and the seed survives it', async () => {
    await poll(new Error('ECONNREFUSED'))
    expect(mocks.log.warn).not.toHaveBeenCalled()

    // Not a re-seed: the RUNNING → TERMINATED transition straddling the outage is still seen.
    await poll([run('a', 'TERMINATED')])
    expect(titles()).toEqual(['Execution finished'])
  })

  it('forget runs the backend evicted, so one re-registered with the same status is news again', async () => {
    await poll([run('a', 'TERMINATED')])
    await poll([])
    await poll([run('a', 'TERMINATED')])

    expect(titles()).toEqual(['Execution finished', 'Execution finished'])
  })

  it('clicking a toast is a request to go look', async () => {
    await poll([run('a', 'TERMINATED')])

    mocks.clicks[0]()
    expect(options.onClick).toHaveBeenCalledTimes(1)
  })

  it('a machine that cannot show notifications gets a warning, not a crash', async () => {
    mocks.failToShow = true

    await poll([run('a', 'TERMINATED')])

    expect(mocks.log.warn).toHaveBeenCalledWith('Could not show a notification: no notification daemon')
  })
})

describe('after a backend restart', () => {
  it('resetRunNotifications re-seeds instead of announcing the new registry as news', async () => {
    await poll([run('a', 'RUNNING')])
    resetRunNotifications()

    await poll([run('a', 'TERMINATED'), run('b', 'ERROR')])
    expect(mocks.shown).toEqual([])

    await poll([run('a', 'TERMINATED'), run('b', 'ERROR'), run('c', 'TERMINATED')])
    expect(titles()).toEqual(['Execution finished'])
  })

  it('stopRunNotifications ends the polling', async () => {
    stopRunNotifications()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.listRuns).not.toHaveBeenCalled()
  })
})

describe('toastFor — the wording', () => {
  it('names the flow, or "A flow" when the run has none', () => {
    expect(toastFor({ status: 'AWAITING_ANSWER', flowName: null })).toEqual<Toast>({
      title: 'The agent asked you something',
      body: 'A flow is waiting for your reply.',
      urgency: 'critical',
    })
    expect(toastFor({ status: 'TERMINATED', flowName: null }).body).toBe('Flow')
  })

  it('keeps an error toast to 200 characters', () => {
    const toast = toastFor({ status: 'ERROR', flowName: 'Nightly', error: 'x'.repeat(500) })
    expect(toast.body).toHaveLength(200)
    expect(toast.body.startsWith('Nightly — xxx')).toBe(true)
  })
})
