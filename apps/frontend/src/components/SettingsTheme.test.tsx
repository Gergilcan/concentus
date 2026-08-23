import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SettingsPanel } from './SettingsPanel.tsx'

vi.mock('../api/client.ts', () => ({
  api: {
    // The endpoint answers with a wrapper, not a bare list — a mock that returns the wrong
    // shape leaves the panel on its spinner forever, which is not a failure anybody can read.
    listSettings: () => Promise.resolve({ settings: [] }),
    saveSettings: () => Promise.resolve({ saved: 0, restartRequired: false }),
    // LicensePanel fetches this on mount; a never-resolving promise keeps it out of these tests.
    getLicense: () => new Promise(() => {}),
  },
}))

/**
 * The theme contract: data-theme on <html> (absent = dark, the stylesheet default), persisted
 * under ui.theme so index.html's pre-paint script restores it without a flash.
 *
 * <p>It used to be asserted against a cycling button in the header. The control moved to the
 * preferences, and the contract came with it — it is the same contract, and it is the reason the
 * app does not flash white on every launch.
 */
describe('the theme, in Settings', () => {
  afterEach(() => {
    delete document.documentElement.dataset.theme
    localStorage.clear()
  })

  it('stamps <html> and persists the choice', async () => {
    render(<SettingsPanel pushError={vi.fn()} />)

    // The panel loads its settings first; the appearance row is part of the loaded screen.
    fireEvent.click(await screen.findByRole('button', { name: /Light/ }))
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('ui.theme')).toBe('light')

    fireEvent.click(screen.getByRole('button', { name: /High contrast/ }))
    expect(document.documentElement.dataset.theme).toBe('contrast')

    // Dark is the default palette: no attribute at all, so un-themed pages agree with it.
    fireEvent.click(screen.getByRole('button', { name: /Dark/ }))
    expect(document.documentElement.dataset.theme).toBeUndefined()
    expect(localStorage.getItem('ui.theme')).toBe('dark')
  })

  it('shows which one is on', async () => {
    localStorage.setItem('ui.theme', 'contrast')
    render(<SettingsPanel pushError={vi.fn()} />)

    expect(await screen.findByRole('button', { name: /High contrast/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /Light/ })).toHaveAttribute('aria-pressed', 'false')
  })
})
