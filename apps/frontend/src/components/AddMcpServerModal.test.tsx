import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AddMcpServerModal } from './AddMcpServerModal.tsx'
import type { CatalogSetup } from './McpCatalog.tsx'

const saveMcpDefMock = vi.fn()
const checkRuntimeMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    saveMcpDef: (d: unknown) => saveMcpDefMock(d),
    checkRuntime: (c: string, r: boolean) => checkRuntimeMock(c, r),
    // The runtime notice asks what installing would run, once something is missing.
    runtimeInstallPlan: () =>
      Promise.resolve({ runtime: 'uv', command: 'curl -LsSf https://astral.sh/uv/install.sh | sh' }),
    // The credential picker loads this; one stored credential is the interesting case.
    listCredentials: () => Promise.resolve([{ id: 'cred_1', label: 'Mailchimp key', hint: '••1234' }]),
  },
}))

const mailchimp: CatalogSetup = {
  name: 'Mailchimp',
  auth: 'stdio',
  note: 'Community server — runs via uvx.',
  command: 'uvx',
  args: ['mailchimp-mcp'],
  env: { MAILCHIMP_API_KEY: '', MAILCHIMP_READ_ONLY: 'true' },
}

const gitlab: CatalogSetup = {
  name: 'GitLab',
  auth: 'token',
  note: 'Needs a personal access token.',
  url: 'https://gitlab.com/api/v4/mcp',
  authHeader: 'PRIVATE-TOKEN',
}

afterEach(() => {
  vi.clearAllMocks()
})

const next = () => fireEvent.click(screen.getByText('Next'))

describe('AddMcpServerModal', () => {
  it('asks a local server’s runtime first, with the command it will run', async () => {
    checkRuntimeMock.mockResolvedValue({
      command: 'uvx',
      runtime: { id: 'uv', label: 'uv', found: false, version: '', neededFor: 'uvx servers', docsUrl: 'x' },
      satisfied: false,
    })
    render(<AddMcpServerModal entry={mailchimp} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.getByText(/Step 1 of 2/)).toBeInTheDocument()
    expect(screen.getByText('uvx mailchimp-mcp')).toBeInTheDocument()
    // The missing runtime surfaces here — before the run, not four minutes into it.
    expect(await screen.findByText(/uv is not installed/)).toBeInTheDocument()
    // …and it never blocks: a server can be added on a machine that cannot launch it yet.
    expect(screen.getByText(/never blocks adding the server/)).toBeInTheDocument()
  })

  it('fills the server’s own variables and stores the secret one by reference', async () => {
    checkRuntimeMock.mockResolvedValue({ command: 'uvx', runtime: null, satisfied: true })
    saveMcpDefMock.mockResolvedValue({ id: 'mcp_1', name: 'Mailchimp' })
    const onSaved = vi.fn()
    const onClose = vi.fn()
    render(<AddMcpServerModal entry={mailchimp} onClose={onClose} onSaved={onSaved} />)

    next()
    // The variables come from the catalogue entry, already named — nothing to type but the values.
    expect(screen.getByLabelText('MAILCHIMP_READ_ONLY')).toHaveValue('true')

    fireEvent.click(screen.getByLabelText('MAILCHIMP_API_KEY — store as a credential'))
    fireEvent.change(await screen.findByLabelText('MAILCHIMP_API_KEY'), { target: { value: 'cred_1' } })
    fireEvent.click(screen.getByText('Add Mailchimp'))

    await waitFor(() => expect(saveMcpDefMock).toHaveBeenCalled())
    const saved = saveMcpDefMock.mock.calls[0][0]
    expect(saved.env).toEqual({ MAILCHIMP_API_KEY: 'credential:cred_1', MAILCHIMP_READ_ONLY: 'true' })
    expect(saved.command).toBe('uvx')
    expect(saved.args).toEqual(['mailchimp-mcp'])
    expect(onSaved).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('asks a remote token server for its credential and nothing else', async () => {
    saveMcpDefMock.mockResolvedValue({ id: 'mcp_2', name: 'GitLab' })
    render(<AddMcpServerModal entry={gitlab} onClose={vi.fn()} onSaved={vi.fn()} />)

    // One question: a remote server launches nothing, so it has no runtime step.
    expect(screen.queryByText(/Step 1 of/)).not.toBeInTheDocument()
    expect(screen.getByText(/PRIVATE-TOKEN header/)).toBeInTheDocument()
    // The stored credentials arrive a tick later; selecting a value the <select> has no option for
    // yet is silently a no-op.
    await screen.findByRole('option', { name: /Mailchimp key/ })
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: 'cred_1' } })
    fireEvent.click(screen.getByText('Add GitLab'))

    await waitFor(() => expect(saveMcpDefMock).toHaveBeenCalled())
    const saved = saveMcpDefMock.mock.calls[0][0]
    expect(saved).toMatchObject({
      name: 'GitLab',
      url: 'https://gitlab.com/api/v4/mcp',
      credentialId: 'cred_1',
      authHeader: 'PRIVATE-TOKEN',
    })
    expect(checkRuntimeMock).not.toHaveBeenCalled()
  })

  it('carries the catalogue’s own note, so the dialog says what the server is', () => {
    checkRuntimeMock.mockResolvedValue({ command: 'uvx', runtime: null, satisfied: true })
    render(<AddMcpServerModal entry={mailchimp} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.getByText('Community server — runs via uvx.')).toBeInTheDocument()
  })
})
