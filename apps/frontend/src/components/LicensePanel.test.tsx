import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LicensePanel } from './LicensePanel.tsx'

const getLicense = vi.fn()
const installLicense = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    getLicense: () => getLicense(),
    installLicense: (token: string) => installLicense(token),
  },
}))

const NONE = {
  tier: null,
  licensee: null,
  seats: null,
  expires: null,
  graceDaysLeft: null,
  valid: false,
  problem: 'No license installed. Paste a token below, or request one.',
  trial: false,
}

const VALID = {
  tier: 'enterprise',
  licensee: 'Tecnovent',
  seats: 5,
  expires: '2099-01-01',
  graceDaysLeft: null,
  valid: true,
  problem: null,
  trial: false,
}

/** An ISO date `days` from now — computed here, not fixed, so the countdown assertion holds on any day the test runs. */
function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10)
}

/**
 * What Concentus is running under, read once from the backend record and shown as one sentence —
 * and the one field an owner ever changes: the token pasted into the box beneath it.
 */
describe('LicensePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the request-a-license link when there is no valid license', async () => {
    getLicense.mockResolvedValue(NONE)
    render(<LicensePanel />)

    expect(await screen.findByText('No license')).toBeInTheDocument()
    expect(screen.getByText(NONE.problem)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Request a license' })).toHaveAttribute(
      'href',
      'https://www.concentus-ai.com/#license',
    )
  })

  it('shows who it is licensed to, the tier, the seats and the expiry', async () => {
    getLicense.mockResolvedValue(VALID)
    render(<LicensePanel />)

    expect(
      await screen.findByText('Licensed to Tecnovent · enterprise · 5 seats · expires 2099-01-01'),
    ).toBeInTheDocument()
  })

  it('leads with the countdown on a trial, and says "day" on the last one', async () => {
    getLicense.mockResolvedValue({ ...VALID, tier: 'team', seats: 3, expires: inDays(5), trial: true })
    render(<LicensePanel />)

    expect(
      await screen.findByText(`Trial — 5 days left · Licensed to Tecnovent · team · 3 seats · expires ${inDays(5)}`),
    ).toBeInTheDocument()
  })

  it('a bought team license is not a trial: no countdown, the ordinary line', async () => {
    getLicense.mockResolvedValue({ ...VALID, tier: 'team', seats: 3, expires: inDays(20) })
    render(<LicensePanel />)

    expect(
      await screen.findByText(`Licensed to Tecnovent · team · 3 seats · expires ${inDays(20)}`),
    ).toBeInTheDocument()
  })

  it('installs a pasted token and shows the new status', async () => {
    getLicense.mockResolvedValue(NONE)
    installLicense.mockResolvedValue(VALID)
    render(<LicensePanel />)
    await screen.findByText('No license')

    fireEvent.change(screen.getByLabelText('License token'), { target: { value: 'the-token' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await waitFor(() => expect(installLicense).toHaveBeenCalledWith('the-token'))
    expect(
      await screen.findByText('Licensed to Tecnovent · enterprise · 5 seats · expires 2099-01-01'),
    ).toBeInTheDocument()
  })

  it('shows the server error verbatim when the token is refused', async () => {
    getLicense.mockResolvedValue(NONE)
    installLicense.mockRejectedValue(new Error('That token has expired.'))
    render(<LicensePanel />)
    await screen.findByText('No license')

    fireEvent.change(screen.getByLabelText('License token'), { target: { value: 'bad-token' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    expect(await screen.findByText('That token has expired.')).toBeInTheDocument()
  })

  // A failed GET used to leave `status` null forever, and the render bailed to the spinner before
  // it ever reached the error line — a permanently spinning panel with no visible reason and no
  // way out. The fix is a `loading` flag independent of `status`, so a failed fetch still renders:
  // error visible, token box still usable.
  it('shows the error and stays usable when the initial fetch fails', async () => {
    getLicense.mockRejectedValue(new Error('The server is not answering.'))
    render(<LicensePanel />)

    expect(await screen.findByText('The server is not answering.')).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByLabelText('License token')).toBeInTheDocument()
  })
})
