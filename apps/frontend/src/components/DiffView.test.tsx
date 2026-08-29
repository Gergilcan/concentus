import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RunDiff } from '../api/types.ts'
import { DiffView, RunChanges } from './DiffView.tsx'

const fetchRunPatchMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    fetchRunPatch: (...args: unknown[]) => fetchRunPatchMock(...args),
  },
}))

const PATCH = [
  'diff --git a/README.md b/README.md',
  '--- a/README.md',
  '+++ b/README.md',
  '@@ -1 +1,2 @@',
  ' one',
  '+two',
  'diff --git a/old.txt b/old.txt',
  'deleted file mode 100644',
  '--- a/old.txt',
  '+++ /dev/null',
  '@@ -1,2 +0,0 @@',
  '-gone',
  '-and this',
  '',
].join('\n')

const diff = (over: Partial<RunDiff> = {}): RunDiff => ({
  nodeId: 'writer',
  label: 'Writer',
  folder: 'concentus',
  repoUrl: 'https://github.com/x/concentus',
  patch: PATCH,
  stats: { files: 2, additions: 1, deletions: 2 },
  note: null,
  takenAt: 1,
  ...over,
})

/**
 * The diff of one checkout as a person reviews it: the numbers first, then one section per file
 * with its own count, then the lines coloured by what they are. What the tests pin down is what
 * would mislead a reviewer if wrong — a miscounted file, a header line shown as a change.
 */
describe('DiffView', () => {
  beforeEach(() => {
    fetchRunPatchMock.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('heads the checkout with who changed it, the folder and the backend counts', () => {
    render(<DiffView runId="run_1" diff={diff()} />)

    const section = screen.getByRole('region', { name: 'Writer · ./concentus' })
    expect(section.textContent).toContain('Writer')
    expect(section.textContent).toContain('./concentus')
    expect(section.textContent).toContain('2 files changed')
    expect(section.textContent).toContain('+1')
    expect(section.textContent).toContain('−2')
  })

  it('renders one collapsible section per file, each with its own +/- counts and status', () => {
    render(<DiffView runId="run_1" diff={diff()} />)

    const readme = screen.getByText('README.md').closest('summary')
    expect(readme?.textContent).toContain('+1')
    expect(readme?.textContent).toContain('−0')
    const old = screen.getByText('old.txt').closest('summary')
    expect(old?.textContent).toContain('deleted')
    expect(old?.textContent).toContain('−2')
    // Two files: both start open, so the lines are on screen.
    expect(screen.getByText('+two')).toBeInTheDocument()
    expect(screen.getByText('-gone')).toBeInTheDocument()
    // Header lines are never shown as changes.
    expect(screen.queryByText('+++ b/README.md')).not.toBeInTheDocument()
  })

  it('says so when the checkout was read and nothing changed, and offers no download', () => {
    render(<DiffView runId="run_1" diff={diff({ patch: null, stats: { files: 0, additions: 0, deletions: 0 } })} />)

    expect(screen.getByText('No changes in ./concentus.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download .patch' })).not.toBeInTheDocument()
  })

  it('shows the note when the patch is not what it could be', () => {
    render(<DiffView runId="run_1" diff={diff({ note: 'The checkout directory no longer exists; this is the change as it was last read while the run was in flight.' })} />)

    expect(screen.getByText(/checkout directory no longer exists/)).toBeInTheDocument()
    // The recorded patch is still there to read.
    expect(screen.getByText('+two')).toBeInTheDocument()
  })

  it('downloads the raw patch through the endpoint, as a file named after the agent and checkout', async () => {
    fetchRunPatchMock.mockResolvedValueOnce(new Blob([PATCH], { type: 'text/x-patch' }))
    const createObjectURL = vi.fn(() => 'blob:patch')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const clicks: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicks.push(this.download)
    })

    render(<DiffView runId="run_1" diff={diff()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Download .patch' }))

    await waitFor(() => expect(clicks).toEqual(['writer--concentus.patch']))
    expect(fetchRunPatchMock).toHaveBeenCalledWith('run_1', 'writer', 'concentus')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:patch')
    click.mockRestore()
  })
})

describe('RunChanges', () => {
  it('totals every checkout of the run and lists each diff', () => {
    render(
      <RunChanges
        runId="run_1"
        diffs={[diff(), diff({ nodeId: 'merge', label: 'Merge', folder: 'concentus', stats: { files: 1, additions: 4, deletions: 0 } })]}
      />,
    )

    expect(screen.getByText(/3 files changed/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Writer · ./concentus' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Merge · ./concentus' })).toBeInTheDocument()
  })

  it('says nothing changed when the run cloned repositories and touched none', () => {
    render(<RunChanges runId="run_1" diffs={[diff({ patch: null })]} />)
    expect(screen.getByText('Nothing changed in the repositories this run touched.')).toBeInTheDocument()
  })

  it('offers a refresh when given one', () => {
    const onRefresh = vi.fn()
    render(<RunChanges runId="run_1" diffs={[]} onRefresh={onRefresh} />)

    expect(screen.getByText(/No repository changes yet/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(onRefresh).toHaveBeenCalled()
  })
})
