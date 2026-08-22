import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppHeader } from './AppHeader.tsx'

const getLicense = vi.fn()

vi.mock('../api/client.ts', () => ({
  // AuthBadge polls this; a never-resolving promise keeps it out of these tests.
  api: { authStatus: () => new Promise(() => {}), getLicense: () => getLicense() },
}))

const NO_GRACE = {
  tier: 'individual',
  licensee: 'Gerard',
  seats: null,
  expires: null,
  graceDaysLeft: null,
  valid: true,
  problem: null,
}

const renderHeader = () =>
  render(<AppHeader view="flows" onView={vi.fn()} signedInAs={null} onSignOut={vi.fn()} />)

describe('AppHeader', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getLicense.mockResolvedValue(NO_GRACE)
  })

  it('does not carry the theme switch any more', async () => {
    renderHeader()

    // The corner belongs to what changes without being asked. A theme is set once and lives in
    // Resources → Settings; the contract it has with <html> is tested there.
    expect(screen.queryByLabelText(/^Theme:/)).toBeNull()
    await waitFor(() => expect(getLicense).toHaveBeenCalled())
  })

  it('says nothing about the license when there is no grace window', async () => {
    renderHeader()

    await waitFor(() => expect(getLicense).toHaveBeenCalled())
    expect(screen.queryByText(/License grace/)).toBeNull()
  })

  it('counts down the grace window once the license has expired', async () => {
    getLicense.mockResolvedValue({ ...NO_GRACE, valid: false, graceDaysLeft: 9, problem: 'Expired.' })
    renderHeader()

    expect(await screen.findByText('License grace: 9 days left')).toBeInTheDocument()
  })
})
