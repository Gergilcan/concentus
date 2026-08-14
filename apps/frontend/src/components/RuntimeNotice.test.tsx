import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeCheck } from '../api/types.ts'
import { RuntimeNotice } from './RuntimeNotice.tsx'

const checkRuntimeMock = vi.fn()
const installPlanMock = vi.fn()
const installRuntimeMock = vi.fn()

vi.mock('../api/client.ts', () => ({
  api: {
    checkRuntime: (c: string, r: boolean) => checkRuntimeMock(c, r),
    runtimeInstallPlan: (id: string) => installPlanMock(id),
    installRuntime: (id: string) => installRuntimeMock(id),
  },
}))

function missing(): RuntimeCheck {
  return {
    command: 'uvx mailchimp-mcp',
    runtime: { id: 'uv', label: 'uv', found: false, version: '', neededFor: 'uvx servers', docsUrl: 'https://example.test/uv' },
    satisfied: false,
  }
}

function found(version = '0.5.1'): RuntimeCheck {
  return {
    command: 'uvx mailchimp-mcp',
    runtime: { id: 'uv', label: 'uv', found: true, version, neededFor: 'uvx servers', docsUrl: 'x' },
    satisfied: true,
  }
}

const CURL = 'curl -LsSf https://astral.sh/uv/install.sh | sh'

afterEach(() => {
  vi.clearAllMocks()
})

describe('RuntimeNotice', () => {
  it('says nothing at all for a remote server, which launches no process', () => {
    const { container } = render(<RuntimeNotice command="" />)

    expect(container).toBeEmptyDOMElement()
    expect(checkRuntimeMock).not.toHaveBeenCalled()
  })

  it('confirms quietly when the runtime is there', async () => {
    checkRuntimeMock.mockResolvedValue(found('10.8.2'))

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)

    expect(await screen.findByText(/uv 10\.8\.2 found/)).toBeInTheDocument()
    // Nothing to install, so nothing is asked about installing.
    expect(installPlanMock).not.toHaveBeenCalled()
  })

  it('names the missing runtime and shows the exact command before offering to run it', async () => {
    checkRuntimeMock.mockResolvedValue(missing())
    installPlanMock.mockResolvedValue({ runtime: 'uv', command: CURL })

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)

    expect(await screen.findByText(/uv is not installed/)).toBeInTheDocument()
    // The command is visible next to the button that runs it — the whole point of this panel.
    expect(await screen.findByText(CURL)).toBeInTheDocument()
    expect(screen.getByText('Install uv')).toBeInTheDocument()
  })

  it('installs in one click, shows the installer’s output and re-probes afterwards', async () => {
    checkRuntimeMock.mockResolvedValueOnce(missing()).mockResolvedValue(found())
    installPlanMock.mockResolvedValue({ runtime: 'uv', command: CURL })
    installRuntimeMock.mockResolvedValue({ ok: true, command: CURL, output: 'installing uv…\ndone' })

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)
    fireEvent.click(await screen.findByText('Install uv'))

    await waitFor(() => expect(installRuntimeMock).toHaveBeenCalledWith('uv'))
    // Forced re-probe: the cached answer would still say "missing" right after an install.
    await waitFor(() => expect(checkRuntimeMock).toHaveBeenCalledWith('uvx mailchimp-mcp', true))
    expect(await screen.findByText(/uv 0\.5\.1 found/)).toBeInTheDocument()
  })

  it('keeps the installer’s own words when it fails, instead of a bare "failed"', async () => {
    checkRuntimeMock.mockResolvedValue(missing())
    installPlanMock.mockResolvedValue({ runtime: 'uv', command: CURL })
    installRuntimeMock.mockResolvedValue({ ok: false, command: CURL, output: "'curl' is not recognized" })

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)
    fireEvent.click(await screen.findByText('Install uv'))

    expect(await screen.findByText(/curl' is not recognized/)).toBeInTheDocument()
    expect(screen.getByText(/did not finish/)).toBeInTheDocument()
  })

  it('offers instructions rather than a button where there is no safe installer', async () => {
    // A Linux distribution's package manager needs a decision this app should not make.
    checkRuntimeMock.mockResolvedValue(missing())
    installPlanMock.mockResolvedValue({
      runtime: 'uv',
      command: null,
      reason: 'On Linux, Python is part of the system.',
    })

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)

    expect(await screen.findByText(/uv is not installed/)).toBeInTheDocument()
    expect(await screen.findByText(/part of the system/)).toBeInTheDocument()
    expect(screen.queryByText('Install uv')).not.toBeInTheDocument()
    expect(screen.getByText('Official instructions')).toHaveAttribute('href', 'https://example.test/uv')
  })

  it('stays silent when the probe itself fails, rather than blaming the machine', async () => {
    // A failed probe is not evidence of a missing runtime, and sending someone to install what
    // they already have is worse than saying nothing.
    checkRuntimeMock.mockRejectedValue(new Error('backend down'))
    const onResolved = vi.fn()

    const { container } = render(<RuntimeNotice command="uvx thing" onResolved={onResolved} />)

    await waitFor(() => expect(onResolved).toHaveBeenCalledWith(true))
    expect(container).toBeEmptyDOMElement()
  })
})
