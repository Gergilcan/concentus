import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppHeader } from './AppHeader.tsx'

vi.mock('../api/client.ts', () => ({
  // AuthBadge polls this; a never-resolving promise keeps it out of these tests.
  api: { authStatus: () => new Promise(() => {}) },
}))

const renderHeader = () =>
  render(<AppHeader view="flows" onView={vi.fn()} signedInAs={null} onSignOut={vi.fn()} />)

describe('AppHeader', () => {
  it('does not carry the theme switch any more', () => {
    renderHeader()

    // The corner belongs to what changes without being asked. A theme is set once and lives in
    // Resources → Settings; the contract it has with <html> is tested there.
    expect(screen.queryByLabelText(/^Theme:/)).toBeNull()
  })
})
