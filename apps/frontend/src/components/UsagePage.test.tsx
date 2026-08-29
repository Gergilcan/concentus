import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { UsageAllowance, UsageSummary, UsageWindow } from '../api/types.ts'
import { UsagePage } from './UsagePage.tsx'
import styles from './usage.module.scss'

const usageSummaryMock = vi.fn<() => Promise<UsageSummary>>()

vi.mock('../api/client.ts', () => ({
  api: {
    usageSummary: () => usageSummaryMock(),
  },
}))

const window_ = (over: Partial<UsageWindow> = {}): UsageWindow => ({
  inputTokens: 1500,
  outputTokens: 200,
  cacheReadTokens: 2_000_000,
  cacheWriteTokens: 0,
  estimatedUsd: 1.5,
  messages: 12,
  ...over,
})

const summary = (over: Partial<UsageSummary> = {}): UsageSummary => ({
  available: true,
  windows: { last5h: window_(), today: window_({ estimatedUsd: 4 }), week: window_({ estimatedUsd: 20, messages: 90 }) },
  models: [{ model: 'claude-opus-4-8', inputTokens: 1000, outputTokens: 500, cacheReadTokens: 0, cacheWriteTokens: 250, estimatedUsd: 0.42 }],
  ...over,
})

const allowance = (over: Partial<UsageAllowance> = {}): UsageAllowance => ({
  allowanceUsd: 10,
  runsUsd: 4,
  machineUsd: 12,
  remainingUsd: 6,
  percent: 40,
  state: 'ok',
  ...over,
})

// The usage page shows what can be measured — the CLI's transcripts — and says what cannot. The
// allowance meter is the one place a run's cost is put against a limit.
describe('UsagePage', () => {
  afterEach(() => vi.clearAllMocks())

  it('says it is reading while the transcripts load, then shows the three windows', async () => {
    usageSummaryMock.mockResolvedValue(summary())
    render(<UsagePage />)
    expect(screen.getByText('Reading transcripts…')).toBeInTheDocument()

    const tile = await screen.findByTitle('The rolling window your plan meters sessions in.')
    expect(tile).toHaveTextContent('Last 5 hours')
    expect(tile).toHaveTextContent('$1.50')
    expect(tile).toHaveTextContent('1.5k in · 200 out · 2.0M cache')
    expect(tile).toHaveTextContent('12 message(s)')
    expect(screen.getByTitle('Since midnight, local time.')).toHaveTextContent('$4.00')
    expect(screen.getByTitle('The window weekly plan limits think in.')).toHaveTextContent('90 message(s)')
  })

  it('is honest about what the figure is: API-equivalent value, not a bill', async () => {
    usageSummaryMock.mockResolvedValue(summary())
    render(<UsagePage />)
    expect(await screen.findByText(/API-equivalent value, not a bill/)).toBeInTheDocument()
    expect(screen.getByTitle(/The official quota and extra-usage credit balance have no API/)).toBeInTheDocument()
  })

  it('says where the transcripts would be when there are none', async () => {
    usageSummaryMock.mockResolvedValue(summary({ available: false }))
    render(<UsagePage />)
    expect(await screen.findByText(/No Claude Code transcripts found on this machine/)).toBeInTheDocument()
    expect(screen.queryByText('Last 5 hours')).not.toBeInTheDocument()
  })

  it('shows the error instead of a page that never loads', async () => {
    usageSummaryMock.mockRejectedValue(new Error('usage endpoint missing'))
    render(<UsagePage />)
    expect(await screen.findByText('usage endpoint missing')).toBeInTheDocument()
  })

  it('without an allowance, points at Settings → Usage to set one', async () => {
    usageSummaryMock.mockResolvedValue(summary())
    render(<UsagePage />)
    expect(await screen.findByText(/Set your plan's weekly allowance for non-interactive use under Settings → Usage/)).toBeInTheDocument()
    expect(screen.queryByText(/Weekly allowance for runs/)).not.toBeInTheDocument()
  })

  it('the meter reads what the runs used, what is left, and the whole machine for scale', async () => {
    usageSummaryMock.mockResolvedValue(summary({ allowance: allowance() }))
    const { container } = render(<UsagePage />)

    expect(await screen.findByText(/Weekly allowance for runs/)).toBeInTheDocument()
    expect(screen.getByText('40%')).toBeInTheDocument()
    expect(screen.getByText('$4.00 of $10.00 — $6.00 left · whole machine: $12.00')).toBeInTheDocument()
    expect(screen.queryByText(/Set your plan's weekly allowance/)).not.toBeInTheDocument()
    const fill = container.querySelector(`.${styles.meterFill}`) as HTMLElement
    expect(fill).toHaveClass(styles.meterOk)
    expect(fill.style.width).toBe('40%')
  })

  it('turns amber when close', async () => {
    usageSummaryMock.mockResolvedValue(summary({ allowance: allowance({ state: 'warn', percent: 85, runsUsd: 8.5, remainingUsd: 1.5 }) }))
    const { container } = render(<UsagePage />)
    await screen.findByText('85%')
    expect(container.querySelector(`.${styles.meterFill}`)).toHaveClass(styles.meterWarn)
  })

  it('exhausted: says spent, caps the bar at full, and explains what runs do now', async () => {
    usageSummaryMock.mockResolvedValue(summary({ allowance: allowance({ state: 'exhausted', percent: 130, runsUsd: 13, remainingUsd: 0 }) }))
    const { container } = render(<UsagePage />)

    expect(await screen.findByText('130%')).toBeInTheDocument()
    expect(screen.getByText(/\$13\.00 of \$10\.00 — spent\. Runs on the subscription wait for the window to reset/)).toBeInTheDocument()
    const fill = container.querySelector(`.${styles.meterFill}`) as HTMLElement
    expect(fill).toHaveClass(styles.meterOver)
    expect(fill.style.width).toBe('100%')
  })

  it('draws the last seven days with today last, and the per-model table', async () => {
    usageSummaryMock.mockResolvedValue(
      summary({
        days: [
          { ...window_({ estimatedUsd: 0 }), date: '2026-08-23' },
          { ...window_({ estimatedUsd: 2 }), date: '2026-08-24' },
        ],
      }),
    )
    render(<UsagePage />)

    expect(await screen.findByText('Day by day')).toBeInTheDocument()
    // The last bar is today, by name; the earlier ones carry their weekday.
    const today = screen.getByTitle('2026-08-24 — 12 message(s)')
    expect(today).toHaveTextContent('Today')
    expect(today).toHaveTextContent('$2.00')
    const earlier = screen.getByTitle('2026-08-23 — 12 message(s)')
    expect(earlier).not.toHaveTextContent('Today')
    expect(earlier).toHaveTextContent(new Date('2026-08-23T12:00:00').toLocaleDateString(undefined, { weekday: 'short' }))

    const row = screen.getByRole('cell', { name: 'claude-opus-4-8' }).closest('tr') as HTMLElement
    expect(row).toHaveTextContent('1.0k')
    expect(row).toHaveTextContent('$0.42')
  })
})
