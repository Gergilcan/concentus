import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { InputNodeData, PublishApprovalView } from '../api/types.ts'
import { PermissionsProvider } from '../state/permissions.tsx'
import { useFlowStore } from '../state/store.ts'
import { InputInspector } from './InputInspector.tsx'

// Publish approval (organization policy). Not required by default — the shape every scenario
// before approvals existed assumes; the approval tests override it.
const publishApprovalMock = vi.fn<() => Promise<PublishApprovalView>>(() =>
  Promise.resolve({ required: false, savedToken: null, approvedToken: null, approvedBy: null, approvedAt: null }),
)
const approvePublishMock = vi.fn<() => Promise<PublishApprovalView>>()

vi.mock('../api/client.ts', () => ({
  api: {
    listCredentials: () => Promise.resolve([]),
    mailStatus: () => Promise.resolve({ state: 'unknown' }),
    mailSignInDefaults: () => Promise.resolve({ configured: false, tenantId: '', clientId: '' }),
    getOrgPolicy: () =>
      Promise.resolve({ policy: { requireFacade: false, publishRequiresApproval: false }, enforced: false, refusal: 'x', canEdit: false }),
    publishApproval: () => publishApprovalMock(),
    approvePublish: () => approvePublishMock(),
    revokePublish: () => Promise.resolve({ required: true, savedToken: 'tok-123', approvedToken: null, approvedBy: null, approvedAt: null }),
  },
  webhookUrl: (flowId: string) => `http://localhost/api/webhooks/${flowId}`,
  publicRunUrl: (flowId: string) => `http://localhost/api/public/flows/${flowId}/run`,
  publicChatUrl: (flowId: string, token: string) =>
    `http://localhost/api/public/flows/${flowId}/chat?token=${token}`,
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

describe('InputInspector — publish as an endpoint', () => {
  it('turning the toggle on mints a token in the browser', () => {
    const set = vi.fn()
    render(<InputInspector data={base} set={set} />)

    fireEvent.click(screen.getByLabelText(/Publish as an endpoint/))

    expect(set).toHaveBeenCalledTimes(1)
    const patch = set.mock.calls[0][0] as { published: boolean; publishToken: string }
    expect(patch.published).toBe(true)
    // A UUID (or, without randomUUID, 32 hex chars): long enough that guessing is not a plan.
    expect(patch.publishToken).toMatch(/^[0-9a-f-]{32,36}$/)
  })

  it('shows the token, a curl line carrying it, and the chat link once the flow is saved', () => {
    useFlowStore.setState({ flowId: 'flow_abc' })
    const set = vi.fn()
    render(<InputInspector data={{ ...base, published: true, publishToken: 'tok-123' }} set={set} />)

    expect(screen.getByDisplayValue('tok-123')).toBeInTheDocument()
    expect(screen.getByDisplayValue('http://localhost/api/public/flows/flow_abc/run')).toBeInTheDocument()
    const curl = screen.getByDisplayValue(/^curl -X POST/) as HTMLInputElement
    expect(curl.value).toContain('Authorization: Bearer tok-123')
    expect(curl.value).toContain('http://localhost/api/public/flows/flow_abc/run')
    expect(screen.getByRole('link', { name: 'open' })).toHaveAttribute(
      'href',
      'http://localhost/api/public/flows/flow_abc/chat?token=tok-123',
    )

    fireEvent.click(screen.getByText('Regenerate'))
    const patch = set.mock.calls[0][0] as { publishToken: string }
    expect(patch.publishToken).toMatch(/^[0-9a-f-]{32,36}$/)
    expect(patch.publishToken).not.toBe('tok-123')
    useFlowStore.setState({ flowId: null })
  })

  it('turning it off keeps the token for when it comes back, and says nothing more', () => {
    const set = vi.fn()
    render(<InputInspector data={{ ...base, published: true, publishToken: 'tok-123' }} set={set} />)

    fireEvent.click(screen.getByLabelText(/Publish as an endpoint/))
    expect(set).toHaveBeenCalledWith({ published: false })
  })
})

/**
 * Organization policy: published endpoints need an administrator's approval. The block says
 * nothing where the rule is off, tells a member the endpoint is waiting where it is on, and
 * gives an admin the button — but only for the SAVED token, never for one still in the browser.
 */
describe('InputInspector — publish approval (organization policy)', () => {
  const waiting: PublishApprovalView = {
    required: true,
    savedToken: 'tok-123',
    approvedToken: null,
    approvedBy: null,
    approvedAt: null,
  }
  const published = { ...base, published: true, publishToken: 'tok-123' }

  it('says nothing where the organization requires no approval', async () => {
    useFlowStore.setState({ flowId: 'flow_1' })
    render(<InputInspector data={published} set={vi.fn()} />)

    await waitFor(() => expect(publishApprovalMock).toHaveBeenCalled())
    expect(screen.queryByText(/administrator's approval/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Approve this endpoint' })).toBeNull()
    useFlowStore.setState({ flowId: null })
  })

  it('tells a member the endpoint is waiting, with no button to press', async () => {
    publishApprovalMock.mockResolvedValueOnce(waiting)
    useFlowStore.setState({ flowId: 'flow_1' })
    render(
      <PermissionsProvider role="MEMBER">
        <InputInspector data={published} set={vi.fn()} />
      </PermissionsProvider>,
    )

    expect(await screen.findByText("Waiting for an administrator's approval")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve this endpoint' })).toBeNull()
    useFlowStore.setState({ flowId: null })
  })

  it('gives an admin the button, and shows who approved once pressed', async () => {
    publishApprovalMock.mockResolvedValueOnce(waiting)
    approvePublishMock.mockResolvedValueOnce({
      ...waiting,
      approvedToken: 'tok-123',
      approvedBy: 'admin@acme.test',
      approvedAt: Date.now(),
    })
    useFlowStore.setState({ flowId: 'flow_1' })
    render(
      <PermissionsProvider role="ADMIN">
        <InputInspector data={published} set={vi.fn()} />
      </PermissionsProvider>,
    )

    fireEvent.click(await screen.findByRole('button', { name: 'Approve this endpoint' }))

    expect(await screen.findByText('Approved')).toBeInTheDocument()
    expect(screen.getByText(/admin@acme.test/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revoke approval' })).toBeInTheDocument()
    useFlowStore.setState({ flowId: null })
  })

  it('asks for a save first when the token in the node is not the saved one', async () => {
    // The author pressed Regenerate: the node holds a token the backend has never seen, and an
    // approval of the saved one would approve the wrong door.
    publishApprovalMock.mockResolvedValueOnce(waiting)
    useFlowStore.setState({ flowId: 'flow_1' })
    render(
      <PermissionsProvider role="ADMIN">
        <InputInspector data={{ ...published, publishToken: 'tok-new' }} set={vi.fn()} />
      </PermissionsProvider>,
    )

    expect(await screen.findByText(/this one is not saved yet/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve this endpoint' })).toBeNull()
    useFlowStore.setState({ flowId: null })
  })
})
