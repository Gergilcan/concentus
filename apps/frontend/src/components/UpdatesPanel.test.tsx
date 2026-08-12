import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ShellBridge, ShellUpdateState } from '../api/shell.ts'
import { UpdatesPanel } from './UpdatesPanel.tsx'

const state = (over: Partial<ShellUpdateState> = {}): ShellUpdateState => ({
  supported: true,
  version: '1.2.3',
  phase: 'idle',
  ...over,
})

const setBridge = (updates: ShellBridge['updates']) => {
  ;(window as unknown as { concentusShell?: ShellBridge }).concentusShell = { updates }
}

describe('UpdatesPanel', () => {
  afterEach(() => {
    delete (window as unknown as { concentusShell?: ShellBridge }).concentusShell
  })

  it('renders nothing outside the desktop shell', () => {
    const { container } = render(<UpdatesPanel />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the running version and drives a manual check', async () => {
    const check = vi.fn().mockResolvedValue(state({ phase: 'downloading', available: '1.3.0', progressPercent: 40 }))
    setBridge({ status: vi.fn().mockResolvedValue(state()), check, install: vi.fn() })
    render(<UpdatesPanel />)

    expect(await screen.findByText('1.2.3')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Check for updates'))

    expect(check).toHaveBeenCalled()
    expect(await screen.findByText(/Downloading 1\.3\.0… 40%/)).toBeInTheDocument()
  })

  it('says why when this run cannot update itself, and disables the check', async () => {
    setBridge({
      status: vi.fn().mockResolvedValue(
        state({ supported: false, reason: 'Development run — only the installed app can update itself.' }),
      ),
      check: vi.fn(),
      install: vi.fn(),
    })
    render(<UpdatesPanel />)

    expect(await screen.findByText(/Development run/)).toBeInTheDocument()
    expect(screen.getByText('Check for updates')).toBeDisabled()
  })

  it('enables Restart & update only once downloaded, and surfaces install refusals', async () => {
    const install = vi.fn().mockResolvedValue({ ok: false, error: 'No downloaded update to install yet.' })
    setBridge({
      status: vi.fn().mockResolvedValue(state({ phase: 'downloaded', available: '1.3.0' })),
      check: vi.fn(),
      install,
    })
    render(<UpdatesPanel />)

    const btn = await screen.findByText('Restart & update')
    expect(btn).toBeEnabled()
    fireEvent.click(btn)

    expect(install).toHaveBeenCalled()
    expect(await screen.findByText(/No downloaded update to install yet/)).toBeInTheDocument()
  })
})
