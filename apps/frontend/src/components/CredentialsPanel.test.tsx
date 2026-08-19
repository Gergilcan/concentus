import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CredentialsPanel } from './CredentialsPanel.tsx'

const listCredentials = vi.fn()
const createOAuthCredential = vi.fn()
const oauthCredentialStatus = vi.fn()
const startOAuthSignIn = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    credentialStatus: () => Promise.resolve({ available: true, hint: '' }),
    listCredentials: () => listCredentials(),
    createCredential: vi.fn(),
    updateCredential: vi.fn(),
    deleteCredential: vi.fn(),
    createOAuthCredential: (body: unknown) => createOAuthCredential(body),
    updateOAuthCredential: vi.fn(),
    oauthCredentialStatus: (id: string) => oauthCredentialStatus(id),
    startOAuthSignIn: (id: string) => startOAuthSignIn(id),
  },
}))

/**
 * The credential you sign in to, rather than paste.
 *
 * It exists so nobody has to run a vendor's auth CLI on the machine that runs the flows — which is
 * merely tedious on a laptop and impossible in a container. The two things this screen must get
 * right: never ask for a value it cannot have, and send the sign-in to the real browser.
 */
describe('CredentialsPanel — OAuth credentials', () => {
  beforeEach(() => {
    listCredentials.mockResolvedValue([])
    createOAuthCredential.mockResolvedValue({ id: 'cred_new', label: 'Google Ads' })
    oauthCredentialStatus.mockResolvedValue({
      connected: false,
      redirectUri: 'http://127.0.0.1:8734/api/credentials/oauth/callback',
      clientId: '',
      scope: '',
      hasRefreshToken: false,
    })
    startOAuthSignIn.mockResolvedValue({
      ok: true,
      authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?client_id=x',
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  async function newOAuthCredential() {
    render(<CredentialsPanel pushError={() => {}} />)
    fireEvent.click(await screen.findByText('+ New'))
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'oauth' } })
  }

  it('asks for the provider endpoints instead of a secret value', async () => {
    await newOAuthCredential()

    expect(screen.getByLabelText('Authorization URL')).toBeTruthy()
    expect(screen.getByLabelText('Token URL')).toBeTruthy()
    expect(screen.getByLabelText('Client ID')).toBeTruthy()
    // There is no single secret to type: the tokens arrive from the provider.
    expect(screen.queryByLabelText('Value')).toBeNull()
  })

  it('fills the Google endpoints, including the parameters that decide whether a refresh token arrives', async () => {
    await newOAuthCredential()

    fireEvent.click(screen.getByText('Google'))

    expect((screen.getByLabelText('Token URL') as HTMLInputElement).value)
      .toBe('https://oauth2.googleapis.com/token')
    // Without access_type=offline and prompt=consent Google issues no refresh token, and the
    // credential dies an hour after it is created.
    expect((screen.getByLabelText('Extra authorization parameters') as HTMLInputElement).value)
      .toContain('access_type=offline')
  })

  it('saves the configuration through the OAuth endpoint', async () => {
    await newOAuthCredential()

    fireEvent.change(screen.getByLabelText('Label'), { target: { value: 'Google Ads' } })
    fireEvent.click(screen.getByText('Google'))
    fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'client-1' } })
    fireEvent.change(screen.getByLabelText('Scopes'), {
      target: { value: 'https://www.googleapis.com/auth/adwords' },
    })
    fireEvent.click(screen.getByText('Save'))

    await waitFor(() => expect(createOAuthCredential).toHaveBeenCalled())
    expect(createOAuthCredential.mock.calls[0][0]).toMatchObject({
      label: 'Google Ads',
      clientId: 'client-1',
      tokenUrl: 'https://oauth2.googleapis.com/token',
      scope: 'https://www.googleapis.com/auth/adwords',
    })
  })

  it('sends the sign-in to the real browser, never an embedded one', async () => {
    // Google answers an embedded webview with disallowed_useragent, and RFC 8252 forbids it for
    // native apps: the shell turns window.open into the system browser.
    const open = vi.fn()
    vi.stubGlobal('open', open)
    listCredentials.mockResolvedValue([
      { id: 'cred_1', label: 'Google Ads', kind: 'oauth', hint: null, createdAt: 0, updatedAt: 0, lastUsedAt: null },
    ])

    render(<CredentialsPanel pushError={() => {}} />)
    fireEvent.click(await screen.findByText('Google Ads'))
    fireEvent.click(await screen.findByText('Connect'))

    await waitFor(() => expect(startOAuthSignIn).toHaveBeenCalledWith('cred_1'))
    expect(open).toHaveBeenCalledWith(
      'https://accounts.google.com/o/oauth2/v2/auth?client_id=x', '_blank', 'noopener')
    vi.unstubAllGlobals()
  })

  it('shows the callback address, because that is what the provider console needs', async () => {
    listCredentials.mockResolvedValue([
      { id: 'cred_1', label: 'Google Ads', kind: 'oauth', hint: null, createdAt: 0, updatedAt: 0, lastUsedAt: null },
    ])

    render(<CredentialsPanel pushError={() => {}} />)
    fireEvent.click(await screen.findByText('Google Ads'))

    expect(await screen.findByText('http://127.0.0.1:8734/api/credentials/oauth/callback')).toBeTruthy()
  })

  it('warns when a sign-in came back without a refresh token', async () => {
    oauthCredentialStatus.mockResolvedValue({
      connected: true,
      redirectUri: 'http://127.0.0.1:8734/api/credentials/oauth/callback',
      clientId: 'client-1',
      scope: 'adwords',
      hasRefreshToken: false,
    })
    listCredentials.mockResolvedValue([
      { id: 'cred_1', label: 'Google Ads', kind: 'oauth', hint: null, createdAt: 0, updatedAt: 0, lastUsedAt: null },
    ])

    render(<CredentialsPanel pushError={() => {}} />)
    fireEvent.click(await screen.findByText('Google Ads'))

    expect(await screen.findByText(/no refresh token/i)).toBeTruthy()
  })
})
