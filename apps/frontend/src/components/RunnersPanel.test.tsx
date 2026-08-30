import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Runner, RunnersListing } from '../api/types.ts'
import { PermissionsProvider } from '../state/permissions.tsx'
import { resetGroupsCache } from './groups.ts'
import { RunnersPanel } from './RunnersPanel.tsx'

const listRunners = vi.fn()
const createRunner = vi.fn()
const renameRunner = vi.fn()
const revokeRunner = vi.fn()
const deleteRunner = vi.fn()
const groupsStatus = vi.fn()
const listGroups = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listRunners: () => listRunners(),
    createRunner: (name: string, scope: string, groupId?: string) => createRunner(name, scope, groupId),
    renameRunner: (id: string, name: string) => renameRunner(id, name),
    revokeRunner: (id: string) => revokeRunner(id),
    deleteRunner: (id: string) => deleteRunner(id),
    groupsStatus: () => groupsStatus(),
    listGroups: () => listGroups(),
  },
}))

const HOUR = 3_600_000
const HUB = 'https://hub.example.com'

function runner(over: Partial<Runner> = {}): Runner {
  return {
    id: 'rn_1',
    organizationId: 'org',
    name: 'office-pc',
    scope: 'organization',
    groupId: null,
    groupName: null,
    userId: null,
    ownerEmail: null,
    createdBy: 'gerard@tecnovent.com',
    createdAt: Date.now() - 48 * HOUR,
    lastSeenAt: Date.now() - 2 * 60_000,
    revokedAt: null,
    online: true,
    busy: 1,
    capacity: 4,
    hostname: 'office-pc',
    os: 'linux',
    arch: 'amd64',
    version: '0.1.14',
    claudeVersion: '2.1.0',
    authKind: 'subscription',
    connectedAt: Date.now() - HOUR,
    mine: false,
    usable: true,
    ...over,
  }
}

const ROWS: Runner[] = [
  runner(),
  runner({
    id: 'rn_2',
    name: 'nas',
    scope: 'group',
    groupId: 'gr_1',
    groupName: 'platform',
    online: false,
    busy: 0,
    lastSeenAt: Date.now() - 5 * HOUR,
    authKind: 'api-key',
  }),
  runner({
    id: 'rn_3',
    name: 'laptop',
    scope: 'user',
    userId: 'u_me',
    ownerEmail: 'me@tecnovent.com',
    online: false,
    busy: 0,
    lastSeenAt: null,
    hostname: null,
    os: null,
    arch: null,
    version: null,
    authKind: null,
    mine: true,
  }),
  runner({
    id: 'rn_4',
    name: 'old-box',
    online: false,
    busy: 0,
    revokedAt: Date.now() - 50 * HOUR,
    lastSeenAt: Date.now() - 60 * HOUR,
  }),
]

function listing(over: Partial<RunnersListing> = {}): RunnersListing {
  return {
    runners: ROWS,
    hubUrl: HUB,
    mayCreate: { organization: true, groups: ['gr_1'], user: true },
    ...over,
  }
}

/**
 * Machines that run flows on their own login: what each row says, the one moment the token is on
 * screen with the ways to start it, and the create button following what the backend says the
 * caller may register. The explanations live in tooltips, so the assertions read `title`.
 */
describe('RunnersPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetGroupsCache()
    listRunners.mockResolvedValue(listing())
    groupsStatus.mockResolvedValue({
      allowed: true,
      refusal: null,
      groups: 1,
      mine: [{ id: 'gr_1', name: 'platform', manager: false }],
    })
    listGroups.mockRejectedValue(new Error('403'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const open = async (role = 'ADMIN') => {
    render(
      <PermissionsProvider role={role}>
        <RunnersPanel pushError={vi.fn()} />
      </PermissionsProvider>,
    )
    await screen.findByText('office-pc')
  }

  it('lists every runner as a row: scope chip, the host on the status dot, how busy it is, when it was last seen', async () => {
    await open()

    // The scope chip says who may run flows on it. Two rows are the organization's: the first
    // and the revoked one.
    expect(screen.getAllByText('Organization')).toHaveLength(2)
    expect(screen.getAllByText('Organization')[0]).toHaveAttribute('title', expect.stringMatching(/Anybody in the organization/))
    expect(screen.getByText('platform')).toHaveAttribute('title', expect.stringMatching(/Members of platform/))
    expect(screen.getByText('Only me')).toHaveAttribute('title', expect.stringMatching(/Only you may run flows/))

    // The dot: online or not, and what the machine said about itself on hover.
    const [online] = screen.getAllByRole('img', { name: 'online' })
    expect(online).toHaveAttribute('title', 'online · office-pc · linux/amd64 · 0.1.14 · Claude subscription')
    expect(screen.getAllByRole('img', { name: 'offline' })).toHaveLength(3)
    expect(screen.getAllByRole('img', { name: 'offline' })[0]).toHaveAttribute('title', expect.stringMatching(/API key$/))

    // Busy over capacity, only while online.
    expect(screen.getByText('1 / 4 running')).toBeInTheDocument()
    expect(screen.queryByText('0 / 4 running')).not.toBeInTheDocument()

    expect(screen.getByText(/last seen 2m ago/)).toBeInTheDocument()
    expect(screen.getByText(/never connected/)).toBeInTheDocument()
    expect(screen.getAllByText(/created .* by gerard@tecnovent.com/)).toHaveLength(4)

    // A revoked one says so as a chip and has nothing left to revoke; it can still be deleted.
    expect(screen.getByText('revoked')).toHaveAttribute('title', expect.stringMatching(/^Revoked 2d ago/))
    expect(screen.getAllByRole('button', { name: 'Revoke' })).toHaveLength(3)
    expect(screen.getAllByRole('button', { name: 'Delete' })).toHaveLength(4)

    expect(screen.getByText('4 runners')).toBeInTheDocument()
  })

  it('offers + New with every scope for an administrator', async () => {
    await open()

    fireEvent.click(screen.getByRole('button', { name: '+ New' }))
    const select = screen.getByLabelText('Scope') as HTMLSelectElement
    expect([...select.options].map((o) => o.textContent)).toEqual(['Organization', 'Group: platform', 'Only me'])
  })

  it('offers only the scopes the listing allows: a group by name and the caller alone for a member', async () => {
    listRunners.mockResolvedValue(listing({ mayCreate: { organization: false, groups: ['gr_1'], user: true } }))
    await open('MEMBER')

    fireEvent.click(screen.getByRole('button', { name: '+ New' }))
    const select = screen.getByLabelText('Scope') as HTMLSelectElement
    expect([...select.options].map((o) => o.textContent)).toEqual(['Group: platform', 'Only me'])
  })

  it('hides + New when nothing may be registered, and a viewer still sees the list', async () => {
    // A viewer sees the organization's runners and none of their own: the backend filtered.
    listRunners.mockResolvedValue(
      listing({
        runners: ROWS.filter((r) => r.scope !== 'user').map((r) => ({ ...r, mine: false })),
        mayCreate: { organization: false, groups: [], user: false },
      }),
    )
    await open('VIEWER')

    expect(screen.queryByRole('button', { name: '+ New' })).not.toBeInTheDocument()
    expect(screen.getByText('nas')).toBeInTheDocument()
    expect(screen.getByText('1 / 4 running')).toBeInTheDocument()
    // Nothing to rename, revoke or delete for somebody who administers nothing.
    expect(screen.getByText('office-pc')).not.toHaveAttribute('title')
    expect(screen.queryByRole('button', { name: 'Rename' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
  })

  it('lets the owner of a user-scoped runner rename, revoke and delete it, and nobody else', async () => {
    listRunners.mockResolvedValue(listing({ mayCreate: { organization: false, groups: [], user: true } }))
    await open('MEMBER')

    expect(screen.getAllByRole('button', { name: 'Revoke' })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: 'Delete' })).toHaveLength(1)
    expect(screen.getByText('laptop')).toHaveAttribute('title', 'Double-click to rename.')
    expect(screen.getByText('office-pc')).not.toHaveAttribute('title')
  })

  // The token is not stored, so this is its only appearance anywhere — with the three ways to
  // start the runner filled in, because this is the one moment the person has the token in hand.
  it('registers a runner, shows the token once with the start commands, and Done hides it for good', async () => {
    const token = 'crn_' + 'x'.repeat(40)
    createRunner.mockResolvedValue({ runner: runner({ id: 'rn_9', name: 'ci-box' }), token, hubUrl: HUB })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    await open()

    fireEvent.click(screen.getByRole('button', { name: '+ New' }))
    fireEvent.change(screen.getByPlaceholderText('office-pc'), { target: { value: 'ci-box' } })
    fireEvent.change(screen.getByLabelText('Scope'), { target: { value: 'group:gr_1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(createRunner).toHaveBeenCalledWith('ci-box', 'group', 'gr_1'))
    expect(await screen.findByText(token)).toBeInTheDocument()
    expect(screen.getByText(/Shown once/)).toBeInTheDocument()

    const docker = screen.getByText(/^docker run/)
    expect(docker.textContent).toContain(`CONCENTUS_RUNNER_URL=${HUB}`)
    expect(docker.textContent).toContain(`CONCENTUS_RUNNER_TOKEN=${token}`)
    expect(docker.textContent).toContain('ghcr.io/gergilcan/concentus-runner:latest')
    expect(screen.getByText(`java -jar concentus-backend.jar runner --url ${HUB} --token ${token}`)).toBeInTheDocument()
    expect(screen.getByText(/tray → Set up… → Server/)).toBeInTheDocument()

    const [copyToken] = screen.getAllByRole('button', { name: 'Copy' })
    fireEvent.click(copyToken)
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(token))
    expect(await screen.findByText('Copied')).toBeInTheDocument()

    const done = screen.getByRole('button', { name: 'Done' })
    expect(done).toHaveAttribute('title', expect.stringMatching(/cannot be shown again/))
    fireEvent.click(done)
    expect(screen.queryByText(token)).not.toBeInTheDocument()
    expect(screen.queryByText(/^docker run/)).not.toBeInTheDocument()
    await waitFor(() => expect(listRunners).toHaveBeenCalledTimes(2))
  })

  it('revokes after asking, and reloads', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    revokeRunner.mockResolvedValue(runner({ revokedAt: Date.now() }))
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Revoke' })[0])

    await waitFor(() => expect(revokeRunner).toHaveBeenCalledWith('rn_1'))
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('office-pc'))
    await waitFor(() => expect(listRunners).toHaveBeenCalledTimes(2))
  })

  it('does nothing when the revocation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Revoke' })[0])

    expect(revokeRunner).not.toHaveBeenCalled()
  })

  it('deletes after asking', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    deleteRunner.mockResolvedValue(undefined)
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Delete' })[3])

    await waitFor(() => expect(deleteRunner).toHaveBeenCalledWith('rn_4'))
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('old-box'))
  })

  it('renames in place from the pencil', async () => {
    renameRunner.mockResolvedValue(runner({ id: 'rn_2', name: 'nas-2' }))
    await open()

    fireEvent.click(screen.getAllByRole('button', { name: 'Rename' })[1])
    const input = screen.getByLabelText('New name for nas')
    fireEvent.change(input, { target: { value: 'nas-2' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(renameRunner).toHaveBeenCalledWith('rn_2', 'nas-2'))
    expect(await screen.findByText('nas-2')).toBeInTheDocument()
  })

  // Online is a fact about the last 45 seconds, so the roster asks again while it is open, and
  // stops the moment it is not.
  it('polls the listing every fifteen seconds while mounted, and stops on unmount', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const { unmount } = render(
      <PermissionsProvider role="ADMIN">
        <RunnersPanel pushError={vi.fn()} />
      </PermissionsProvider>,
    )
    await screen.findByText('office-pc')
    expect(listRunners).toHaveBeenCalledTimes(1)

    await act(async () => {
      vi.advanceTimersByTime(15_000)
    })
    expect(listRunners).toHaveBeenCalledTimes(2)

    unmount()
    await act(async () => {
      vi.advanceTimersByTime(30_000)
    })
    expect(listRunners).toHaveBeenCalledTimes(2)
  })

  it('says it is empty in one line', async () => {
    listRunners.mockResolvedValue(listing({ runners: [] }))
    render(
      <PermissionsProvider role="ADMIN">
        <RunnersPanel pushError={vi.fn()} />
      </PermissionsProvider>,
    )

    expect(await screen.findByText('No runners yet.')).toBeInTheDocument()
    expect(screen.getByText('0 runners')).toBeInTheDocument()
  })
})
