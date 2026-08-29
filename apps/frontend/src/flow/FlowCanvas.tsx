import {
  Background,
  BackgroundVariant,
  Controls,
  type Edge,
  type EdgeTypes,
  MiniMap,
  type NodeChange,
  Panel,
  ReactFlow,
  type ReactFlowInstance,
  ViewportPortal,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import './canvas-overrides.css'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NODE_COLORS } from '../constants.ts'
import type { NodeKind } from '../api/types.ts'
import { NODE_DRAG_TYPE } from '../components/Palette.tsx'
import { type AppNode, useFlowStore } from '../state/store.ts'
import { cx } from '../utils/cx.ts'
import { DeletableEdge } from './DeletableEdge.tsx'
import { applyHelperLines, type HelperLines, NO_LINES } from './helperLines.ts'
import { edgeKinClass, kinClass, kinship } from './kinship.ts'
import { nodeTypes } from './nodeTypes.ts'

// Minimap swatch per node kind, read straight from the shared table. The if-chain this replaces
// made adding a node kind a two-file edit and ignored entries the table already had — 'knowledge'
// nodes were grey on the minimap for no reason anyone chose.
function nodeColor(type = 'default'): string {
  return NODE_COLORS[type] ?? NODE_COLORS.default
}

const edgeTypes: EdgeTypes = { deletable: DeletableEdge }

/** True while the user is typing, so we never hijack their real copy/paste. */
function isTextEntry(el: EventTarget | null): boolean {
  const node = el as HTMLElement | null
  if (!node) return false
  return node.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(node.tagName)
}

/**
 * React Flow ships its own dark/light variable sets, switched by `colorMode` — hardcoding
 * "dark" was why the canvas ignored the app's theme. The app's contract is `data-theme` on
 * <html> (absent = dark); a MutationObserver follows live switches from the header's toggle.
 * High-contrast maps to dark: it is a dark-ground theme, and xyflow only knows two.
 */
function useCanvasColorMode(): 'dark' | 'light' {
  const read = () => (document.documentElement.dataset.theme === 'light' ? 'light' : 'dark')
  const [mode, setMode] = useState<'dark' | 'light'>(read)
  useEffect(() => {
    const observer = new MutationObserver(() => setMode(read()))
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => observer.disconnect()
  }, [])
  return mode
}

/** The class an edge wears for the output it left from — the wire itself says error/else now,
 * not only the handle it hangs from, because at three steps' distance the handle is a dot. */
function edgeHandleClass(e: Edge): string | null {
  if (e.sourceHandle === 'error') return 'edge-error'
  if (e.sourceHandle === 'else') return 'edge-else'
  if (e.sourceHandle === 'rejected') return 'edge-rejected'
  return null
}

export function FlowCanvas() {
  const { t } = useTranslation()
  const colorMode = useCanvasColorMode()
  const nodes = useFlowStore((s) => s.nodes)
  const edges = useFlowStore((s) => s.edges)
  const runExecByNode = useFlowStore((s) => s.runExecByNode)
  const selectedId = useFlowStore((s) => s.selectedId)
  const openNodeDetails = useFlowStore((s) => s.openNodeDetails)
  const replay = useFlowStore((s) => s.replay)
  const setReplay = useFlowStore((s) => s.setReplay)
  const layoutAnimating = useFlowStore((s) => s.layoutAnimating)

  // Alignment guides while dragging — flow coordinates, drawn inside the viewport below.
  const [helperLines, setHelperLines] = useState<HelperLines>(NO_LINES)

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
  const baseNodes = useMemo(
    () => (workerNodes.length ? ([...nodes, ...workerNodes] as AppNode[]) : nodes),
    [nodes, workerNodes],
  )

  // Which boxes the selected one is wired to. Written onto the node rather than read inside each
  // node component: every kind would otherwise need the same three lines, and a kind added next
  // month would silently not highlight. React Flow puts className on the node's wrapper — the
  // rings themselves are in canvas-overrides.css.
  const kin = useMemo(() => kinship(edges, selectedId), [edges, selectedId])

  // The replay verdicts by node, when a replay is being read on this canvas.
  const replayByNode = useMemo(() => {
    if (!replay) return null
    const byNode: Record<string, string> = {}
    for (const n of replay.nodes) {
      if (n.divergent) byNode[n.nodeId] = 'replay-divergent'
      else if (n.now === 'would-skip') byNode[n.nodeId] = 'replay-skip'
    }
    return byNode
  }, [replay])

  const allNodes = useMemo(() => {
    if (kin.inbound.size === 0 && kin.outbound.size === 0 && !replayByNode) return baseNodes
    return baseNodes.map((n) => {
      const className = cx(kinClass(kin, n.id), replayByNode?.[n.id]) || undefined
      // The same object back when nothing applies, so untouched nodes do not re-render.
      return className || n.className ? { ...n, className } : n
    })
  }, [baseNodes, kin, replayByNode])

  const shownEdges = useMemo(
    () =>
      edges.map((e) => {
        const className =
          cx(edgeHandleClass(e), selectedId ? edgeKinClass(e, selectedId) : null) || undefined
        return className || e.className ? { ...e, className } : e
      }),
    [edges, selectedId],
  )
  const onNodesChange = useFlowStore((s) => s.onNodesChange)
  const onEdgesChange = useFlowStore((s) => s.onEdgesChange)
  const onConnect = useFlowStore((s) => s.onConnect)
  const selectNode = useFlowStore((s) => s.selectNode)
  const addNode = useFlowStore((s) => s.addNode)
  const checkpoint = useFlowStore((s) => s.checkpoint)
  const undo = useFlowStore((s) => s.undo)
  const redo = useFlowStore((s) => s.redo)
  const rf = useRef<ReactFlowInstance<AppNode, Edge> | null>(null)
  const copySelection = useFlowStore((s) => s.copySelection)
  const paste = useFlowStore((s) => s.paste)
  const duplicateSelection = useFlowStore((s) => s.duplicateSelection)
  const settleDrop = useFlowStore((s) => s.settleDrop)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // Enter on a focused node opens its properties — the canvas is Tab-navigable, and a block
      // you can reach but not open is half a feature. The node wrappers are React Flow's, so
      // this reads the focused element rather than wiring a handler into every node component.
      if (e.key === 'Enter' && !isTextEntry(e.target)) {
        const el = document.activeElement as HTMLElement | null
        const id = el?.classList.contains('react-flow__node') ? el.getAttribute('data-id') : null
        if (id) {
          selectNode(id)
          openNodeDetails()
          e.preventDefault()
          return
        }
      }
      if (!(e.ctrlKey || e.metaKey) || e.altKey) return
      if (isTextEntry(e.target)) return
      switch (e.key.toLowerCase()) {
        case 'c':
          // Text selected anywhere — console output, an inspector hint — means the user is
          // copying TEXT. Selected canvas nodes must not steal that Ctrl+C: isTextEntry only
          // covers focus in a field, not a selection in plain markup, and this handler used to
          // overwrite the clipboard with node JSON whenever any node happened to be selected.
          if (!window.getSelection()?.isCollapsed) return
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
        case 'z':
          if (e.shiftKey) redo()
          else undo()
          e.preventDefault()
          break
        case 'y':
          redo()
          e.preventDefault()
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [copySelection, paste, duplicateSelection, undo, redo, selectNode, openNodeDetails])

  return (
    <ReactFlow
      className={layoutAnimating ? 'layout-animating' : undefined}
      nodes={allNodes}
      edges={shownEdges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      onNodesChange={(changes: NodeChange<AppNode>[]) => {
        // The guides mutate the in-flight position change so the node truly sticks to the line.
        setHelperLines(applyHelperLines(changes, nodes))
        onNodesChange(changes)
      }}
      onEdgesChange={onEdgesChange}
      onConnect={onConnect}
      onNodeClick={(_, node) => selectNode(node.id)}
      // A prompt is not editable in a 300px column, and the side panel is the wrong shape for
      // one. Double-click opens the same inspector in a dialog with room to write in — the
      // gesture people already try on a diagram when they want "open this properly".
      onNodeDoubleClick={(_, node) => {
        selectNode(node.id)
        openNodeDetails()
      }}
      // Grabbing a node makes it the active one even when the gesture turns into a real drag —
      // otherwise moving a node never opened its inspector, because only click selected.
      // The checkpoint is here rather than in the position changes because a drag is hundreds
      // of them and exactly one undo step.
      onNodeDragStart={(_, node) => {
        checkpoint()
        selectNode(node.id)
      }}
      onSelectionDragStart={() => checkpoint()}
      // Where the nodes landed decides which frame they belong to now. Both stop handlers,
      // because a drag of several selected nodes reports through the other one.
      onNodeDragStop={(_, __, dragged) => {
        setHelperLines(NO_LINES)
        settleDrop(dragged.map((n) => n.id))
      }}
      onSelectionDragStop={(_, dragged) => settleDrop(dragged.map((n) => n.id))}
      // Deleting a frame deletes the frame. React Flow would take its members down with it —
      // sensible for a node that owns its children, wrong for a rectangle somebody drew around
      // blocks they still want. Members the user selected themselves still go; the wires that
      // stay are the ones touching nothing that is leaving.
      onBeforeDelete={async ({ nodes: doomed, edges: cut }) => {
        const frames = new Set(doomed.filter((n) => n.type === 'group').map((n) => n.id))
        const nodes = doomed.filter((n) => n.selected || !(n.parentId && frames.has(n.parentId)))
        const leaving = new Set(nodes.map((n) => n.id))
        return {
          nodes,
          edges: cut.filter((e) => e.selected || leaving.has(e.source) || leaving.has(e.target)),
        }
      }}
      // Two distinct knobs, both needed for "clicking with a slightly unsteady hand selects":
      // the drag THRESHOLD keeps the node from micro-moving under a jittery press, and the click
      // DISTANCE keeps d3's click suppression (default 0px — any movement kills the click) from
      // eating the selection. With only the first, a 3px wobble was neither a move nor a click.
      nodeDragThreshold={5}
      nodeClickDistance={5}
      paneClickDistance={5}
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
      colorMode={colorMode}
      nodesDraggable
      nodesFocusable
      elementsSelectable
      deleteKeyCode={['Backspace', 'Delete']}
      defaultEdgeOptions={{ type: 'deletable', animated: true }}
    >
      <Background variant={BackgroundVariant.Dots} gap={18} size={1} />
      {/* A minimap over a graph you can already see whole navigates nothing, and it takes a
          200x150 bite out of the canvas to do it. It arrives when the graph outgrows the screen. */}
      {allNodes.length > 8 && <MiniMap pannable zoomable nodeColor={(n) => nodeColor(n.type)} />}
      <Controls />
      {/* Alignment guides, drawn in flow coordinates so they pan and zoom with the drawing. */}
      <ViewportPortal>
        {helperLines.vertical !== null && (
          <div
            className="helper-line helper-line-v"
            style={{ transform: `translate(${helperLines.vertical}px, -50000px)` }}
          />
        )}
        {helperLines.horizontal !== null && (
          <div
            className="helper-line helper-line-h"
            style={{ transform: `translate(-50000px, ${helperLines.horizontal}px)` }}
          />
        )}
      </ViewportPortal>
      {replay && (
        <Panel position="top-center">
          <div className="replay-banner" role="status">
            <strong>
              {replay.divergences === 0
                ? t('Replay: the current flow would take the same path.')
                : replay.divergences === 1
                  ? t('Replay: 1 divergence against the current flow.')
                  : t('Replay: {{n}} divergences against the current flow.', {
                      n: replay.divergences,
                    })}
            </strong>
            <span className="replay-banner-detail">
              {replay.nodes.some((n) => n.now === 'gone' && n.divergent)
                ? ' ' +
                  t('Blocks that ran and no longer exist: {{names}}.', {
                    names: replay.nodes
                      .filter((n) => n.now === 'gone' && n.divergent)
                      .map((n) => n.label)
                      .join(', '),
                  })
                : ''}
              {' ' + t('Routing only — nothing was executed.')}
            </span>
            <button className="replay-banner-close" onClick={() => setReplay(null)}>
              {t('Close')}
            </button>
          </div>
        </Panel>
      )}
    </ReactFlow>
  )
}
