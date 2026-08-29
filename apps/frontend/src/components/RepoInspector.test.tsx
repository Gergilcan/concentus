import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RemoteRepo, RemoteRepoList, RepoNodeData } from '../api/types.ts'
import { RepoInspector } from './RepoInspector.tsx'

const listGroupReposMock = vi.fn<(...args: unknown[]) => Promise<RemoteRepoList>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listGroupRepos: (...args: unknown[]) => listGroupReposMock(...args),
    // The credential picker loads the stored list; empty keeps it out of these tests.
    listCredentials: () => Promise.resolve([]),
  },
}))

const base: RepoNodeData = { kind: 'repo', provider: 'github', url: '', credentialId: '', mountPath: '', branch: '' }

const repo = (fullName: string, over: Partial<RemoteRepo> = {}): RemoteRepo => ({
  name: fullName.split('/')[1],
  fullName,
  cloneUrl: `https://github.com/${fullName}.git`,
  defaultBranch: 'main',
  archived: false,
  description: null,
  ...over,
})

// A repository node is either one repository or a whole organization/group, and which one is
// derived from what it holds: a node with a group IS a group node. Picking from the browser
// fills the branch too, because a repo whose default is `develop` cloned at `main` fails silently.
describe('RepoInspector', () => {
  afterEach(() => vi.clearAllMocks())

  it('starts as one repository: a URL, an organization to browse, and a listing button that waits for a name', () => {
    const set = vi.fn()
    render(<RepoInspector data={base} set={set} />)

    expect(screen.getByLabelText('Scope')).toHaveValue('repo')
    expect(screen.getByRole('option', { name: 'A whole GitHub organization' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('URL'), { target: { value: 'https://github.com/acme/app' } })
    expect(set).toHaveBeenCalledWith({ url: 'https://github.com/acme/app' })

    expect(screen.getByText('Browse a organization')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'List repositories' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Organization'), { target: { value: 'acme' } })
    expect(set).toHaveBeenCalledWith({ group: 'acme' })
  })

  it('switching scope clears the other side so a node is never half of each', () => {
    const set = vi.fn()
    render(<RepoInspector data={{ ...base, url: 'https://github.com/acme/app' }} set={set} />)

    fireEvent.change(screen.getByLabelText('Scope'), { target: { value: 'group' } })
    expect(set).toHaveBeenCalledWith({ url: '' })
    expect(screen.queryByLabelText('URL')).not.toBeInTheDocument()
    expect(screen.getByText('All repositories')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Scope'), { target: { value: 'repo' } })
    expect(set).toHaveBeenCalledWith({ group: '', only: [] })
    expect(screen.getByLabelText('URL')).toBeInTheDocument()
  })

  it('speaks GitLab when the provider is GitLab: groups, paths and subgroups', () => {
    render(<RepoInspector data={{ ...base, provider: 'gitlab' }} set={vi.fn()} />)

    expect(screen.getByRole('option', { name: 'A whole GitLab group' })).toBeInTheDocument()
    expect(screen.getByText('Browse a group')).toBeInTheDocument()
    expect(screen.getByLabelText('Group path')).toHaveAttribute('placeholder', 'acme/backend')
    expect(screen.getByText(/Subgroups are included, so a top-level group lists everything beneath it/)).toBeInTheDocument()
  })

  it('lists the organization and picking a repository fills the URL AND its default branch', async () => {
    listGroupReposMock.mockResolvedValue({ ok: true, repos: [repo('acme/app', { defaultBranch: 'develop' }), repo('acme/lib')] })
    const set = vi.fn()
    const { rerender } = render(<RepoInspector data={base} set={set} />)
    // The organization name lands in the node; the store hands it back on the next render.
    rerender(<RepoInspector data={{ ...base, group: 'acme' }} set={set} />)

    const list = screen.getByRole('button', { name: 'List repositories' })
    expect(list).toBeEnabled()
    fireEvent.click(list)
    expect(listGroupReposMock).toHaveBeenCalledWith('github', 'acme', '', undefined)

    fireEvent.click(await screen.findByRole('button', { name: /acme\/app/ }))
    expect(set).toHaveBeenCalledWith({ url: 'https://github.com/acme/app.git', branch: 'develop', group: '', only: [] })
    // Picked: the list has done its job and goes away.
    expect(screen.queryByRole('button', { name: /acme\/lib/ })).not.toBeInTheDocument()
  })

  it('a whole organization: ticking narrows it to some repositories, and "use all" widens it again', async () => {
    listGroupReposMock.mockResolvedValue({ ok: true, repos: [repo('acme/app'), repo('acme/lib', { archived: true })] })
    const set = vi.fn()
    const { rerender } = render(<RepoInspector data={{ ...base, group: 'acme' }} set={set} />)

    expect(screen.getByText(/in this organization. Tick some below to narrow it down/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Select specific repositories' }))
    const app = await screen.findByRole('button', { name: /☐ acme\/app/ })
    expect(screen.getByRole('button', { name: /acme\/lib/ })).toHaveTextContent('archived')

    fireEvent.click(app)
    expect(set).toHaveBeenCalledWith({ only: ['acme/app'] })

    rerender(<RepoInspector data={{ ...base, group: 'acme', only: ['acme/app'] }} set={set} />)
    expect(screen.getByRole('button', { name: /☑ acme\/app/ })).toBeInTheDocument()
    expect(screen.getByText('1 selected:')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit selection' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /☑ acme\/app/ }))
    expect(set).toHaveBeenCalledWith({ only: [] })
    fireEvent.click(screen.getByRole('button', { name: 'use all instead' }))
    expect(set).toHaveBeenLastCalledWith({ only: [] })
  })

  it('reports a refused listing and an empty one in words, not an empty list', async () => {
    listGroupReposMock.mockResolvedValue({ ok: false, error: 'Bad credentials', repos: [] })
    render(<RepoInspector data={{ ...base, group: 'acme' }} set={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Select specific repositories' }))
    expect(await screen.findByText('Bad credentials')).toBeInTheDocument()
    expect(screen.queryByText('No repositories found there.')).not.toBeInTheDocument()

    listGroupReposMock.mockResolvedValue({ ok: true, repos: [] })
    render(<RepoInspector data={{ ...base, group: 'ghost' }} set={vi.fn()} />)
    fireEvent.click(screen.getAllByRole('button', { name: 'Select specific repositories' })[1])
    expect(await screen.findByText('No repositories found there.')).toBeInTheDocument()
  })

  it('a listing that throws shows the error and re-enables the button', async () => {
    listGroupReposMock.mockRejectedValue(new Error('network down'))
    render(<RepoInspector data={{ ...base, group: 'acme' }} set={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Select specific repositories' }))
    expect(await screen.findByText('network down')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Select specific repositories' })).toBeEnabled())
  })

  it('branch and Fine-tuning: archived repositories are only a question for a whole organization', () => {
    const set = vi.fn()
    const { rerender } = render(<RepoInspector data={base} set={set} />)

    expect(screen.getByLabelText('Branch')).toHaveAttribute('placeholder', 'main')
    fireEvent.click(screen.getByText('Fine-tuning'))
    expect(screen.queryByLabelText('Include archived repositories')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Mount path'), { target: { value: '/workspace/app' } })
    expect(set).toHaveBeenCalledWith({ mountPath: '/workspace/app' })

    rerender(<RepoInspector data={{ ...base, group: 'acme' }} set={set} />)
    // Scope is read from the data, so the group node's shape appears without touching the select.
    fireEvent.change(screen.getByLabelText('Scope'), { target: { value: 'group' } })
    expect(screen.getByLabelText(/Branch \(blank = each repo/)).toHaveAttribute('placeholder', 'leave blank')
    fireEvent.click(screen.getByLabelText('Include archived repositories'))
    expect(set).toHaveBeenCalledWith({ includeArchived: true })
  })
})
