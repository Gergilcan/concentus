import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SettingsPanel } from './SettingsPanel.tsx'

const listSettings = vi.fn()
const saveSettings = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    listSettings: () => listSettings(),
    saveSettings: (values: Record<string, string>) => saveSettings(values),
    // LicensePanel fetches this on mount; a never-resolving promise keeps it out of these tests.
    getLicense: () => new Promise(() => {}),
  },
}))

const RUNS = {
  key: 'runs.max-concurrent',
  group: 'Runs',
  label: 'Runs at once',
  help: 'How many flows may execute simultaneously.',
  type: 'NUMBER' as const,
  restartRequired: true,
  options: [] as string[],
  source: 'CONFIGURED' as const,
  value: '4',
  hasValue: true,
}

const DOMAINS = {
  key: 'app.auth.allowed-email-domains',
  group: 'Access',
  label: 'Domains allowed to sign in',
  help: 'Blank allows any.',
  type: 'LIST' as const,
  restartRequired: false,
  options: [] as string[],
  source: 'DEFAULT' as const,
  value: '',
  hasValue: false,
}

const API_KEY = {
  key: 'local-model.api-key',
  group: 'Local models',
  label: 'API key',
  help: 'Only if that endpoint asks for one.',
  type: 'SECRET' as const,
  restartRequired: true,
  options: [] as string[],
  source: 'STORED' as const,
  value: '',
  hasValue: true,
}

/**
 * The screen that replaced a list of environment variables.
 *
 * What it has to get right is not the fields — those are a form — but the two things a properties
 * file could never say: where the value in front of you came from, and whether changing it does
 * anything before the next restart.
 */
describe('SettingsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listSettings.mockResolvedValue({ settings: [RUNS, DOMAINS, API_KEY] })
    saveSettings.mockResolvedValue({ settings: [{ ...RUNS, value: '12', source: 'STORED' }] })
  })

  const open = async () => {
    render(<SettingsPanel pushError={vi.fn()} />)
    await screen.findByText('Runs at once')
  }

  it('groups the settings and explains what each one does', async () => {
    await open()

    expect(screen.getByRole('heading', { name: 'Runs' })).toBeInTheDocument()
    expect(screen.getByText('How many flows may execute simultaneously.')).toBeInTheDocument()
  })

  // "4" means three different things, and only one of them is yours to clear.
  it('says where each value came from', async () => {
    await open()

    // Matched exactly: "default" also appears inside help text, and a loose match would pass
    // whether or not the origin was ever rendered.
    expect(screen.getByText('from this deployment · needs a restart')).toBeInTheDocument()
    expect(screen.getByText('default')).toBeInTheDocument()
    expect(screen.getByText('set here · needs a restart')).toBeInTheDocument()
  })

  it('saves only what was changed', async () => {
    await open()

    fireEvent.change(screen.getByLabelText('Runs at once'), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: /Save 1 change/ }))

    await waitFor(() => expect(saveSettings).toHaveBeenCalledWith({ 'runs.max-concurrent': '12' }))
  })

  // The difference between a setting that took effect and one that will: saying so is the whole
  // reason the catalogue carries the flag.
  it('says a restart is needed when the change waits for one', async () => {
    await open()
    fireEvent.change(screen.getByLabelText('Runs at once'), { target: { value: '12' } })

    fireEvent.click(screen.getByRole('button', { name: /Save 1 change/ }))

    expect(await screen.findByText(/Restart Concentus/)).toBeInTheDocument()
  })

  it('says so plainly when a change is already in effect', async () => {
    saveSettings.mockResolvedValue({ settings: [DOMAINS] })
    await open()
    fireEvent.change(screen.getByLabelText('Domains allowed to sign in'), {
      target: { value: 'tecnovent.com' },
    })

    fireEvent.click(screen.getByRole('button', { name: /Save 1 change/ }))

    expect(await screen.findByText(/In effect now/)).toBeInTheDocument()
  })

  // A secret's value is never read back out of the API, so the field shows that it has one rather
  // than what it is.
  it('shows a secret as set without showing it', async () => {
    await open()

    const field = screen.getByLabelText('API key')
    expect(field).toHaveValue('')
    expect(field).toHaveAttribute('placeholder', expect.stringContaining('unchanged'))
  })

  it('has nothing to save until something changes', async () => {
    await open()

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})
