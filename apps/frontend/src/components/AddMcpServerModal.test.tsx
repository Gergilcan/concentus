import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AddMcpServerModal } from './AddMcpServerModal.tsx'

const saveMcpDefMock = vi.fn()
const checkRuntimeMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    saveMcpDef: (d: unknown) => saveMcpDefMock(d),
    checkRuntime: (c: string, r: boolean) => checkRuntimeMock(c, r),
    // The credential picker loads this; an empty list is the "nothing stored yet" case.
    listCredentials: () => Promise.resolve([{ id: 'cred_1', label: 'Mailchimp key', hint: '••1234' }]),
  },
}))
vi.mock('../api/shell.ts', () => ({ shellBridge: () => null }))

afterEach(() => {
  vi.clearAllMocks()
})

const next = () => fireEvent.click(screen.getByText('Next'))

describe('AddMcpServerModal', () => {
  it('walks a local server through its runtime check before asking for values', async () => {
    checkRuntimeMock.mockResolvedValue({
      command: 'uvx mailchimp-mcp',
      runtime: { id: 'uv', label: 'uv', found: false, version: '', neededFor: 'uvx servers', docsUrl: 'x' },
      satisfied: false,
    })
    saveMcpDefMock.mockResolvedValue({ id: 'mcp_1', name: 'mailchimp' })

    render(
      <AddMcpServerModal
        initial={{ name: 'mailchimp', command: 'uvx', args: ['mailchimp-mcp'], env: { MAILCHIMP_READ_ONLY: 'true' } }}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    )

    expect(screen.getByText(/Step 1 of 3/)).toBeInTheDocument()
    next()

    // The requirements step is where a missing runtime surfaces — before the run, not during it.
    expect(await screen.findByText(/uv is not installed/)).toBeInTheDocument()
    // …and it never blocks: adding a server the machine cannot launch yet is allowed, and said so.
    expect(screen.getByText(/never blocks adding the server/)).toBeInTheDocument()
    next()

    expect(screen.getByDisplayValue('MAILCHIMP_READ_ONLY')).toBeInTheDocument()
    expect(screen.getByDisplayValue('true')).toBeInTheDocument()
  })

  it('stores a secret variable as a credential reference, never as its value', async () => {
    checkRuntimeMock.mockResolvedValue({ command: 'uvx x', runtime: null, satisfied: true })
    saveMcpDefMock.mockResolvedValue({ id: 'mcp_1', name: 'mailchimp' })
    const onSaved = vi.fn()

    render(
      <AddMcpServerModal
        initial={{ name: 'mailchimp', command: 'uvx', args: ['mailchimp-mcp'], env: { MAILCHIMP_API_KEY: '' } }}
        onClose={vi.fn()}
        onSaved={onSaved}
      />,
    )
    next()
    next()

    fireEvent.click(screen.getByLabelText(/This is a secret/))
    fireEvent.change(await screen.findByLabelText('Credential'), { target: { value: 'cred_1' } })
    fireEvent.click(screen.getByText('Add server'))

    await waitFor(() => expect(saveMcpDefMock).toHaveBeenCalled())
    const saved = saveMcpDefMock.mock.calls[0][0]
    expect(saved.env).toEqual({ MAILCHIMP_API_KEY: 'credential:cred_1' })
    expect(saved.command).toBe('uvx')
    expect(saved.args).toEqual(['mailchimp-mcp'])
    expect(onSaved).toHaveBeenCalled()
  })

  it('skips the runtime step for a remote server, which launches nothing', async () => {
    saveMcpDefMock.mockResolvedValue({ id: 'mcp_2', name: 'linear' })

    render(<AddMcpServerModal onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.getByText(/Step 1 of 2/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'linear' } })
    fireEvent.change(screen.getByLabelText('URL'), { target: { value: 'https://mcp.linear.app/mcp' } })
    next()

    expect(screen.getByText(/the values it needs/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('Add server'))

    await waitFor(() => expect(saveMcpDefMock).toHaveBeenCalled())
    expect(saveMcpDefMock.mock.calls[0][0].url).toBe('https://mcp.linear.app/mcp')
    expect(checkRuntimeMock).not.toHaveBeenCalled()
  })

  it('will not move on until the server has a name and somewhere to reach it', () => {
    render(<AddMcpServerModal onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(screen.getByText('Next')).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'linear' } })
    expect(screen.getByText('Next')).toBeDisabled()
    fireEvent.change(screen.getByLabelText('URL'), { target: { value: 'https://mcp.linear.app/mcp' } })
    expect(screen.getByText('Next')).toBeEnabled()
  })
})
