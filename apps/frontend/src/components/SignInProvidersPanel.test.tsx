import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SignInProvidersPanel } from './SignInProvidersPanel.tsx'

const listSignInProviders = vi.fn()
const saveSignInProvider = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listSignInProviders: () => listSignInProviders(),
    saveSignInProvider: (update: unknown) => saveSignInProvider(update),
  },
}))

const GOOGLE = {
  id: 'google',
  name: 'Google',
  enabled: true,
  clientId: 'g-id',
  hasSecret: true,
  tenant: '',
  issuer: '',
  displayName: '',
  wantsTenant: false,
  wantsIssuer: false,
  refusal: null,
}

const GENERIC = {
  ...GOOGLE,
  id: 'generic',
  name: 'your organization',
  clientId: 'okta-id',
  issuer: 'https://acme.okta.com',
  wantsIssuer: true,
}

const GENERIC_REFUSAL =
  'Custom identity providers (any OpenID Connect issuer) is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together; this is one of the things an organization asks for. Write in to upgrade.'
const JIT_REFUSAL =
  'Automatic accounts for an email domain is an Enterprise feature — the Team license covers everything a team of up to ten needs to work together; this is one of the things an organization asks for. Write in to upgrade.'

const ENTERPRISE = {
  providers: [GOOGLE, GENERIC],
  redirectUri: 'https://app.acme.com/api/account/oidc/callback',
  live: [{ id: 'google', name: 'Google' }],
  allowedDomains: 'acme.com',
  domainJitRefusal: null,
}

const TEAM = {
  ...ENTERPRISE,
  providers: [GOOGLE, { ...GENERIC, refusal: GENERIC_REFUSAL }],
  live: [{ id: 'google', name: 'Google' }],
  domainJitRefusal: JIT_REFUSAL,
}

/**
 * What the license withholds is said next to the thing it withholds, in the backend's words: a
 * custom issuer on Team is listed and marked inactive rather than hidden, and the domain
 * allowlist says why it does nothing. Enterprise sees neither sentence.
 */
describe('SignInProvidersPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('marks a withheld provider inactive, keeps its fields readable, and links the write-in address', async () => {
    listSignInProviders.mockResolvedValue(TEAM)
    render(<SignInProvidersPanel pushError={vi.fn()} />)

    expect(await screen.findByText('Enterprise — inactive')).toBeInTheDocument()
    expect(screen.getByText(GENERIC_REFUSAL)).toBeInTheDocument()
    // The registration is not lost: the id is still there, just not editable or saveable.
    expect(screen.getByDisplayValue('okta-id')).toBeDisabled()
    expect(screen.getByDisplayValue('https://acme.okta.com')).toBeDisabled()
    // Google, the preset, is untouched beside it.
    expect(screen.getByText('on the sign-in screen')).toBeInTheDocument()
    expect(screen.getByDisplayValue('g-id')).not.toBeDisabled()
    const writeIn = screen.getAllByRole('link', { name: 'Write in' })
    expect(writeIn.length).toBeGreaterThanOrEqual(1)
    for (const link of writeIn) expect(link).toHaveAttribute('href', expect.stringMatching(/^mailto:/))
  })

  it('shows the domain allowlist disabled with the refusal on a team license', async () => {
    listSignInProviders.mockResolvedValue(TEAM)
    render(<SignInProvidersPanel pushError={vi.fn()} />)

    const field = await screen.findByLabelText('Domains whose people get an account on first sign-in')
    expect(field).toBeDisabled()
    expect(field).toHaveValue('acme.com')
    expect(screen.getByText(/Automatic accounts for an email domain is an Enterprise feature/)).toBeInTheDocument()
    expect(screen.getByText(/Add people under Members instead/)).toBeInTheDocument()
  })

  it('says nothing about the license on an enterprise deployment', async () => {
    listSignInProviders.mockResolvedValue(ENTERPRISE)
    render(<SignInProvidersPanel pushError={vi.fn()} />)

    expect(await screen.findByText('https://app.acme.com/api/account/oidc/callback')).toBeInTheDocument()
    expect(screen.queryByText('Enterprise — inactive')).toBeNull()
    expect(screen.queryByRole('link', { name: 'Write in' })).toBeNull()
    expect(screen.getByDisplayValue('okta-id')).not.toBeDisabled()
    // The allowlist is read-only everywhere (it is startup configuration), but says so plainly.
    expect(screen.getByLabelText('Domains whose people get an account on first sign-in')).toBeDisabled()
    expect(screen.getByText(/Set at startup with AUTH_ALLOWED_DOMAINS/)).toBeInTheDocument()
  })
})
