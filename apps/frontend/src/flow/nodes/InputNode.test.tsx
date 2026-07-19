import { ReactFlowProvider } from '@xyflow/react'
import type { Node, NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { InputNodeData } from '../../api/types.ts'
import { InputNode } from './InputNode.tsx'
import styles from './nodes.module.scss'

type InputRFNode = Node<InputNodeData, 'input'>

function inputData(overrides: Partial<InputNodeData> = {}): InputNodeData {
  return { kind: 'input', mode: 'manual', prompt: '', cron: '', secret: '', authParam: '', ...overrides }
}

// InputNode is the flow's entry point: it has no target handle (nothing feeds into it), so
// it renders through the shared card scaffold with just a source handle. <Handle> needs a
// <ReactFlowProvider>.
function renderInputNode(props: Partial<NodeProps<InputRFNode>> = {}) {
  const base = {
    id: 'input-1',
    data: inputData(),
    selected: false,
    type: 'input',
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<InputRFNode>
  return render(
    <ReactFlowProvider>
      <InputNode {...base} {...props} />
    </ReactFlowProvider>,
  )
}

describe('InputNode', () => {
  it('always titles itself "Input" and shows the play icon', () => {
    renderInputNode()
    expect(screen.getByText('▶')).toBeInTheDocument()
    expect(screen.getByText('Input')).toBeInTheDocument()
  })

  it.each([
    ['manual', 'Manual'],
    ['prompt', 'Prompt'],
    ['cron', 'Automatic (cron)'],
    ['webhook', 'Webhook'],
  ] as const)('shows the "%s" mode as badge text "%s"', (mode, label) => {
    renderInputNode({ data: inputData({ mode }) })
    expect(screen.getByText(label)).toBeInTheDocument()
  })

  it('shows the cron schedule, or a "no schedule" placeholder when empty, only in cron mode', () => {
    renderInputNode({ data: inputData({ mode: 'cron', cron: '0 * * * *' }) })
    expect(screen.getByText('0 * * * *')).toBeInTheDocument()

    renderInputNode({ data: inputData({ mode: 'cron', cron: '' }) })
    expect(screen.getByText('no schedule')).toBeInTheDocument()
  })

  it('shows a fixed placeholder for webhook mode regardless of prompt', () => {
    renderInputNode({ data: inputData({ mode: 'webhook', prompt: 'ignored' }) })
    expect(screen.getByText('starts on an external event')).toBeInTheDocument()
  })

  it('shows a fixed placeholder for manual mode regardless of prompt', () => {
    renderInputNode({ data: inputData({ mode: 'manual', prompt: 'ignored' }) })
    expect(screen.getByText('you type the first message')).toBeInTheDocument()
  })

  it('renders the whole prompt, leaving the visible clamp to CSS', () => {
    // Truncating in JS cut mid-word with nothing to indicate more text; the snippet is
    // line-clamped in CSS instead, so the full string is in the DOM and reachable on hover.
    const prompt = 'x'.repeat(120)
    renderInputNode({ data: inputData({ mode: 'prompt', prompt }) })

    const snippet = screen.getByText(prompt)
    expect(snippet).toBeInTheDocument()
    expect(snippet).toHaveAttribute('title', prompt)
  })

  it('shows a "no prompt set" placeholder for prompt mode with an empty prompt', () => {
    renderInputNode({ data: inputData({ mode: 'prompt', prompt: '' }) })
    expect(screen.getByText('no prompt set')).toBeInTheDocument()
  })

  it('renders only a source handle, no target handle', () => {
    const { container } = renderInputNode()
    const handles = container.querySelectorAll('.react-flow__handle')
    expect(handles).toHaveLength(1)
    expect(container.querySelector('.react-flow__handle-right')).not.toBeNull()
    expect(container.querySelector('.react-flow__handle-left')).toBeNull()
  })

  it('applies the input variant class to the root', () => {
    const { container } = renderInputNode()
    expect(container.firstChild).toHaveClass(styles.input)
  })

  it('applies the selected class when selected is true', () => {
    const { container } = renderInputNode({ selected: true })
    expect(container.firstChild).toHaveClass(styles.selected)
  })
})
