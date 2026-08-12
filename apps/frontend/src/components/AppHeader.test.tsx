import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppHeader } from './AppHeader.tsx'

vi.mock('../api/client.ts', () => ({
  // AuthBadge polls this; a never-resolving promise keeps it out of these tests.
  api: { authStatus: () => new Promise(() => {}) },
}))

const renderHeader = () =>
  render(<AppHeader view="flows" onView={vi.fn()} signedInAs={null} onSignOut={vi.fn()} />)

// The theme contract: data-theme on <html> (absent = dark, the stylesheet default), persisted
// under ui.theme so index.html's pre-paint script restores it without a flash.
describe('AppHeader theme switch', () => {
  afterEach(() => {
    delete document.documentElement.dataset.theme
    localStorage.clear()
  })

  it('cycles dark → light → contrast → dark, stamping <html> and persisting', () => {
    renderHeader()
    const btn = screen.getByLabelText(/^Theme:/)

    fireEvent.click(btn)
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('ui.theme')).toBe('light')

    fireEvent.click(btn)
    expect(document.documentElement.dataset.theme).toBe('contrast')

    fireEvent.click(btn)
    // Dark is the default palette: no attribute at all, so un-themed pages agree with it.
    expect(document.documentElement.dataset.theme).toBeUndefined()
    expect(localStorage.getItem('ui.theme')).toBe('dark')
  })

  it('starts from the persisted theme', () => {
    localStorage.setItem('ui.theme', 'contrast')
    renderHeader()

    expect(screen.getByLabelText(/Theme: High contrast/)).toBeInTheDocument()
  })
})
