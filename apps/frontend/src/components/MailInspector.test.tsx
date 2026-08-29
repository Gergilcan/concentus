import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { MailNodeData } from '../api/types.ts'
import { MailInspector } from './MailInspector.tsx'

vi.mock('../api/client.ts', () => ({
  api: {
    listCredentials: () => Promise.resolve([{ id: 'cred_1', label: 'Gmail bot', kind: 'mail-password', hint: '••ab' }]),
  },
}))

const data: MailNodeData = {
  kind: 'mail',
  label: 'Tell Gerard',
  to: 'gerard@example.com',
  subject: '{{flow}}: {{status}}',
  smtpHost: 'smtp.gmail.com',
  smtpPort: 587,
  smtpStarttls: true,
  smtpUsername: '',
  from: 'bot@gmail.com',
  credentialId: 'cred_1',
}

describe('MailInspector', () => {
  it('edits the recipients and the subject, and never offers a body', () => {
    const set = vi.fn()
    render(<MailInspector data={data} set={set} />)

    fireEvent.change(screen.getByDisplayValue('gerard@example.com'), { target: { value: 'a@x.com, b@x.com' } })
    expect(set).toHaveBeenCalledWith({ to: 'a@x.com, b@x.com' })

    fireEvent.change(screen.getByDisplayValue('{{flow}}: {{status}}'), { target: { value: 'Report' } })
    expect(set).toHaveBeenCalledWith({ subject: 'Report' })

    // The body is whatever the wire carries; a field for it would be a second source of truth.
    expect(screen.queryByText(/^Body/)).toBeNull()
    expect(screen.getByText(/nothing to write here/)).toBeInTheDocument()
  })

  it('documents the subject placeholders without letting i18next eat them', () => {
    render(<MailInspector data={data} set={vi.fn()} />)

    expect(screen.getByText('{{flow}}')).toBeInTheDocument()
    expect(screen.getByText('{{status}}')).toBeInTheDocument()
    expect(screen.getByTitle(/\{\{flow\}\} becomes the flow's name; \{\{status\}\} becomes completed, failed or rejected/)).toBeInTheDocument()
  })

  it('switching STARTTLS off moves the port to 465, and back on to 587', () => {
    const set = vi.fn()
    const { rerender } = render(<MailInspector data={data} set={set} />)

    fireEvent.click(screen.getByRole('checkbox'))
    expect(set).toHaveBeenCalledWith({ smtpStarttls: false, smtpPort: 465 })

    rerender(<MailInspector data={{ ...data, smtpStarttls: false, smtpPort: 465 }} set={set} />)
    fireEvent.click(screen.getByRole('checkbox'))
    expect(set).toHaveBeenCalledWith({ smtpStarttls: true, smtpPort: 587 })
  })

  it('picks the mailbox password from the stored credentials by id', async () => {
    const set = vi.fn()
    render(<MailInspector data={{ ...data, credentialId: '' }} set={set} />)

    const option = await screen.findByRole('option', { name: /Gmail bot/ })
    fireEvent.change(option.closest('select')!, { target: { value: 'cred_1' } })

    expect(set).toHaveBeenCalledWith({ credentialId: 'cred_1' })
  })
})
