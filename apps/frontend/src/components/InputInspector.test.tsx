import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { InputNodeData } from '../api/types.ts'
import { InputInspector } from './InputInspector.tsx'

vi.mock('../api/client.ts', () => ({
  api: {
    listCredentials: () => Promise.resolve([]),
    mailStatus: () => Promise.resolve({ state: 'unknown' }),
    mailSignInDefaults: () => Promise.resolve({ configured: false, tenantId: '', clientId: '' }),
  },
  webhookUrl: (flowId: string) => `http://localhost/api/webhooks/${flowId}`,
}))

const base: InputNodeData = {
  kind: 'input',
  mode: 'manual',
  prompt: '',
  cron: '0 9 * * *',
  secret: '',
  authParam: 'Linear-Signature',
}

describe('InputInspector — folder watch', () => {
  it('shows the folder, the pattern and the quiet time, and edits the folder', () => {
    const set = vi.fn()
    render(<InputInspector data={{ ...base, mode: 'watch', watchPath: 'C:\\drop' }} set={set} />)

    expect(screen.getByText('Folder to watch')).toBeInTheDocument()
    expect(screen.getByText('Files that count')).toBeInTheDocument()
    expect(screen.getByText('Quiet time before a run (seconds)')).toBeInTheDocument()

    fireEvent.change(screen.getByDisplayValue('C:\\drop'), { target: { value: 'C:\\drop\\incoming' } })
    expect(set).toHaveBeenCalledWith({ watchPath: 'C:\\drop\\incoming' })

    fireEvent.change(screen.getByPlaceholderText('*.pdf'), { target: { value: '*.csv' } })
    expect(set).toHaveBeenCalledWith({ watchGlob: '*.csv' })

    // The default is written out where the person can see it, not hidden in the backend.
    expect(screen.getByDisplayValue('5')).toBeInTheDocument()
  })

  it('hides the watch fields in every other mode', () => {
    render(<InputInspector data={{ ...base, mode: 'cron' }} set={vi.fn()} />)

    expect(screen.queryByText('Folder to watch')).toBeNull()
  })
})

describe('InputInspector — webhook provider presets', () => {
  it('choosing a provider fills the validation parameter and says where its secret lives', () => {
    const set = vi.fn()
    render(<InputInspector data={{ ...base, mode: 'webhook' }} set={set} />)

    // Linear-Signature is the stored default, so the select starts on Linear.
    const select = screen.getByDisplayValue('Linear')
    expect(screen.getByText(/Linear shows a signing secret/)).toBeInTheDocument()

    fireEvent.change(select, { target: { value: 'github' } })
    expect(set).toHaveBeenCalledWith({ authParam: 'X-Hub-Signature-256' })

    fireEvent.change(select, { target: { value: 'gitlab' } })
    expect(set).toHaveBeenCalledWith({ authParam: 'X-Gitlab-Token' })
  })

  it('follows a parameter typed by hand, and Custom leaves it alone', () => {
    const set = vi.fn()
    const { rerender } = render(
      <InputInspector data={{ ...base, mode: 'webhook', authParam: 'X-Hub-Signature-256' }} set={set} />,
    )
    expect(screen.getByDisplayValue('GitHub')).toBeInTheDocument()
    expect(screen.getByText(/GitHub signs every delivery/)).toBeInTheDocument()

    rerender(<InputInspector data={{ ...base, mode: 'webhook', authParam: 'clientState' }} set={set} />)
    expect(screen.getByDisplayValue('Custom')).toBeInTheDocument()

    fireEvent.change(screen.getByDisplayValue('Custom'), { target: { value: 'custom' } })
    // Nothing on the wire changes for Custom: the parameter is whatever the person typed.
    expect(set).not.toHaveBeenCalledWith(expect.objectContaining({ authParam: expect.anything() }))
  })
})
