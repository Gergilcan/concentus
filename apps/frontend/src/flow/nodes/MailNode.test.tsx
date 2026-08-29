import { ReactFlowProvider } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { MailNodeData } from '../../api/types.ts'
import { useFlowStore } from '../../state/store.ts'
import type { MailRFNode } from '../nodeTypes.ts'
import { MailNode } from './MailNode.tsx'
import styles from './nodes.module.scss'

function mailData(overrides: Partial<MailNodeData> = {}): MailNodeData {
  return {
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
    ...overrides,
  }
}

// The badge reads the wire the box hangs off, so the store's edges are the fixture.
function renderMailNode(id = 'm1', data = mailData()) {
  const props = {
    id,
    data,
    selected: false,
    type: 'mail',
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<MailRFNode>
  return render(
    <ReactFlowProvider>
      <MailNode {...props} />
    </ReactFlowProvider>,
  )
}

describe('MailNode', () => {
  beforeEach(() => {
    useFlowStore.getState().newFlow()
  })

  it('shows the envelope, the label, the recipients and the subject', () => {
    const { container } = renderMailNode()
    expect(screen.getByText('✉')).toBeInTheDocument()
    expect(screen.getByText('Tell Gerard')).toBeInTheDocument()
    expect(screen.getByText(/gerard@example\.com · \{\{flow\}\}: \{\{status\}\}/)).toBeInTheDocument()
    expect(container.firstChild).toHaveClass(styles.mail)
  })

  it('has a target handle and no source handle — a mailbox hands nothing back', () => {
    const { container } = renderMailNode()
    expect(container.querySelector('.react-flow__handle.target')).not.toBeNull()
    expect(container.querySelector('.react-flow__handle.source')).toBeNull()
  })

  it('says it will not send while nothing is wired into it', () => {
    renderMailNode()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByText('not wired to a block — it will not send')).toBeInTheDocument()
  })

  it('reads which output it hangs off, through a gate, and says what that sends', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('verifier')
    addNode('condition')
    addNode('mail')
    const [verifier, gate, mail] = useFlowStore.getState().nodes
    // Adding each box drew a main-output wire to the one before it; the drawing under test is
    // the one a person makes from the verifier's second output.
    useFlowStore.setState({ edges: [] })
    onConnect({ source: verifier.id, target: gate.id, sourceHandle: 'rejected', targetHandle: null })
    onConnect({ source: gate.id, target: mail.id, sourceHandle: null, targetHandle: null })

    renderMailNode(mail.id, mailData({ to: '' }))

    expect(screen.getByText('ON REJECTED')).toBeInTheDocument()
    expect(screen.getByText('sends the verification report')).toBeInTheDocument()
    expect(screen.getByText(/^no recipient yet · /)).toBeInTheDocument()
  })

  it('reads the main output as "after", with the final answer as the body', () => {
    const { addNode, onConnect } = useFlowStore.getState()
    addNode('coordinator')
    addNode('mail')
    const [lead, mail] = useFlowStore.getState().nodes
    useFlowStore.setState({ edges: [] })
    onConnect({ source: lead.id, target: mail.id, sourceHandle: null, targetHandle: null })

    renderMailNode(mail.id)

    expect(screen.getByText('AFTER')).toBeInTheDocument()
    expect(screen.getByText("sends the run's final answer")).toBeInTheDocument()
  })
})
