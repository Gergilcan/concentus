import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendFlow } from '../api/types.ts'
import { PermissionsProvider } from '../state/permissions.tsx'
import { FlowCard } from './FlowCard.tsx'
import { resetGroupsCache } from './groups.ts'

const groupsStatus = vi.fn()
const listGroups = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    groupsStatus: () => groupsStatus(),
    listGroups: () => listGroups(),
  },
}))

const flow: BackendFlow = { id: 'f1', name: 'Mail triage', nodes: [], edges: [], groupId: 'gr_1' }

function renderCard(props: Partial<React.ComponentProps<typeof FlowCard>> = {}, role = 'ADMIN') {
  const setVisibleToFor = vi.fn()
  render(
    <PermissionsProvider role={role}>
      <FlowCard
        flow={flow}
        flowRuns={[]}
        onOpen={vi.fn()}
        onRun={vi.fn()}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
        patch={vi.fn()}
        exportFlow={vi.fn()}
        setVersionsFor={vi.fn()}
        setSettingsFor={vi.fn()}
        setTagFilter={vi.fn()}
        setVisibleToFor={setVisibleToFor}
        {...props}
      />
    </PermissionsProvider>,
  )
  return { setVisibleToFor }
}

/** The card's part of groups: the chip that names the group, and the menu item that changes it. */
describe('FlowCard and groups', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetGroupsCache()
    groupsStatus.mockResolvedValue({ allowed: true, refusal: null, groups: 1, mine: [] })
    listGroups.mockResolvedValue({
      groups: [{ id: 'gr_1', organizationId: 'org_1', name: 'platform', description: null, createdAt: 1, createdBy: null, members: 1, resources: 1, manager: true }],
      allowed: true,
      refusal: null,
    })
  })

  it('wears the group as a chip, named once the list is known', async () => {
    renderCard()
    const chip = await screen.findByTestId('group-chip')
    expect(chip).toHaveAttribute('title', 'Visible to the members of this group and administrators')
    expect(await screen.findByText('platform')).toBe(chip)
  })

  it('a flow of the whole organization wears no chip', () => {
    renderCard({ flow: { ...flow, groupId: null } })
    expect(screen.queryByTestId('group-chip')).toBeNull()
  })

  it('the menu offers "Visible to…" and hands the flow over', () => {
    const { setVisibleToFor } = renderCard()
    fireEvent.click(screen.getByRole('button', { name: 'More actions for Mail triage' }))
    const item = screen.getByRole('menuitem', { name: /Visible to…/ })
    expect(item).toHaveAttribute('title', expect.stringMatching(/policy and settings/))
    fireEvent.click(item)
    expect(setVisibleToFor).toHaveBeenCalledWith(flow)
  })

  it('a viewer sees the item disabled with the reason, not missing', () => {
    renderCard({}, 'VIEWER')
    fireEvent.click(screen.getByRole('button', { name: 'More actions for Mail triage' }))
    const item = screen.getByRole('menuitem', { name: /Visible to…/ })
    expect(item).toBeDisabled()
    expect(item).toHaveAttribute('title', expect.stringMatching(/cannot change flows/))
  })

  it('without a handler the item is not there', () => {
    renderCard({ setVisibleToFor: undefined })
    fireEvent.click(screen.getByRole('button', { name: 'More actions for Mail triage' }))
    expect(screen.queryByRole('menuitem', { name: /Visible to…/ })).toBeNull()
  })
})
