import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Group, GroupPolicyView, GroupSettings } from '../api/types.ts'
import { PermissionsProvider } from '../state/permissions.tsx'
import { resetGroupsCache } from './groups.ts'
import { GroupsPanel } from './GroupsPanel.tsx'

const listGroups = vi.fn()
const createGroup = vi.fn()
const updateGroup = vi.fn()
const deleteGroup = vi.fn()
const listGroupMembers = vi.fn()
const addGroupMember = vi.fn()
const removeGroupMember = vi.fn()
const listMembers = vi.fn()
const getGroupSettings = vi.fn()
const saveGroupSettings = vi.fn()
const getGroupPolicy = vi.fn()
const saveGroupPolicy = vi.fn()
const groupsStatus = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listGroups: () => listGroups(),
    createGroup: (name: string, description: string) => createGroup(name, description),
    updateGroup: (id: string, name: string, description: string) => updateGroup(id, name, description),
    deleteGroup: (id: string) => deleteGroup(id),
    listGroupMembers: (id: string) => listGroupMembers(id),
    addGroupMember: (id: string, userId: string, manager: boolean) => addGroupMember(id, userId, manager),
    removeGroupMember: (id: string, userId: string) => removeGroupMember(id, userId),
    listMembers: () => listMembers(),
    getGroupSettings: (id: string) => getGroupSettings(id),
    saveGroupSettings: (id: string, values: unknown) => saveGroupSettings(id, values),
    getGroupPolicy: (id: string) => getGroupPolicy(id),
    saveGroupPolicy: (id: string, policy: unknown) => saveGroupPolicy(id, policy),
    groupsStatus: () => groupsStatus(),
    listFacadeProfiles: () => Promise.resolve([{ id: 'fp_1', name: 'reader', readOnly: true }]),
  },
}))

const REFUSAL =
  'Groups inside an organization is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together; this is one of the things an organization asks for. Write in to upgrade.'

const platform: Group = {
  id: 'gr_1',
  organizationId: 'org_1',
  name: 'platform',
  description: 'The platform team',
  createdAt: 1_000,
  createdBy: 'u1',
  members: 2,
  resources: 3,
  manager: true,
}
const support: Group = { ...platform, id: 'gr_2', name: 'support', description: null, members: 1, resources: 0, manager: false }

const SETTINGS: GroupSettings = {
  values: { 'workers.retries': '3' },
  keys: [
    { key: 'workers.retries', group: 'Workers', label: 'Retries', help: 'How many times a worker is retried.', type: 'NUMBER', restartRequired: false, options: [] },
    { key: 'local.permission-mode', group: 'Local', label: 'Permission mode', help: 'What a run may do.', type: 'CHOICE', restartRequired: false, options: ['plan', 'default'] },
  ],
  inherited: { 'workers.retries': '2', 'local.permission-mode': 'default' },
}

const POLICY: GroupPolicyView = {
  defaultFacadeProfileId: null,
  requireFacade: true,
  maxPermissionMode: null,
  monthlyBudgetUsd: 50,
  publishRequiresApproval: null,
  effective: {
    defaultFacadeProfileId: 'fp_1',
    requireFacade: true,
    maxPermissionMode: 'acceptEdits',
    monthlyBudgetUsd: 50,
    publishRequiresApproval: false,
  },
}

function renderPanel(role: string | null = 'ADMIN') {
  const pushError = vi.fn()
  render(
    <PermissionsProvider role={role}>
      <GroupsPanel pushError={pushError} />
    </PermissionsProvider>,
  )
  return { pushError }
}

const openGroup = async (name: string) => {
  const row = (await screen.findByText(name)).closest('li') as HTMLElement
  fireEvent.click(within(row).getByRole('button', { name: 'Open' }))
  return row
}

/**
 * The roster and what opens from it. The gate is the thing to get right: on Team the list still
 * shows — a downgrade never widens who sees what — but nothing can be made, and the button says why.
 */
describe('GroupsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetGroupsCache()
    listGroups.mockResolvedValue({ groups: [platform, support], allowed: true, refusal: null })
    groupsStatus.mockResolvedValue({ allowed: true, refusal: null, groups: 2, mine: [] })
    listGroupMembers.mockResolvedValue([
      { userId: 'u2', email: 'ana@tecnovent.com', role: 'MEMBER', manager: false, createdAt: 1_000 },
    ])
    listMembers.mockResolvedValue([
      { id: 'u2', organizationId: 'org_1', email: 'ana@tecnovent.com', role: 'MEMBER', enabled: true, createdAt: 1 },
      { id: 'u3', organizationId: 'org_1', email: 'bo@tecnovent.com', role: 'VIEWER', enabled: true, createdAt: 1 },
    ])
    getGroupSettings.mockResolvedValue(SETTINGS)
    getGroupPolicy.mockResolvedValue(POLICY)
  })

  it('lists every group with its description, its counts as chips and the manager chip', async () => {
    renderPanel()

    expect(await screen.findByText('platform')).toBeInTheDocument()
    expect(screen.getByText('support')).toBeInTheDocument()
    expect(screen.getByText('The platform team')).toBeInTheDocument()
    expect(screen.getByText('2 members')).toBeInTheDocument()
    expect(screen.getByText('3 resources')).toBeInTheDocument()
    expect(screen.getByText('1 member')).toBeInTheDocument()
    expect(screen.getByText('0 resources')).toBeInTheDocument()
    expect(screen.getAllByText('manager')).toHaveLength(1)
    expect(screen.getByText('2 groups')).toBeInTheDocument()
  })

  it('on Team the refusal shows once under the header and + New is disabled with it as tooltip', async () => {
    listGroups.mockResolvedValue({ groups: [platform], allowed: false, refusal: REFUSAL })
    renderPanel()

    expect(await screen.findByText(REFUSAL)).toBeInTheDocument()
    expect(screen.getAllByText(REFUSAL)).toHaveLength(1)
    const create = screen.getByRole('button', { name: '+ New' })
    expect(create).toBeDisabled()
    expect(create).toHaveAttribute('title', REFUSAL)
    // What already exists is still listed: a downgrade never widens who sees what.
    expect(screen.getByText('platform')).toBeInTheDocument()
  })

  it('creates a group from the header form', async () => {
    createGroup.mockResolvedValue({ ...platform, id: 'gr_3', name: 'data' })
    renderPanel()
    await screen.findByText('platform')

    fireEvent.click(screen.getByRole('button', { name: '+ New' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'data' } })
    fireEvent.change(screen.getByLabelText('Description (optional)'), { target: { value: 'A data crew' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(createGroup).toHaveBeenCalledWith('data', 'A data crew'))
    // The roster is read again, and the shared answer every select reads from too.
    await waitFor(() => expect(listGroups).toHaveBeenCalledTimes(2))
    expect(groupsStatus).toHaveBeenCalled()
  })

  it('renames from the pencil, keeping the description', async () => {
    updateGroup.mockResolvedValue({ ...platform, name: 'core' })
    renderPanel()
    await screen.findByText('platform')

    fireEvent.click(screen.getByRole('button', { name: 'Rename platform' }))
    const input = screen.getByLabelText('New name for platform')
    fireEvent.change(input, { target: { value: 'core' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(updateGroup).toHaveBeenCalledWith('gr_1', 'core', 'The platform team'))
  })

  it('deletes after a confirmation that says resources return to the organization', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    deleteGroup.mockResolvedValue({ deleted: true, unscoped: 3 })
    renderPanel()
    const row = (await screen.findByText('platform')).closest('li') as HTMLElement

    const del = within(row).getByRole('button', { name: 'Delete' })
    expect(del).toHaveAttribute('title', expect.stringMatching(/return to the organization/))
    fireEvent.click(del)

    expect(confirm.mock.calls[0][0]).toMatch(/returns to the organization/)
    await waitFor(() => expect(deleteGroup).toHaveBeenCalledWith('gr_1'))
    confirm.mockRestore()
  })

  it('a refused confirmation deletes nothing', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPanel()
    const row = (await screen.findByText('platform')).closest('li') as HTMLElement
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }))
    expect(deleteGroup).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('a manager who is not an admin opens what they manage and can neither make nor delete groups', async () => {
    renderPanel('MEMBER')
    const platformRow = (await screen.findByText('platform')).closest('li') as HTMLElement
    const supportRow = screen.getByText('support').closest('li') as HTMLElement

    expect(screen.queryByRole('button', { name: '+ New' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
    expect(within(platformRow).getByRole('button', { name: 'Open' })).toBeInTheDocument()
    expect(within(supportRow).queryByRole('button', { name: 'Open' })).toBeNull()
  })

  describe('members', () => {
    it('lists who is in the group and adds an account from the organization, as a manager', async () => {
      addGroupMember.mockResolvedValue({ userId: 'u3', email: 'bo@tecnovent.com', role: 'VIEWER', manager: true, createdAt: 2 })
      renderPanel()
      await openGroup('platform')

      expect(await screen.findByText('ana@tecnovent.com')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Add member' }))
      // Only accounts not yet in the group are offered.
      const picker = screen.getByLabelText('Account')
      expect(within(picker).queryByRole('option', { name: 'ana@tecnovent.com' })).toBeNull()
      fireEvent.change(picker, { target: { value: 'u3' } })
      fireEvent.click(screen.getByLabelText('Manager'))
      fireEvent.click(screen.getByRole('button', { name: 'Add member' }))

      await waitFor(() => expect(addGroupMember).toHaveBeenCalledWith('gr_1', 'u3', true))
      await waitFor(() => expect(listGroupMembers).toHaveBeenCalledTimes(2))
    })

    it('the manager toggle re-sends the account with the other flag', async () => {
      addGroupMember.mockResolvedValue({ userId: 'u2', email: 'ana@tecnovent.com', role: 'MEMBER', manager: true, createdAt: 1_000 })
      renderPanel()
      await openGroup('platform')

      const toggle = await screen.findByLabelText('Manager: ana@tecnovent.com')
      expect(toggle).not.toBeChecked()
      expect(toggle.closest('label')).toHaveAttribute('title', expect.stringMatching(/add and remove members/))
      fireEvent.click(toggle)

      await waitFor(() => expect(addGroupMember).toHaveBeenCalledWith('gr_1', 'u2', true))
      await waitFor(() => expect(screen.getByLabelText('Manager: ana@tecnovent.com')).toBeChecked())
    })

    it('removes an account from the group', async () => {
      removeGroupMember.mockResolvedValue(undefined)
      renderPanel()
      await openGroup('platform')
      await screen.findByText('ana@tecnovent.com')

      fireEvent.click(screen.getByRole('button', { name: 'Remove' }))

      await waitFor(() => expect(removeGroupMember).toHaveBeenCalledWith('gr_1', 'u2'))
    })
  })

  describe('settings', () => {
    it('shows the inherited value muted until overridden, and saves only the overrides', async () => {
      saveGroupSettings.mockImplementation((_id: string, values: Record<string, string>) =>
        Promise.resolve({ ...SETTINGS, values }),
      )
      renderPanel()
      await openGroup('platform')
      fireEvent.click(await screen.findByRole('tab', { name: 'Settings' }))

      // Not set by the group: the organization's value, marked as inherited, no Reset.
      const mode = await screen.findByLabelText('Permission mode')
      expect(mode).toHaveValue('default')
      expect(screen.getByText('inherited')).toBeInTheDocument()
      expect(screen.getAllByRole('button', { name: 'Reset' })).toHaveLength(1)
      // Set by the group: its own value, and a way back.
      expect(screen.getByLabelText('Retries')).toHaveValue(3)
      expect(screen.getByText('set for this group')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

      // Reset first, while it is the only one: overriding the mode below grows a second Reset.
      fireEvent.click(screen.getByRole('button', { name: 'Reset' }))
      expect(screen.getByLabelText('Retries')).toHaveValue(2)
      fireEvent.change(mode, { target: { value: 'plan' } })
      expect(screen.getAllByRole('button', { name: 'Reset' })).toHaveLength(1)
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))

      await waitFor(() => expect(saveGroupSettings).toHaveBeenCalledWith('gr_1', { 'local.permission-mode': 'plan' }))
      expect(await screen.findByText('Saved. Applies to the next run.')).toBeInTheDocument()
    })

    it('on Team the controls are disabled under the refusal', async () => {
      listGroups.mockResolvedValue({ groups: [platform], allowed: false, refusal: REFUSAL })
      renderPanel()
      await openGroup('platform')
      fireEvent.click(await screen.findByRole('tab', { name: 'Settings' }))

      expect(await screen.findByLabelText('Retries')).toBeDisabled()
      expect(screen.getAllByText(REFUSAL).length).toBeGreaterThanOrEqual(2)
    })
  })

  describe('policy', () => {
    it('every rule has an inherit switch, shows what is in effect, and saves null for the inherited ones', async () => {
      saveGroupPolicy.mockImplementation((_id: string, policy: GroupPolicyView) =>
        Promise.resolve({ ...policy, effective: POLICY.effective }),
      )
      renderPanel()
      await openGroup('platform')
      fireEvent.click(await screen.findByRole('tab', { name: 'Policy' }))

      // Inherited: the switch is on, the control off, and the organization's value is named.
      const inheritCeiling = await screen.findByLabelText('Inherit: Permission ceiling')
      expect(inheritCeiling).toBeChecked()
      expect(screen.getByLabelText('Permission ceiling')).toBeDisabled()
      expect(screen.getByText('in effect: Auto-accept file edits, ask for the rest')).toBeInTheDocument()
      // Set by the group: the switch is off and the control holds the group's own value.
      expect(screen.getByLabelText('Inherit: Group budget (USD per month)')).not.toBeChecked()
      expect(screen.getByLabelText('Group budget (USD per month)')).toHaveValue(50)
      expect(screen.getByText('in effect: reader')).toBeInTheDocument()

      // Stop inheriting the ceiling: the control wakes up holding what was in effect, then narrows.
      fireEvent.click(inheritCeiling)
      const ceiling = screen.getByLabelText('Permission ceiling')
      expect(ceiling).toBeEnabled()
      expect(ceiling).toHaveValue('acceptEdits')
      fireEvent.change(ceiling, { target: { value: 'plan' } })
      // And the budget goes back to the organization's.
      fireEvent.click(screen.getByLabelText('Inherit: Group budget (USD per month)'))
      expect(screen.getByLabelText('Group budget (USD per month)')).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))

      await waitFor(() =>
        expect(saveGroupPolicy).toHaveBeenCalledWith('gr_1', {
          defaultFacadeProfileId: null,
          requireFacade: true,
          maxPermissionMode: 'plan',
          monthlyBudgetUsd: null,
          publishRequiresApproval: null,
        }),
      )
      expect(await screen.findByText('Saved. Applies to the next run.')).toBeInTheDocument()
    })

    it('a rule the API leaves out of its answer is an inherited one', async () => {
      // The backend serialises with non_null: a fresh group answers only `effective`, and every
      // rule of its own is simply absent — which has to read as inherited, not as "set to nothing".
      getGroupPolicy.mockResolvedValue({ effective: POLICY.effective })
      renderPanel()
      await openGroup('platform')
      fireEvent.click(await screen.findByRole('tab', { name: 'Policy' }))

      expect(await screen.findByLabelText('Inherit: Permission ceiling')).toBeChecked()
      expect(screen.getByLabelText('Inherit: Group budget (USD per month)')).toBeChecked()
      expect(screen.getByLabelText('Permission ceiling')).toBeDisabled()
      expect(screen.getByLabelText('Group budget (USD per month)')).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    })

    it('on Team there is no Save and the refusal shows', async () => {
      listGroups.mockResolvedValue({ groups: [platform], allowed: false, refusal: REFUSAL })
      renderPanel()
      await openGroup('platform')
      fireEvent.click(await screen.findByRole('tab', { name: 'Policy' }))

      await screen.findByLabelText('Inherit: Permission ceiling')
      expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
      expect(screen.getAllByText(REFUSAL).length).toBeGreaterThanOrEqual(2)
    })
  })
})
