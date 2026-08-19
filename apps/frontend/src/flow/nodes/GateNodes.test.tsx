import { ReactFlowProvider } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ConditionNodeData, ForEachNodeData } from '../../api/types.ts'
import type { ConditionRFNode, ForEachRFNode } from '../nodeTypes.ts'
import { ConditionNode } from './ConditionNode.tsx'
import { ForEachNode } from './ForEachNode.tsx'

// The gates carry no live status of their own — nothing runs in them — so unlike the agent node
// tests these need no store reset, only the provider the shared card's Handle asks for.
function renderCondition(data: Partial<ConditionNodeData> = {}) {
  const props = {
    id: 'if-1',
    type: 'condition',
    data: {
      kind: 'condition',
      label: 'if',
      test: 'contains',
      value: 'ERROR',
      caseSensitive: false,
      ...data,
    },
    selected: false,
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<ConditionRFNode>
  return render(
    <ReactFlowProvider>
      <ConditionNode {...props} />
    </ReactFlowProvider>,
  )
}

function renderForEach(data: Partial<ForEachNodeData> = {}) {
  const props = {
    id: 'each-1',
    type: 'foreach',
    data: { kind: 'foreach', label: 'for each', source: 'lines', limit: 25, ...data },
    selected: false,
    dragging: false,
    zIndex: 0,
    isConnectable: true,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
  } as unknown as NodeProps<ForEachRFNode>
  return render(
    <ReactFlowProvider>
      <ForEachNode {...props} />
    </ReactFlowProvider>,
  )
}

// The rule belongs on the box. A gate whose card says only "if" makes the reader open the
// inspector to find out what the flow does, which is the habit these nodes exist to break.
describe('gate nodes', () => {
  it('a condition shows the test it applies', () => {
    renderCondition()
    expect(screen.getByText(/contains/)).toHaveTextContent('ERROR')
  })

  it('a condition with no text to look for says what it does instead', () => {
    renderCondition({ test: 'not_empty', value: '' })
    expect(screen.getByText('answered anything')).toBeInTheDocument()
  })

  it('a for-each shows how it reads the list and how many it will start', () => {
    renderForEach({ source: 'json', limit: 5 })
    expect(screen.getByText(/a JSON array/)).toHaveTextContent('up to 5')
  })
})
