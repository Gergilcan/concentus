import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuditPanel } from './AuditPanel.tsx'

const auditStatus = vi.fn()
const listAudit = vi.fn()
const exportAudit = vi.fn()
const runRetentionNow = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    auditStatus: () => auditStatus(),
    listAudit: (filters: unknown, before?: number, limit?: number) => listAudit(filters, before, limit),
    exportAudit: (format: string, filters: unknown) => exportAudit(format, filters),
    runRetentionNow: () => runRetentionNow(),
  },
}))

const REFUSAL =
  'Audit trail export is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together; this is one of the things an organization asks for. Write in to upgrade.'

const row = (id: number, kind: string, actor: string, label: string, detail: string | null = null) => ({
  id,
  at: Date.now() - id * 60_000,
  actorEmail: actor,
  actorRole: actor.startsWith('system') ? null : 'ADMIN',
  kind,
  subjectType: kind.split('.')[0],
  subjectId: `${kind.split('.')[0]}_${id}`,
  subjectLabel: label,
  detail,
})

/**
 * The trail as a page: rows with who, what and to which subject; filters that reach the request;
 * and the one tier gate on it — export — shown as a disabled button carrying the backend's own
 * sentence rather than a click that fails.
 */
describe('AuditPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auditStatus.mockResolvedValue({
      available: true,
      kinds: ['run.started', 'flow.saved', 'member.invited'],
      exportRefusal: REFUSAL,
      retentionDays: 90,
      retentionReason: 'Team license: ninety days.',
    })
    listAudit.mockResolvedValue({
      events: [
        row(2, 'flow.saved', 'gerard@tecnovent.com', 'Nightly digest', '{"version":4}'),
        row(1, 'run.started', 'system:cron', 'Nightly digest', '{"trigger":"cron","flowVersion":4}'),
      ],
      hasMore: false,
      nextBefore: 1,
    })
  })

  const open = async () => {
    render(<AuditPanel pushError={vi.fn()} />)
    await screen.findByText('gerard@tecnovent.com')
  }

  it('lists who did what, with the system actor named by its trigger', async () => {
    await open()

    // Inside the table: the kinds are also options of the Kind filter above it.
    const table = within(screen.getByRole('table'))
    expect(table.getByText('system:cron')).toBeInTheDocument()
    expect(table.getByText('flow.saved')).toBeInTheDocument()
    expect(table.getByText('run.started')).toBeInTheDocument()
    expect(table.getByText('version: 4')).toBeInTheDocument()
    expect(table.getByText('trigger: cron · flowVersion: 4')).toBeInTheDocument()
    expect(table.getAllByText('Nightly digest')).toHaveLength(2)
  })

  it('states the retention in force next to the trail', async () => {
    await open()

    expect(screen.getByText(/Team license: ninety days\./)).toBeInTheDocument()
    expect(screen.getByText('Apply retention now')).toBeInTheDocument()
  })

  // The button is grey AND says why: a disabled control with no sentence is a puzzle, and the
  // sentence is the backend's, the same one the API answers with.
  it('disables export below Enterprise with the refusal as the reason', async () => {
    await open()

    const csv = screen.getByText('Export CSV')
    expect(csv).toBeDisabled()
    expect(csv).toHaveAttribute('title', REFUSAL)
    expect(screen.getByText('Export JSON')).toBeDisabled()
    expect(screen.getByText(REFUSAL)).toBeInTheDocument()
  })

  it('enables export on Enterprise and downloads what the filters show', async () => {
    auditStatus.mockResolvedValue({
      available: true,
      kinds: ['run.started'],
      exportRefusal: null,
      retentionDays: null,
      retentionReason: 'Enterprise license: kept without limit.',
    })
    exportAudit.mockResolvedValue(new Blob(['id,at'], { type: 'text/csv' }))
    // jsdom has no object URLs and cannot navigate; the download is an anchor click over one.
    const createObjectURL = vi.fn(() => 'blob:audit')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await open()

    const csv = screen.getByText('Export CSV')
    expect(csv).toBeEnabled()
    fireEvent.click(csv)

    await waitFor(() => expect(exportAudit).toHaveBeenCalledWith('csv', expect.objectContaining({ actor: '' })))
    await screen.findByText('Exported.')
    expect(click).toHaveBeenCalled()
    expect(screen.queryByText('Apply retention now')).not.toBeInTheDocument()
    click.mockRestore()
  })

  it('applies the filters to the request', async () => {
    await open()

    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'member.invited' } })
    fireEvent.change(screen.getByPlaceholderText('anyone — or system:'), { target: { value: 'system:' } })
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-08-01' } })
    fireEvent.click(screen.getByText('Apply'))

    await waitFor(() =>
      expect(listAudit).toHaveBeenLastCalledWith(
        { actor: 'system:', kind: 'member.invited', from: '2026-08-01', to: '' },
        undefined,
        100,
      ),
    )
  })

  it('loads the next page from the last id of the page before', async () => {
    listAudit
      .mockResolvedValueOnce({
        events: [row(9, 'flow.saved', 'gerard@tecnovent.com', 'Page one')],
        hasMore: true,
        nextBefore: 9,
      })
      .mockResolvedValueOnce({
        events: [row(3, 'flow.saved', 'gerard@tecnovent.com', 'Page two')],
        hasMore: false,
        nextBefore: 3,
      })
    render(<AuditPanel pushError={vi.fn()} />)
    await screen.findByText('Page one')

    fireEvent.click(screen.getByText('Load more'))

    await screen.findByText('Page two')
    expect(listAudit).toHaveBeenLastCalledWith(expect.anything(), 9, 100)
    expect(screen.getByText('Page one')).toBeInTheDocument()
    expect(screen.queryByText('Load more')).not.toBeInTheDocument()
  })

  it('reports what a purge removed', async () => {
    runRetentionNow.mockResolvedValue({ days: 90, runs: 12, versions: 3, auditEvents: 40 })
    await open()

    fireEvent.click(screen.getByText('Apply retention now'))

    await screen.findByText('Purged 12 runs, 3 flow versions and 40 audit events.')
  })

  it('says so when nothing matches', async () => {
    listAudit.mockResolvedValue({ events: [], hasMore: false, nextBefore: null })
    render(<AuditPanel pushError={vi.fn()} />)

    await screen.findByText('Nothing recorded yet — or nothing matches these filters.')
  })
})
