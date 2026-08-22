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
}

const VALID = {
  tier: 'enterprise',
  licensee: 'Tecnovent',
  seats: 5,
  expires: '2099-01-01',
  graceDaysLeft: null,
  valid: true,
  problem: null,
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
})
