import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DoctorFinding } from '../api/types.ts'
import { DoctorModal } from './DoctorModal.tsx'

const runFlowDoctorMock = vi.fn()

vi.mock('../api/client.ts', () => ({ api: { runFlowDoctor: (id: string) => runFlowDoctorMock(id) } }))

function open() {
  render(<DoctorModal flowId="f1" flowName="Mail triage" onClose={vi.fn()} />)
}

const missingCredential: DoctorFinding = {
  level: 'error',
  area: 'credential',
  message: '"Linear" points at a credential that no longer exists (cred_gone).',
  fix: 'Create it under Resources → Credentials and pick it on the node.',
  where: 'm-1',
}

const maybeOpenServer: DoctorFinding = {
  level: 'warn',
  area: 'mcp',
  message: '"Docs" has no stored token and no OAuth sign-in.',
  fix: 'If this server needs auth, sign in on the node. If it is open, nothing to do.',
  where: 'm-2',
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('DoctorModal', () => {
  it('says so plainly when there is nothing to fix', async () => {
    runFlowDoctorMock.mockResolvedValue([])
    open()

    expect(await screen.findByText(/Nothing to fix/)).toBeInTheDocument()
  })

  it('shows each finding with the fix it names', async () => {
    runFlowDoctorMock.mockResolvedValue([missingCredential, maybeOpenServer])
    open()

    expect(await screen.findByText(/points at a credential that no longer exists/)).toBeInTheDocument()
    // The fix is the point: a check that only says what is wrong has moved the problem.
    expect(screen.getByText(/Create it under Resources/)).toBeInTheDocument()
    expect(screen.getByText(/has no stored token/)).toBeInTheDocument()
  })

  it('counts what would fail and says the run is still allowed', async () => {
    runFlowDoctorMock.mockResolvedValue([missingCredential, maybeOpenServer])
    open()

    expect(await screen.findByText(/1 thing would fail/)).toBeInTheDocument()
    expect(screen.getByText(/1 worth a look/)).toBeInTheDocument()
    expect(screen.getByText(/does not block/)).toBeInTheDocument()
  })

  it('puts errors above warnings, whatever order they arrived in', async () => {
    runFlowDoctorMock.mockResolvedValue([maybeOpenServer, missingCredential])
    open()

    const rows = await screen.findAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('credential')
    expect(rows[1]).toHaveTextContent('mcp')
  })

  it('says the CHECK failed rather than dressing it as a finding', async () => {
    runFlowDoctorMock.mockRejectedValue(new Error('backend down'))
    open()

    expect(await screen.findByText(/could not run: backend down/)).toBeInTheDocument()
    expect(screen.queryByText(/Nothing to fix/)).not.toBeInTheDocument()
  })
})
