import {
  Background,
  BackgroundVariant,
  Controls,
  type Edge,
  type EdgeTypes,
  MiniMap,
  ReactFlow,
  type ReactFlowInstance,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useEffect, useMemo, useRef } from 'react'
import { NODE_COLORS } from '../constants.ts'
import type { NodeKind } from '../api/types.ts'
import { NODE_DRAG_TYPE } from '../components/Palette.tsx'
import { type AppNode, useFlowStore } from '../state/store.ts'
import { DeletableEdge } from './DeletableEdge.tsx'
import { nodeTypes } from './nodeTypes.ts'

// Minimap swatch per node kind, read straight from the shared table. The if-chain this replaces
// made adding a node kind a two-file edit and ignored entries the table already had — 'knowledge'
// nodes were grey on the minimap for no reason anyone chose.
function nodeColor(type?: string): string {
  return NODE_COLORS[type as keyof typeof NODE_COLORS] ?? NODE_COLORS.default
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
  const runExecByNode = useFlowStore((s) => s.runExecByNode)

  // Plan-born workers: boxes that exist in the run report but were never drawn. Synthesized
  // here rather than inserted into the store, so they can never be saved with the flow, and
  // they vanish the moment another run (or none) is selected. Not draggable or deletable —
  // applyNodeChanges ignores ids the store doesn't hold, which is exactly right for them.
  const workerNodes = useMemo(() => {
    const synthetic = Object.values(runExecByNode).filter(
      (e) => e.nodeId.startsWith('worker:') && !nodes.some((n) => n.id === e.nodeId),
    )
    if (synthetic.length === 0) return []
    const maxY = nodes.length ? Math.max(...nodes.map((n) => n.position.y)) : 0
    return synthetic.map((e, i) => ({
      id: e.nodeId,
      type: 'worker' as const,
      position: { x: 120 + (i % 4) * 240, y: maxY + 170 + Math.floor(i / 4) * 130 },
      draggable: false,
      data: { kind: 'worker' as const, label: e.label || e.nodeId, model: e.model ?? undefined },
    }))
  }, [runExecByNode, nodes])

  // The cast is the price of keeping 'worker' out of NodeKind (it must never be addable,
  // clonable or saved); React Flow only needs the id/type/position/data shape.
  const allNodes = useMemo(
    () => (workerNodes.length ? ([...nodes, ...workerNodes] as AppNode[]) : nodes),
    [nodes, workerNodes],
  )
  const onNodesChange = useFlowStore((s) => s.onNodesChange)
  const onEdgesChange = useFlowStore((s) => s.onEdgesChange)
  const onConnect = useFlowStore((s) => s.onConnect)
  const selectNode = useFlowStore((s) => s.selectNode)
  const addNode = useFlowStore((s) => s.addNode)
  const rf = useRef<ReactFlowInstance<AppNode, Edge> | null>(null)
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
      nodes={allNodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      onConnect={onConnect}
      onNodeClick={(_, node) => selectNode(node.id)}
      onPaneClick={() => selectNode(null)}
      // The instance is captured here rather than through useReactFlow(), which would need this
      // component split around a ReactFlowProvider to be inside its own context.
      onInit={(instance) => (rf.current = instance)}
      onDragOver={(e) => {
        if (!e.dataTransfer.types.includes(NODE_DRAG_TYPE)) return
        // Both required: without preventDefault the browser refuses the drop outright, and
        // without dropEffect the cursor shows "no entry" over a target that does accept it.
        e.preventDefault()
        e.dataTransfer.dropEffect = 'copy'
      }}
      onDrop={(e) => {
        const kind = e.dataTransfer.getData(NODE_DRAG_TYPE)
        // Validated against the registry: getData returns whatever was dragged, and an unknown
        // type would add a node React Flow has no component for — a blank hole on the canvas.
        if (!kind || !(kind in nodeTypes)) return
        e.preventDefault()
        const at = rf.current?.screenToFlowPosition({ x: e.clientX, y: e.clientY })
        // Drop point minus half a node, so the node lands under the cursor instead of hanging
        // from its top-left corner where the pointer was.
        addNode(kind as NodeKind, at && { x: at.x - 90, y: at.y - 30 })
      }}
      fitView
      // Capped at 1:1. With one or two nodes, an uncapped fit-view zooms IN until they fill the
      // screen — which is why a fresh flow's nodes looked billboard-sized. Fitting only ever
      // zooms out now; the user can still zoom in by hand.
      fitViewOptions={{ maxZoom: 1 }}
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
