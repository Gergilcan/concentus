import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ProviderButtons } from './ProviderButtons.tsx'

/**
 * Every way in, whether or not it is set up.
 *
 * Showing only the registered ones answered "can I sign in with my work account here?" with an
 * absence, which reads as no — when the true answer is usually "yes, once somebody registers it".
 * What makes showing them all safe is that an unregistered one is not a link: it explains, instead
 * of sending somebody to a provider that refuses them after they have typed their password.
 */
describe('ProviderButtons', () => {
  const providers = [
    { id: 'microsoft', name: 'Microsoft', configured: true },
    { id: 'google', name: 'Google', configured: false },
  ]

  it('sends a registered provider to its own sign-in', () => {
    render(<ProviderButtons providers={providers} verb="Continue" />)

    expect(screen.getByRole('link', { name: 'Continue with Microsoft' })).toHaveAttribute(
      'href',
      '/api/account/oidc/start?provider=microsoft',
    )
  })

  // The point of showing it: not a dead end, and not a redirect that fails.
  it('offers an unregistered provider and says what it needs', () => {
    render(<ProviderButtons providers={providers} verb="Continue" />)

    const google = screen.getByRole('button', { name: 'Continue with Google' })
    expect(google).toBeInTheDocument()
    expect(screen.queryByText(/not registered/)).not.toBeInTheDocument()

    fireEvent.click(google)

    expect(screen.getByText(/Google is not registered/)).toBeInTheDocument()
    expect(screen.getByText(/Resources → Members → Sign-in providers/)).toBeInTheDocument()
  })

  // A link would send somebody to a provider that has never heard of this installation.
  it('never links an unregistered provider anywhere', () => {
    render(<ProviderButtons providers={providers} verb="Continue" />)

    expect(screen.getAllByRole('link')).toHaveLength(1)
  })

  it('says what it is for: continuing, or setting the thing up', () => {
    render(<ProviderButtons providers={providers} verb="Set up" />)

    expect(screen.getByRole('link', { name: 'Set up with Microsoft' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set up with Google' })).toBeInTheDocument()
  })

  // A provider that arrived without the flag is one this build asked an older backend about; the
  // safe reading is the one that produces a working link rather than an explanation nobody needs.
  it('treats a provider with nothing said about it as ready', () => {
    render(<ProviderButtons providers={[{ id: 'microsoft', name: 'Microsoft' }]} verb="Continue" />)

    expect(screen.getByRole('link', { name: 'Continue with Microsoft' })).toBeInTheDocument()
  })
})
