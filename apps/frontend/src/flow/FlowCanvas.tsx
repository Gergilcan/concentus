import {
  Background,
  BackgroundVariant,
  Controls,
  type EdgeTypes,
  MiniMap,
  ReactFlow,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useFlowStore } from '../state/store.ts'
import { DeletableEdge } from './DeletableEdge.tsx'
import { nodeTypes } from './nodeTypes.ts'

function nodeColor(type?: string): string {
  if (type === 'agent') return '#6ea8fe'
  if (type === 'mcp') return '#63e6be'
  if (type === 'repo') return '#ffd43b'
  if (type === 'sql') return '#ff922b'
  return '#888'
}

const edgeTypes: EdgeTypes = { deletable: DeletableEdge }

export function FlowCanvas() {
  const nodes = useFlowStore((s) => s.nodes)
  const edges = useFlowStore((s) => s.edges)
  const onNodesChange = useFlowStore((s) => s.onNodesChange)
  const onEdgesChange = useFlowStore((s) => s.onEdgesChange)
  const onConnect = useFlowStore((s) => s.onConnect)
  const selectNode = useFlowStore((s) => s.selectNode)

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      onConnect={onConnect}
      onNodeClick={(_, node) => selectNode(node.id)}
      onPaneClick={() => selectNode(null)}
      fitView
      colorMode="dark"
      nodesDraggable
      elementsSelectable
      deleteKeyCode={['Backspace', 'Delete']}
      defaultEdgeOptions={{ type: 'deletable', animated: true }}
    >
      <Background variant={BackgroundVariant.Dots} gap={18} size={1} />
      <MiniMap pannable zoomable nodeColor={(n) => nodeColor(n.type)} />
      <Controls />
    </ReactFlow>
  )
}
