import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeCheck } from '../api/types.ts'
import { RuntimeNotice } from './RuntimeNotice.tsx'

const checkRuntimeMock = vi.fn()
const shellBridgeMock = vi.fn()

vi.mock('../api/client.ts', () => ({ api: { checkRuntime: (c: string, r: boolean) => checkRuntimeMock(c, r) } }))
vi.mock('../api/shell.ts', () => ({ shellBridge: () => shellBridgeMock() }))

function missing(runtime = 'uv', label = 'uv'): RuntimeCheck {
  return {
    command: 'uvx mailchimp-mcp',
    runtime: { id: runtime, label, found: false, version: '', neededFor: 'uvx servers', docsUrl: 'https://example.test/uv' },
    satisfied: false,
  }
}

/** A shell that can install, with an output stream that records its unsubscribe being called. */
function bridgeWith(install: () => Promise<{ ok: boolean; detail: string }>, unsubscribe = vi.fn()) {
  return {
    runtimes: {
      plan: vi.fn().mockResolvedValue({ runtime: 'uv', command: 'curl -LsSf https://astral.sh/uv/install.sh | sh' }),
      install: vi.fn(install),
      onOutput: vi.fn((handler: (line: string) => void) => {
        handler('installing…\n')
        return unsubscribe
      }),
    },
  }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('RuntimeNotice', () => {
  it('says nothing at all for a remote server, which launches no process', () => {
    shellBridgeMock.mockReturnValue(null)
    const { container } = render(<RuntimeNotice command="" />)

    expect(container).toBeEmptyDOMElement()
    expect(checkRuntimeMock).not.toHaveBeenCalled()
  })

  it('confirms quietly when the runtime is there', async () => {
    shellBridgeMock.mockReturnValue(null)
    checkRuntimeMock.mockResolvedValue({
      command: 'npx -y thing',
      runtime: { id: 'npm', label: 'npm', found: true, version: '10.8.2', neededFor: 'npx servers', docsUrl: 'x' },
      satisfied: true,
    })

    render(<RuntimeNotice command="npx -y thing" />)

    expect(await screen.findByText(/npm 10\.8\.2 found/)).toBeInTheDocument()
  })

  it('names the missing runtime and shows the exact command before offering to run it', async () => {
    shellBridgeMock.mockReturnValue(bridgeWith(async () => ({ ok: true, detail: '' })))
    checkRuntimeMock.mockResolvedValue(missing())

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)

    expect(await screen.findByText(/uv is not installed/)).toBeInTheDocument()
    // The command is visible next to the button that runs it — the whole point of this panel.
    expect(screen.getByText('curl -LsSf https://astral.sh/uv/install.sh | sh')).toBeInTheDocument()
    expect(screen.getByText('Install uv')).toBeInTheDocument()
  })

  it('installs through the shell, streams its output and re-probes afterwards', async () => {
    const bridge = bridgeWith(async () => ({ ok: true, detail: '' }))
    shellBridgeMock.mockReturnValue(bridge)
    checkRuntimeMock.mockResolvedValueOnce(missing()).mockResolvedValue({
      command: 'uvx mailchimp-mcp',
      runtime: { id: 'uv', label: 'uv', found: true, version: '0.5.1', neededFor: 'uvx servers', docsUrl: 'x' },
      satisfied: true,
    })

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)
    fireEvent.click(await screen.findByText('Install uv'))

    await waitFor(() => expect(bridge.runtimes.install).toHaveBeenCalledWith('uv'))
    // Forced re-probe: the cached answer would still say "missing" right after an install.
    await waitFor(() => expect(checkRuntimeMock).toHaveBeenCalledWith('uvx mailchimp-mcp', true))
    expect(await screen.findByText(/uv 0\.5\.1 found/)).toBeInTheDocument()
  })

  it('offers instructions rather than a button when there is no shell to install with', async () => {
    shellBridgeMock.mockReturnValue(null)
    checkRuntimeMock.mockResolvedValue(missing())

    render(<RuntimeNotice command="uvx mailchimp-mcp" />)

    expect(await screen.findByText(/uv is not installed/)).toBeInTheDocument()
    expect(screen.queryByText('Install uv')).not.toBeInTheDocument()
    expect(screen.getByText('Official instructions')).toHaveAttribute('href', 'https://example.test/uv')
  })

  it('stays silent when the probe itself fails, rather than blaming the machine', async () => {
    // A failed probe is not evidence of a missing runtime, and sending someone to install what
    // they already have is worse than saying nothing.
    shellBridgeMock.mockReturnValue(null)
    checkRuntimeMock.mockRejectedValue(new Error('backend down'))
    const onResolved = vi.fn()

    const { container } = render(<RuntimeNotice command="uvx thing" onResolved={onResolved} />)

    await waitFor(() => expect(onResolved).toHaveBeenCalledWith(true))
    expect(container).toBeEmptyDOMElement()
  })
})
