import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrgPolicyView } from '../api/types.ts'
import { PoliciesPanel } from './PoliciesPanel.tsx'

const getOrgPolicy = vi.fn<() => Promise<OrgPolicyView>>()
const saveOrgPolicy = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    getOrgPolicy: () => getOrgPolicy(),
    saveOrgPolicy: (p: unknown) => saveOrgPolicy(p),
    listFacadeProfiles: () => Promise.resolve([{ id: 'fprof_1', name: 'reader', readOnly: true }]),
  },
}))

const POLICY = {
  id: 'default',
  defaultFacadeProfileId: 'fprof_1',
  requireFacade: true,
  maxPermissionMode: 'acceptEdits',
  monthlyBudgetUsd: 250,
  publishRequiresApproval: true,
}

const REFUSAL =
  'Organization policies is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together; this is one of the things an organization asks for. Write in to upgrade.'

/**
 * The gate, as the panel shows it: on Team the record renders read-only under the license's own
 * sentence; on Enterprise an admin edits and saves, a member only reads.
 */
describe('PoliciesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('on Team it is read-only and shows the refusal in the license’s own words', async () => {
    getOrgPolicy.mockResolvedValue({ policy: POLICY, enforced: false, refusal: REFUSAL, canEdit: false })
    render(<PoliciesPanel pushError={() => {}} />)

    expect(await screen.findByText(REFUSAL)).toBeInTheDocument()
    expect(screen.getByText('Read-only.')).toBeInTheDocument()
    // What was saved still shows — a downgrade is not a deletion — but nothing can be changed.
    await waitFor(() => expect(screen.getByDisplayValue('reader')).toBeDisabled())
    expect(screen.getByDisplayValue('Auto-accept file edits, ask for the rest')).toBeDisabled()
    expect(screen.getByDisplayValue('250')).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
  })

  it('on Enterprise an admin edits and saves the policy', async () => {
    getOrgPolicy.mockResolvedValue({ policy: POLICY, enforced: true, refusal: null, canEdit: true })
    saveOrgPolicy.mockImplementation((p: OrgPolicyView['policy']) =>
      Promise.resolve({ policy: p, enforced: true, refusal: null, canEdit: true }),
    )
    render(<PoliciesPanel pushError={() => {}} />)

    const ceiling = await screen.findByDisplayValue('Auto-accept file edits, ask for the rest')
    expect(ceiling).toBeEnabled()
    expect(screen.queryByText(REFUSAL)).toBeNull()
    // Nothing to save until something changed.
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

    fireEvent.change(ceiling, { target: { value: 'plan' } })
    fireEvent.change(screen.getByDisplayValue('250'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveOrgPolicy).toHaveBeenCalledTimes(1))
    expect(saveOrgPolicy).toHaveBeenCalledWith(
      expect.objectContaining({ maxPermissionMode: 'plan', monthlyBudgetUsd: null, requireFacade: true }),
    )
    expect(await screen.findByText('Saved. Applies to the next run.')).toBeInTheDocument()
  })

  it('on Enterprise a member reads but cannot edit', async () => {
    getOrgPolicy.mockResolvedValue({ policy: POLICY, enforced: true, refusal: null, canEdit: false })
    render(<PoliciesPanel pushError={() => {}} />)

    expect(await screen.findByText(/Only an administrator can change these/)).toBeInTheDocument()
    expect(screen.getByDisplayValue('Auto-accept file edits, ask for the rest')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
  })

  it('says the context roots are a server setting rather than offering to edit them', async () => {
    getOrgPolicy.mockResolvedValue({ policy: POLICY, enforced: true, refusal: null, canEdit: true })
    render(<PoliciesPanel pushError={() => {}} />)

    expect(await screen.findByText('Allowed context roots')).toBeInTheDocument()
    expect(screen.getByText('local.context-roots')).toBeInTheDocument()
  })
})
