import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Group } from '../api/types.ts'
import { resetGroupsCache } from './groups.ts'
import { VisibleTo } from './VisibleTo.tsx'

const groupsStatus = vi.fn()
const listGroups = vi.fn()
const assignGroup = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    groupsStatus: () => groupsStatus(),
    listGroups: () => listGroups(),
    assignGroup: (kind: string, resourceId: string, groupId: string | null) => assignGroup(kind, resourceId, groupId),
  },
}))

const REFUSAL = 'Groups inside an organization is an Enterprise feature. Write in to upgrade.'

const group = (id: string, name: string): Group => ({
  id,
  organizationId: 'org_1',
  name,
  description: null,
  createdAt: 1,
  createdBy: null,
  members: 1,
  resources: 0,
  manager: false,
})

/**
 * The select under every resource form. It calls the one endpoint that moves a resource between
 * the organization and a group, and it wears the license's refusal when there is one.
 */
describe('VisibleTo', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetGroupsCache()
    groupsStatus.mockResolvedValue({ allowed: true, refusal: null, groups: 2, mine: [{ id: 'gr_1', name: 'platform', manager: false }] })
    listGroups.mockResolvedValue({ groups: [group('gr_1', 'platform'), group('gr_2', 'support')], allowed: true, refusal: null })
  })

  it('lists the organization and every group the caller may see, and assigns on change', async () => {
    assignGroup.mockResolvedValue({ kind: 'agent', resourceId: 'ag_1', groupId: 'gr_2' })
    const onAssigned = vi.fn()
    render(<VisibleTo kind="agent" resourceId="ag_1" groupId={null} onAssigned={onAssigned} />)

    const select = await screen.findByLabelText('Visible to')
    expect(select).toHaveValue('')
    expect(screen.getByRole('option', { name: 'Organization' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'platform' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'support' })).toBeInTheDocument()

    fireEvent.change(select, { target: { value: 'gr_2' } })

    await waitFor(() => expect(assignGroup).toHaveBeenCalledWith('agent', 'ag_1', 'gr_2'))
    expect(onAssigned).toHaveBeenCalledWith('gr_2')
  })

  it('back to the organization sends null', async () => {
    assignGroup.mockResolvedValue({ kind: 'mcp', resourceId: 'mcp_1', groupId: null })
    const onAssigned = vi.fn()
    render(<VisibleTo kind="mcp" resourceId="mcp_1" groupId="gr_1" onAssigned={onAssigned} />)

    const select = await screen.findByLabelText('Visible to')
    expect(select).toHaveValue('gr_1')
    fireEvent.change(select, { target: { value: '' } })

    await waitFor(() => expect(assignGroup).toHaveBeenCalledWith('mcp', 'mcp_1', null))
    expect(onAssigned).toHaveBeenCalledWith(null)
  })

  it('is disabled with the refusal as its tooltip when the license withholds groups', async () => {
    groupsStatus.mockResolvedValue({ allowed: false, refusal: REFUSAL, groups: 1, mine: [] })
    listGroups.mockResolvedValue({ groups: [group('gr_1', 'platform')], allowed: false, refusal: REFUSAL })
    render(<VisibleTo kind="variable" resourceId="var_1" groupId="gr_1" onAssigned={vi.fn()} />)

    const select = await screen.findByLabelText('Visible to')
    expect(select).toBeDisabled()
    expect(select.closest('label')).toHaveAttribute('title', REFUSAL)
    // What is already scoped stays scoped, and the select still says so.
    expect(select).toHaveValue('gr_1')
  })

  it('shows nothing when there is no group to choose', async () => {
    listGroups.mockResolvedValue({ groups: [], allowed: true, refusal: null })
    groupsStatus.mockResolvedValue({ allowed: true, refusal: null, groups: 0, mine: [] })
    render(<VisibleTo kind="database" resourceId="db_1" groupId={null} onAssigned={vi.fn()} />)

    await waitFor(() => expect(listGroups).toHaveBeenCalled())
    expect(screen.queryByLabelText('Visible to')).toBeNull()
  })

  it('an unsaved record cannot be scoped yet, and the tooltip says to save first', async () => {
    render(<VisibleTo kind="knowledge" resourceId={undefined} groupId={null} onAssigned={vi.fn()} />)

    const select = await screen.findByLabelText('Visible to')
    expect(select).toBeDisabled()
    expect(select.closest('label')).toHaveAttribute('title', expect.stringMatching(/Save it first/))
  })

  it('a refusal from the server goes to the toast, and the record is not moved', async () => {
    assignGroup.mockRejectedValue(new Error('Only a member of the group or an administrator may do that.'))
    const onAssigned = vi.fn()
    const pushError = vi.fn()
    render(<VisibleTo kind="credential" resourceId="cred_1" groupId={null} onAssigned={onAssigned} pushError={pushError} />)

    fireEvent.change(await screen.findByLabelText('Visible to'), { target: { value: 'gr_1' } })

    await waitFor(() => expect(pushError).toHaveBeenCalledWith('Only a member of the group or an administrator may do that.'))
    expect(onAssigned).not.toHaveBeenCalled()
  })
})
