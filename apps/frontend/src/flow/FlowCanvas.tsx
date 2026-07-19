import {
  Background,
  BackgroundVariant,
  Controls,
  type EdgeTypes,
  MiniMap,
  ReactFlow,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useEffect } from 'react'
import { NODE_COLORS } from '../constants.ts'
import { useFlowStore } from '../state/store.ts'
import { DeletableEdge } from './DeletableEdge.tsx'
import { nodeTypes } from './nodeTypes.ts'

// Minimap swatch per node kind. Deliberately narrower than NODE_COLORS: 'input' nodes
// have no dedicated minimap color in the original design, so they fall through to the
// same neutral default as any unrecognized kind.
function nodeColor(type?: string): string {
  if (type === 'agent') return NODE_COLORS.agent
  if (type === 'mcp') return NODE_COLORS.mcp
  if (type === 'repo') return NODE_COLORS.repo
  if (type === 'sql') return NODE_COLORS.sql
  return NODE_COLORS.default
}

const edgeTypes: EdgeTypes = { deletable: DeletableEdge }

/** True while the user is typing, so we never hijack their real copy/paste. */
function isTextEntry(el: EventTarget | null): boolean {
  const node = el as HTMLElement | null
  if (!node) return false
  return node.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(node.tagName)
}

export function FlowCanvas() {
  const nodes = useFlowStore((s) => s.nodes)
  const edges = useFlowStore((s) => s.edges)
  const onNodesChange = useFlowStore((s) => s.onNodesChange)
  const onEdgesChange = useFlowStore((s) => s.onEdgesChange)
  const onConnect = useFlowStore((s) => s.onConnect)
  const selectNode = useFlowStore((s) => s.selectNode)
  const copySelection = useFlowStore((s) => s.copySelection)
  const paste = useFlowStore((s) => s.paste)
  const duplicateSelection = useFlowStore((s) => s.duplicateSelection)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey) return
      if (isTextEntry(e.target)) return
      switch (e.key.toLowerCase()) {
        case 'c':
          if (copySelection()) e.preventDefault()
          break
        case 'v':
          paste()
          e.preventDefault()
          break
        case 'd':
          duplicateSelection()
          e.preventDefault() // Ctrl+D would otherwise bookmark the page
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [copySelection, paste, duplicateSelection])

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
