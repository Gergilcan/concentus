import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
} from '@xyflow/react'
import { create } from 'zustand'
import type {
  AppNodeData,
  BackendFlow,
  BackendFlowNode,
  GraphMetrics,
  NodeExec,
  NodeExecReport,
  NodeKind,
  ReplayReport,
  RunDiff,
  RunEvent,
} from '../api/types.ts'
import { DEFAULT_MAX_TOKENS, DEFAULT_MODEL } from '../constants.ts'
import { NODE_H, NODE_W, tidyLayout } from '../flow/layout.ts'
import { canConnect, isAnnotation, wiringRoleOf } from '../flow/wiring.ts'

export type AppNode = Node<AppNodeData>

/** Successive pastes of the same clipboard cascade instead of stacking. */
let pasteCount = 0

function uid(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().slice(0, 8)}`
}

/**
 * How many capabilities already hang under this box, so the next does not land on top of them.
 *
 * Counting every incoming edge counted the trigger too, which does not hang underneath — it sits
 * to the left, in the chain — and the first capability was pushed a whole column further right
 * than it should have been, off the edge of the canvas.
 */
function attached(
  s: { nodes: AppNode[]; edges: { source: string; target: string }[] },
  id: string,
): number {
  return s.edges.filter((e) => {
    if (e.target !== id) return false
    const source = s.nodes.find((n) => n.id === e.source)
    // A trigger feeds too, and it is the one feeder that belongs in the chain rather than
    // under the box — counting it pushed the first capability a whole column off the canvas.
    return !!source && source.data.kind !== 'input' && wiringRoleOf(source.data.kind) === 'feeds'
  }).length
}

/** Canvas offset applied to each successive paste so copies never land on the original. */
const PASTE_OFFSET = 40

/** A fresh frame: room for a short chain of cards, before the author drags a corner. */
const GROUP_W = 480
const GROUP_H = 260

/** What a tidied frame keeps around its members: the label band above, a margin elsewhere. */
const FRAME_TOP = 36
const FRAME_PAD = 16

function sizeOf(n: AppNode): { w: number; h: number } {
  return {
    w: n.width ?? n.measured?.width ?? NODE_W,
    h: n.height ?? n.measured?.height ?? NODE_H,
  }
}

/**
 * Where a node sits on the canvas, whatever it is nested in. React Flow keeps a framed node's
 * position RELATIVE to its frame, so that the frame drags its contents along; everything that
 * reasons about the drawing as a whole — a drop, a save, a copy — wants the absolute one.
 */
function absolutePosition(n: AppNode, all: AppNode[]): { x: number; y: number } {
  const parent = n.parentId ? all.find((p) => p.id === n.parentId) : undefined
  return parent ? { x: parent.position.x + n.position.x, y: parent.position.y + n.position.y } : n.position
}

/**
 * Frames ahead of everything else. React Flow resolves a child's place from its parent's and
 * insists the parent be listed first; frames never nest, so "frames first" is the whole order.
 */
function parentsFirst(nodes: AppNode[]): AppNode[] {
  const groups = nodes.filter((n) => n.data.kind === 'group')
  return groups.length ? [...groups, ...nodes.filter((n) => n.data.kind !== 'group')] : nodes
}

/**
 * Re-homes the nodes `ids` after they landed: a node dropped inside a frame becomes its member
 * (position made relative), one dropped outside stops being one (position made absolute). The
 * centre decides, not the corner — a card half over the frame's edge is where the hand meant it.
 * Frames themselves never nest.
 */
function settle(nodes: AppNode[], ids: string[]): AppNode[] {
  const groups = nodes.filter((n) => n.data.kind === 'group')
  let changed = false
  const out = nodes.map((n) => {
    if (!ids.includes(n.id) || n.data.kind === 'group') return n
    const abs = absolutePosition(n, nodes)
    const { w, h } = sizeOf(n)
    const centre = { x: abs.x + w / 2, y: abs.y + h / 2 }
    const host = groups.find((g) => {
      const size = sizeOf(g)
      return (
        centre.x >= g.position.x &&
        centre.x <= g.position.x + size.w &&
        centre.y >= g.position.y &&
        centre.y <= g.position.y + size.h
      )
    })
    if ((n.parentId ?? undefined) === host?.id) return n
    changed = true
    const position = host ? { x: abs.x - host.position.x, y: abs.y - host.position.y } : abs
    const { parentId: _dropped, ...rest } = n
    return host ? { ...rest, parentId: host.id, position } : { ...rest, position }
  })
  return changed ? parentsFirst(out) : nodes
}

/**
 * Sets free the members of frames about to go. Deleting a frame deletes the frame — not the
 * blocks somebody drew inside it — and a member whose parent is gone would be positioned
 * relative to nothing, so it takes its absolute place before the parent disappears.
 */
function orphan(nodes: AppNode[], removed: Set<string>): AppNode[] {
  if (!nodes.some((n) => n.parentId && removed.has(n.parentId) && !removed.has(n.id))) return nodes
  return nodes.map((n) => {
    if (!n.parentId || !removed.has(n.parentId) || removed.has(n.id)) return n
    const { parentId: _gone, ...rest } = n
    return { ...rest, position: absolutePosition(n, nodes) }
  })
}

/** Cap on retained console events; the backend keeps the authoritative buffer. */
const MAX_RUN_EVENTS = 4000

/** The field each node kind uses as its human-facing identifier, if it has one. */
function nameKey(kind: NodeKind): 'name' | 'label' | null {
  if (kind === 'agent' || kind === 'coordinator' || kind === 'mcp' || kind === 'merge' || kind === 'verifier') return 'name'
  if (kind === 'sql' || kind === 'knowledge' || kind === 'flow' || kind === 'mail') return 'label'
  return null
}

/** The node's human-facing name, when its kind has one. */
function nameOf(data: AppNodeData): string | null {
  const key = nameKey(data.kind)
  return key ? (data as unknown as Record<string, string>)[key] : null
}

function uniqueName(base: string, taken: Set<string>): string {
  if (!taken.has(base)) return base
  let n = 2
  while (taken.has(`${base} ${n}`)) n += 1
  return `${base} ${n}`
}

/**
 * Deep-copies node data for a clone, fixing up the fields that must NOT be shared
 * with the original: the compiler rejects a flow with more than one coordinator, a
 * webhook secret is a per-node credential, and agents are delegated to by name so
 * duplicate names would be ambiguous.
 */
function cloneData(data: AppNodeData, taken: Set<string>): AppNodeData {
  const copy = structuredClone(data)
  // A flow has one lead: the copy of a coordinator is an agent, and the canvas node's type
  // follows the data (insertClones reads it back from here).
  if (copy.kind === 'coordinator') (copy as { kind: string }).kind = 'agent'
  // The copy is a separate webhook endpoint needing its own provider-issued secret.
  if (copy.kind === 'input') copy.secret = ''

  const key = nameKey(copy.kind)
  if (key) {
    const record = copy as unknown as Record<string, string>
    record[key] = uniqueName(`${record[key]} copy`, taken)
    taken.add(record[key])
  }
  return copy
}

type Clipboard = { nodes: AppNode[]; edges: Edge[] }

/** One undo step: the whole drawing. Snapshots hold references — every mutation in this store
 * replaces objects rather than editing them, so old arrays stay intact for free. */
type Snapshot = { nodes: AppNode[]; edges: Edge[] }

/** Undo depth. Beyond this the oldest step falls off; nobody undoes a hundred times on purpose. */
const UNDO_CAP = 100

/**
 * Coalescing memo for checkpoints, module-level like the exec signature: it is bookkeeping about
 * the LAST call, not state anything renders. Typing in an inspector fires updateNodeData per
 * keystroke, and an undo that removes one letter at a time is worse than none — checkpoints with
 * the same key inside the window collapse into the first one, and the window slides while the
 * typing continues, so one pause equals one undo step.
 */
let lastCheckpoint: { key: string | null; at: number } = { key: null, at: 0 }
const COALESCE_MS = 800

/** Nodes the user is acting on: the multi-selection if there is one, else the inspected node. */
function targetNodes(s: FlowState): AppNode[] {
  const selected = s.nodes.filter((n) => n.selected)
  if (selected.length) return selected
  const inspected = s.nodes.find((n) => n.id === s.selectedId)
  return inspected ? [inspected] : []
}

/**
 * The self-contained block `picked` forms: the nodes plus only the edges with BOTH endpoints inside
 * the set, since an edge reaching outside it would dangle once the block is cloned.
 *
 * A frame brings its members: duplicating an empty rectangle is never what picking a frame
 * meant. A member picked without its frame leaves it, and is written down at its absolute place
 * so the clipboard stands on its own — it may be pasted into a flow where that frame never existed.
 */
function blockOf(picked: AppNode[], all: AppNode[], edges: Edge[]): Clipboard {
  const pickedIds = new Set(picked.map((n) => n.id))
  const members = parentsFirst([
    ...picked,
    ...all.filter((n) => n.parentId && pickedIds.has(n.parentId) && !pickedIds.has(n.id)),
  ])
  const ids = new Set(members.map((n) => n.id))
  const nodes = members.map((n) => {
    if (!n.parentId || ids.has(n.parentId)) return n
    const { parentId: _left, ...rest } = n
    return { ...rest, position: absolutePosition(n, all) }
  })
  return { nodes, edges: edges.filter((e) => ids.has(e.source) && ids.has(e.target)) }
}

/**
 * Inserts clones of `src` into the flow, offset by `offset`, remapping ids so the
 * copies wire up among themselves. Edges are carried over only when BOTH endpoints
 * were part of the copied set — a dangling half-edge would point at the original.
 */
function insertClones(s: FlowState, src: Clipboard, offset: number) {
  const taken = new Set<string>()
  for (const n of s.nodes) {
    const name = nameOf(n.data)
    if (name) taken.add(name)
  }

  const idMap = new Map<string, string>()
  for (const n of src.nodes) idMap.set(n.id, uid(n.data.kind))
  const nodes: AppNode[] = src.nodes.map((n) => {
    const data = cloneData(n.data, taken)
    // A member of a copied frame keeps its place INSIDE the copy: its position is relative to
    // the frame, and the frame is the one that moves by the offset.
    const parentId = n.parentId ? idMap.get(n.parentId) : undefined
    return {
      ...n,
      id: idMap.get(n.id) as string,
      type: data.kind,
      selected: true,
      parentId,
      position: parentId ? n.position : { x: n.position.x + offset, y: n.position.y + offset },
      data,
    }
  })

  const edges: Edge[] = src.edges
    .filter((e) => idMap.has(e.source) && idMap.has(e.target))
    .map((e) => ({
      ...e,
      id: uid('e'),
      source: idMap.get(e.source) as string,
      target: idMap.get(e.target) as string,
    }))

  return {
    // Deselect the originals so the new copies are what's dragged/acted on next.
    nodes: [...s.nodes.map((n) => (n.selected ? { ...n, selected: false } : n)), ...nodes],
    edges: [...s.edges, ...edges],
    selectedId: nodes[0]?.id ?? s.selectedId,
  }
}

function defaultData(kind: NodeKind): AppNodeData {
  switch (kind) {
    case 'agent':
    case 'coordinator':
      return {
        kind,
        name: kind === 'coordinator' ? 'Coordinator' : 'Agent',
        // Independent workers is the execution with the verifier, the merge and the facades
        // behind it, so a new flow starts there; flows saved before keep whatever they chose.
        ...(kind === 'coordinator' ? { execution: 'fanout' as const } : {}),
        model: DEFAULT_MODEL,
        description: '',
        systemPrompt: '',
        maxTokens: DEFAULT_MAX_TOKENS,
        effort: 'high',
        contextFolders: [],
        claudeMdPath: '',
      }
    case 'mcp':
      return { kind: 'mcp', name: 'github', url: 'https://api.githubcopilot.com/mcp/', credentialId: '' }
    case 'repo':
      return { kind: 'repo', provider: 'github', url: '', credentialId: '', mountPath: '', branch: '' }
    case 'knowledge':
      return { kind: 'knowledge', label: 'knowledge', baseId: '', topK: 5 }
    case 'api':
      return { kind: 'api', label: 'api', specUrl: '', ops: [] }
    case 'flow':
      // Waits by default, because that is what a caller almost always wants: the child's answer
      // IS the reason for calling it. Where it is wired decides whether it runs before or after.
      return { kind: 'flow', label: 'flow', flowId: '', waitForResult: true }
    case 'mail':
      // STARTTLS on 587 is what nearly every provider documents, so it is what an untouched node
      // does. The subject names the flow and how it went — the two things a subject line is for.
      return {
        kind: 'mail',
        label: 'mail',
        to: '',
        subject: '{{flow}}: {{status}}',
        smtpHost: '',
        smtpPort: 587,
        smtpStarttls: true,
        smtpUsername: '',
        from: '',
        credentialId: '',
      }
    case 'sql':
      return {
        kind: 'sql',
        label: 'db',
        jdbcUrl: 'jdbc:postgresql://localhost:5432/postgres',
        username: 'postgres',
        credentialId: '',
        query: 'SELECT * FROM my_table LIMIT 20',
        maxRows: 50,
      }
    case 'condition':
      // "Is there an answer at all" by default: the check people reach for first, and the one a
      // gate left unconfigured should perform rather than waving everything through.
      return { kind: 'condition', label: 'if', test: 'not_empty', value: '', caseSensitive: false }
    case 'foreach':
      return { kind: 'foreach', label: 'for each', source: 'lines', limit: 25 }
    case 'merge':
      return {
        kind: 'merge',
        name: 'Merge',
        model: DEFAULT_MODEL,
        systemPrompt: '',
        maxTokens: DEFAULT_MAX_TOKENS,
        effort: 'high',
      }
    case 'verifier':
      return {
        kind: 'verifier',
        name: 'Verifier',
        model: DEFAULT_MODEL,
        systemPrompt: '',
        maxTokens: DEFAULT_MAX_TOKENS,
        effort: 'high',
      }
    case 'input':
      return {
        kind: 'input',
        mode: 'manual',
        prompt: '',
        cron: '0 9 * * *',
        // Filled in by pasting the provider's signing secret (they generate it, not us).
        secret: '',
        authParam: 'Linear-Signature',
      }
    case 'note':
      return { kind: 'note', text: '', color: 'yellow' }
    case 'group':
      return { kind: 'group', label: 'Group', color: 'blue' }
  }
}

/** Flow-level metadata edited from the Flows dashboard; carried through canvas saves. */
type FlowMeta = {
  enabled?: boolean
  tags?: string[]
  favorite?: boolean
  notifyWebhook?: string
  budgetUsd?: number | null
  /** {{NAME}} values for this flow — must survive a canvas save or the flow forgets them. */
  variables?: Record<string, string>
  /** Dashboard folder — same deal: a canvas save must not silently move the flow to the root. */
  folder?: string
  /** The group the flow is visible to. Echoed on save; changed only through the assign endpoint. */
  groupId?: string | null
}

interface FlowState {
  flowId: string | null
  name: string
  flowMeta: FlowMeta
  nodes: AppNode[]
  edges: Edge[]
  selectedId: string | null
  /**
   * Whether the selected node's properties are open in a dialog rather than only in the side
   * panel. Kept here rather than in the canvas because two components need it — the canvas opens
   * it on double-click, App renders it — and it belongs to the selection either way.
   *
   * Never forced back to false when the selection changes: the dialog is only rendered for a
   * node that exists, so deleting the node it was opened for closes it on its own. One rule in
   * one place beats the same reset repeated in delete, load, and new-flow.
   */
  detailsOpen: boolean

  // Live execution overlay for the currently-inspected run.
  activeRunId: string | null
  runExecByNode: Record<string, NodeExec>
  runTotals: { input: number; output: number; costUsd: number }
  /** Fan-out health for the inspected run; null for runs that never fanned out. */
  runGraph: GraphMetrics | null
  /**
   * What the inspected run's agents did to the repositories, one entry per checkout. Empty for
   * a run that cloned nothing. Read less often than the node state — see useSelectedRun — so
   * it is held apart from the report rather than folded into it.
   */
  runDiffs: RunDiff[]
  setActiveRun: (id: string | null) => void
  setRunExec: (report: NodeExecReport | null) => void
  setRunDiffs: (diffs: RunDiff[]) => void
  /**
   * Live console events for the active run. Held here rather than inside Console so a node's
   * inspector can show that one agent's lines without opening a second socket.
   */
  runEvents: RunEvent[]
  addRunEvent: (e: RunEvent) => void
  clearRunEvents: () => void

  // Undo / redo over the drawing (nodes + edges). Deleting a block with Delete was irreversible
  // short of reloading without saving — the most visible absence next to any editor people know.
  past: Snapshot[]
  future: Snapshot[]
  /**
   * Pushes the current drawing onto the undo stack. Every mutation calls it BEFORE changing
   * anything; `coalesceKey` lets a burst of the same edit (typing, a two-part delete) count as
   * one step.
   */
  checkpoint: (coalesceKey?: string) => void
  undo: () => void
  redo: () => void
  /** dagre over the chain, capabilities hung under their agents — behind an undo checkpoint. */
  autoLayout: () => void
  /** True for the moment after autoLayout, so the canvas can animate nodes to their new places. */
  layoutAnimating: boolean

  /**
   * The replay overlay: where the selected run's path would diverge against the flow as saved
   * today. Held here for the same reason the exec overlay is — the canvas paints it, the runs
   * panel sets it, and neither should know the other exists.
   */
  replay: ReplayReport | null
  setReplay: (report: ReplayReport | null) => void

  onNodesChange: (changes: NodeChange<AppNode>[]) => void
  onEdgesChange: (changes: EdgeChange[]) => void
  onConnect: (conn: Connection) => void
  deleteEdge: (id: string) => void
  /** `at` is a flow-space position, from a palette drag; omitted, the node cascades. */
  addNode: (kind: NodeKind, at?: { x: number; y: number }) => void
  /**
   * Called when a drag ends, with the nodes that moved: the ones now inside a frame join it,
   * the ones dragged out leave it. Part of the drag's own undo step — the checkpoint was taken
   * when the drag began, and nobody wants "undo" to first un-frame and only then un-move.
   */
  settleDrop: (ids: string[]) => void
  updateNodeData: (id: string, patch: Record<string, unknown>) => void
  deleteNode: (id: string) => void
  selectNode: (id: string | null) => void
  openNodeDetails: () => void
  closeNodeDetails: () => void
  /**
   * A block the canvas should bring into view. Set by the command palette, which has no React
   * Flow instance and should not need one; the canvas answers it — centring the viewport on the
   * block — and clears it. Held here so a request made while the Studio is not on screen is
   * answered the moment it is.
   */
  focusNodeId: string | null
  requestFocus: (id: string) => void
  clearFocus: () => void

  // Copy / paste / duplicate of canvas blocks.
  clipboard: Clipboard | null
  copySelection: () => number
  paste: () => void
  duplicateSelection: () => void
  duplicateNode: (id: string) => void
  setName: (name: string) => void

  newFlow: () => void
  loadBackendFlow: (flow: BackendFlow) => void
  toBackendFlow: () => BackendFlow

  /**
   * The historical revision currently on the canvas, or null when it shows the saved flow. Set
   * only by the Versions tab's Preview, and cleared by every other load (opening a flow, a run,
   * a new flow) — so "Back to latest" appears exactly while the canvas is showing the past.
   *
   * A preview is not read-only: the canvas is the editor, and the nodes are editable as always.
   * What it does not do is save — restoring is the explicit action next to it.
   */
  previewVersion: number | null
  setPreviewVersion: (version: number | null) => void
}

// Module-level rather than store state: it is a memo of the last poll, not something any
// component should render or subscribe to.
let lastRunExecSignature: string | null = null

/**
 * The run overlay with nothing in it — the shape three places have to agree on (initial state,
 * switching run, clearing). Fresh objects every call on purpose: canvas badges and the console's
 * token bar select these by reference (see setRunExec).
 */
function emptyOverlay(): Pick<FlowState, 'runExecByNode' | 'runTotals' | 'runGraph' | 'runDiffs'> {
  return {
    runExecByNode: {},
    runTotals: { input: 0, output: 0, costUsd: 0 },
    runGraph: null,
    runDiffs: [],
  }
}

/** Whether two reads of a run's diffs say the same thing — the array is fresh on every fetch. */
function sameDiffs(a: RunDiff[], b: RunDiff[]): boolean {
  if (a.length !== b.length) return false
  return a.every((x, i) => {
    const y = b[i]
    return x.nodeId === y.nodeId && x.folder === y.folder && x.takenAt === y.takenAt
      && (x.patch ?? null) === (y.patch ?? null) && (x.note ?? null) === (y.note ?? null)
  })
}

export const useFlowStore = create<FlowState>((set, get) => ({
  flowId: null,
  name: 'Untitled flow',
  flowMeta: {},
  nodes: [],
  edges: [],
  selectedId: null,
  detailsOpen: false,

  activeRunId: null,
  ...emptyOverlay(),
  runEvents: [],
  // Bounded so a long-running flow can't grow this array without limit; the backend keeps the
  // authoritative buffer and replays it on reconnect.
  addRunEvent: (e) =>
    set((s) => {
      const next = [...s.runEvents, e]
      const overflow = next.length - MAX_RUN_EVENTS
      return { runEvents: overflow > 0 ? next.slice(overflow) : next }
    }),
  clearRunEvents: () => set({ runEvents: [] }),

  setActiveRun: (id) =>
    set((s) => {
      // Re-setting the id already active must not wipe the overlay just built for that run.
      if (s.activeRunId === id) return {}
      lastRunExecSignature = null
      return { activeRunId: id, ...emptyOverlay(), runEvents: [] }
    }),
  setRunExec: (report) => {
    if (!report) {
      lastRunExecSignature = null
      set(emptyOverlay())
      return
    }
    // Bail out when the poll brought back the same state. Every node badge on the canvas and the
    // console's token bar select these objects by reference, so unconditionally minting fresh ones
    // re-rendered all of them at each poll — including on finished runs, whose payload is
    // byte-identical every time. The signature is cheap and JSON-stable per node. The verdict is
    // part of it because it lands AFTER a worker's endedAt — without it, the poll that carries
    // the verifier's judgment would be dismissed as "no change".
    const g = report.graph
    const signature = report.nodes
      .map((n) => `${n.nodeId}|${n.status}|${n.inputTokens}|${n.outputTokens}|${n.contextTokens ?? 0}|${n.endedAt ?? ''}|${n.verdict ?? ''}`)
      .join(';') + `#${report.totalInputTokens}|${report.totalOutputTokens}|${report.totalCostUsd ?? 0}`
      + `#${g ? `${g.workers}|${g.workersFailed}|${g.workersRejected}|${g.retries}|${g.verdicts}|${g.wallMs}` : ''}`
    if (signature === lastRunExecSignature) return
    lastRunExecSignature = signature

    const byNode: Record<string, NodeExec> = {}
    for (const n of report.nodes) byNode[n.nodeId] = n
    set({
      runExecByNode: byNode,
      runTotals: {
        input: report.totalInputTokens,
        output: report.totalOutputTokens,
        costUsd: report.totalCostUsd ?? 0,
      },
      runGraph: report.graph ?? null,
    })
  },
  // Skipped when nothing changed, for the same reason setRunExec bails out: the inspector and
  // the console both select this array, and a finished run answers byte-identically every time.
  setRunDiffs: (diffs) => set((s) => (sameDiffs(s.runDiffs, diffs) ? {} : { runDiffs: diffs })),

  past: [],
  future: [],
  layoutAnimating: false,

  checkpoint: (coalesceKey) =>
    set((s) => {
      const now = Date.now()
      if (coalesceKey && lastCheckpoint.key === coalesceKey && now - lastCheckpoint.at < COALESCE_MS) {
        lastCheckpoint = { key: coalesceKey, at: now }
        return {}
      }
      lastCheckpoint = { key: coalesceKey ?? null, at: now }
      const past = [...s.past, { nodes: s.nodes, edges: s.edges }]
      return { past: past.length > UNDO_CAP ? past.slice(1) : past, future: [] }
    }),

  undo: () =>
    set((s) => {
      const previous = s.past[s.past.length - 1]
      if (!previous) return {}
      // The step after an undo is never part of the burst that preceded it.
      lastCheckpoint = { key: null, at: 0 }
      return {
        past: s.past.slice(0, -1),
        future: [...s.future, { nodes: s.nodes, edges: s.edges }],
        nodes: previous.nodes,
        edges: previous.edges,
        // The node it pointed at may be back, or gone; pointing at nothing is always safe.
        selectedId: null,
      }
    }),

  redo: () =>
    set((s) => {
      const next = s.future[s.future.length - 1]
      if (!next) return {}
      lastCheckpoint = { key: null, at: 0 }
      return {
        future: s.future.slice(0, -1),
        past: [...s.past, { nodes: s.nodes, edges: s.edges }],
        nodes: next.nodes,
        edges: next.edges,
        selectedId: null,
      }
    }),

  autoLayout: () => {
    const s = get()
    if (s.nodes.length < 2) return
    const byId = new Map(s.nodes.map((n) => [n.id, n]))
    const placed = new Map<string, { x: number; y: number }>()
    const resized = new Map<string, { w: number; h: number }>()

    // Inside each frame first. A frame is a grouping the author drew on purpose, so its members
    // are tidied against the frame's own origin — never scattered across the canvas — and the
    // frame then grows or shrinks to fit what it holds, label band included.
    for (const g of s.nodes) {
      if (g.data.kind !== 'group') continue
      const members = s.nodes.filter((n) => n.parentId === g.id && !isAnnotation(n.data.kind))
      if (members.length === 0) continue
      const inner = members.length > 1 ? tidyLayout(members, s.edges) : new Map([[members[0].id, { x: 0, y: 0 }]])
      let minX = Infinity
      let minY = Infinity
      for (const m of members) {
        const at = inner.get(m.id) ?? m.position
        minX = Math.min(minX, at.x)
        minY = Math.min(minY, at.y)
      }
      let w = 0
      let h = 0
      for (const m of members) {
        const at = inner.get(m.id) ?? m.position
        const p = { x: at.x - minX + FRAME_PAD, y: at.y - minY + FRAME_TOP }
        placed.set(m.id, p)
        const size = sizeOf(m)
        w = Math.max(w, p.x + size.w)
        h = Math.max(h, p.y + size.h)
      }
      resized.set(g.id, { w: w + FRAME_PAD, h: h + FRAME_PAD })
    }

    // Then the top level, frames included as the blocks they are: a wire into a member ranks
    // its frame, so a frame lands downstream of what feeds anything inside it. Notes stay put —
    // they have no wires for dagre to rank them by.
    const topOf = (id: string) => byId.get(id)?.parentId ?? id
    const outer = tidyLayout(
      s.nodes
        .filter((n) => !n.parentId && n.data.kind !== 'note')
        .map((n) => {
          const size = resized.get(n.id)
          return size ? { ...n, measured: { width: size.w, height: size.h } } : n
        }),
      s.edges
        .map((e) => ({ source: topOf(e.source), target: topOf(e.target) }))
        .filter((e) => e.source !== e.target),
    )
    for (const [id, at] of outer) placed.set(id, at)
    if (placed.size === 0 && resized.size === 0) return
    s.checkpoint()
    set({
      nodes: s.nodes.map((n) => {
        const at = placed.get(n.id)
        const size = resized.get(n.id)
        if (!at && !size) return n
        return { ...n, ...(at ? { position: at } : {}), ...(size ? { width: size.w, height: size.h } : {}) }
      }),
      layoutAnimating: true,
    })
    // Long enough for the CSS transition, short enough that the next drag is never animated.
    setTimeout(() => set({ layoutAnimating: false }), 400)
  },

  replay: null,
  setReplay: (report) => set({ replay: report }),

  onNodesChange: (changes) => {
    // Delete arrives here as a 'remove' change (the canvas's Delete key), not through
    // deleteNode — without a checkpoint the most destructive gesture would be the one
    // that cannot be undone. Node and edge removals of one Delete coalesce into one step.
    if (changes.some((c) => c.type === 'remove')) get().checkpoint('remove')
    set((s) => {
      const removed = new Set(changes.filter((c) => c.type === 'remove').map((c) => c.id))
      return {
        nodes: applyNodeChanges(changes, removed.size ? orphan(s.nodes, removed) : s.nodes),
        ...(s.selectedId && removed.has(s.selectedId) ? { selectedId: null, detailsOpen: false } : {}),
      }
    })
  },
  onEdgesChange: (changes) => {
    if (changes.some((c) => c.type === 'remove')) get().checkpoint('remove')
    set((s) => ({ edges: applyEdgeChanges(changes, s.edges) }))
  },
  onConnect: (conn) => {
    const s = get()
    // A wire that means nothing is not drawn. The canvas already greys the handle out, but a
    // programmatic connect (paste, a generated flow) reaches here without ever touching a handle.
    const kindOf = (id: string | null) => s.nodes.find((n) => n.id === id)?.data.kind
    const source = kindOf(conn.source)
    const target = kindOf(conn.target)
    if (source && target && !canConnect(source, target)) return
    s.checkpoint()
    set({ edges: addEdge({ ...conn, id: uid('e') }, s.edges) })
  },
  deleteEdge: (id) => {
    get().checkpoint()
    set((s) => ({ edges: s.edges.filter((e) => e.id !== id) }))
  },

  addNode: (kind, at) => {
    // Exactly one lead per flow. The palette disables its button; this is the same rule for a
    // drop, a shortcut, or anything else that reaches the store.
    if (kind === 'coordinator' && get().nodes.some((n) => n.data.kind === 'coordinator')) return
    get().checkpoint()
    set((s) => {
      // Who this box would obviously attach to: the most recent one the canvas would let it
      // connect to. Most recent rather than any, because building a flow is building a chain and
      // the last box drawn is the one still in mind. Ambiguity is not a problem to solve here —
      // every wire this draws is one the canvas would have accepted, and an edge drawn for you
      // takes one click to remove, while an edge nobody drew has to be discovered.
      const feeds = wiringRoleOf(kind) === 'feeds'
      const partner = [...s.nodes]
        .reverse()
        .find((n) => (feeds ? canConnect(kind, n.data.kind) : canConnect(n.data.kind, kind)))

      // A dropped node has a position the user chose; nudging it would move it out from under
      // their cursor. Everything else is placed so the drawing reads: the chain runs left to
      // right, and the capabilities an agent is given hang underneath it.
      let position = at
      if (!position && partner) {
        position = feeds
          ? { x: partner.position.x + attached(s, partner.id) * (NODE_W + 20), y: partner.position.y + 150 }
          : { x: partner.position.x + NODE_W + 90, y: partner.position.y }
      }
      if (!position) {
        const right = s.nodes.reduce((max, n) => Math.max(max, n.position.x), -Infinity)
        position = s.nodes.length
          ? { x: right + NODE_W + 90, y: 120 }
          : { x: 120, y: 120 }
      }

      const node: AppNode = {
        id: uid(kind),
        type: kind,
        position,
        data: defaultData(kind),
        // A frame is sized from birth (the resizer only changes a size that exists) and drawn
        // behind everything: it is a background for blocks, and a background in front of the
        // block it frames is a lid.
        ...(kind === 'group' ? { width: GROUP_W, height: GROUP_H, zIndex: -1 } : {}),
      }
      const edges = partner
        ? addEdge(
            feeds
              ? { id: uid('e'), source: node.id, target: partner.id }
              : { id: uid('e'), source: partner.id, target: node.id },
            s.edges,
          )
        : s.edges
      // A block dropped from the palette onto a frame is inside it from the start, the same as
      // one dragged there afterwards.
      return { nodes: settle(parentsFirst([...s.nodes, node]), [node.id]), edges, selectedId: node.id }
    })
  },

  settleDrop: (ids) =>
    set((s) => {
      const nodes = settle(s.nodes, ids)
      return nodes === s.nodes ? {} : { nodes }
    }),

  updateNodeData: (id, patch) => {
    // Coalesced per node: a burst of keystrokes in one inspector field is one undo step.
    get().checkpoint(`data:${id}`)
    set((s) => ({
      nodes: s.nodes.map((n) =>
        n.id === id ? { ...n, data: { ...n.data, ...patch } as AppNodeData } : n,
      ),
    }))
  },

  deleteNode: (id) => {
    get().checkpoint()
    set((s) => ({
      nodes: orphan(s.nodes, new Set([id])).filter((n) => n.id !== id),
      edges: s.edges.filter((e) => e.source !== id && e.target !== id),
      selectedId: s.selectedId === id ? null : s.selectedId,
      // The dialog follows the selection; a block deleted from its own dialog must not leave the
      // dialog armed to pop open on the next click anywhere on the canvas.
      detailsOpen: s.selectedId === id ? false : s.detailsOpen,
    }))
  },

  clipboard: null,

  copySelection: () => {
    const s = get()
    const picked = targetNodes(s)
    if (!picked.length) return 0
    set({ clipboard: blockOf(picked, s.nodes, s.edges) })
    pasteCount = 0
    return picked.length
  },

  paste: () => {
    if (!get().clipboard?.nodes.length) return
    get().checkpoint()
    set((s) => {
      if (!s.clipboard?.nodes.length) return {}
      pasteCount += 1
      return insertClones(s, s.clipboard, PASTE_OFFSET * pasteCount)
    })
  },

  // Duplicate acts in place and leaves the clipboard alone.
  duplicateSelection: () => {
    if (!targetNodes(get()).length) return
    get().checkpoint()
    set((s) => {
      const picked = targetNodes(s)
      if (!picked.length) return {}
      return insertClones(s, blockOf(picked, s.nodes, s.edges), PASTE_OFFSET)
    })
  },

  duplicateNode: (id) => {
    if (!get().nodes.some((n) => n.id === id)) return
    get().checkpoint()
    set((s) => {
      const node = s.nodes.find((n) => n.id === id)
      // Through blockOf rather than as a lone node: a frame's copy has to bring the members.
      return node ? insertClones(s, blockOf([node], s.nodes, s.edges), PASTE_OFFSET) : {}
    })
  },

  selectNode: (id) => set({ selectedId: id }),
  focusNodeId: null,
  requestFocus: (id) =>
    set((s) => ({
      focusNodeId: id,
      selectedId: id,
      // Selected on the canvas too, not only inspected: the ring is how the eye finds the block
      // the viewport just travelled to.
      nodes: s.nodes.map((n) => (n.selected !== (n.id === id) ? { ...n, selected: n.id === id } : n)),
    })),
  clearFocus: () => set({ focusNodeId: null }),
  openNodeDetails: () => set({ detailsOpen: true }),
  closeNodeDetails: () => set({ detailsOpen: false }),
  setName: (name) => set({ name }),

  previewVersion: null,
  setPreviewVersion: (version) => set({ previewVersion: version }),

  newFlow: () =>
    set({
      flowId: null,
      name: 'Untitled flow',
      flowMeta: {},
      nodes: [],
      edges: [],
      selectedId: null,
      focusNodeId: null,
      previewVersion: null,
      // A new drawing starts a new history: undoing across flows would resurrect the wrong one.
      past: [],
      future: [],
      replay: null,
    }),

  loadBackendFlow: (flow) => {
    const placed = flow.nodes.map((bn) => {
      // The lead is a canvas kind of its own; on the wire it is an agent with a role, and older
      // canvases wrote that role inside the data too. Both readings land on the same block.
      const role = bn.role ?? (bn.data as { role?: unknown })?.role
      const kind: NodeKind = bn.type === 'agent' && role === 'coordinator' ? 'coordinator' : bn.type
      const pos = (bn.data?._pos ?? {}) as { x?: number; y?: number }
      const size = (bn.data?._size ?? null) as { w?: number; h?: number } | null
      const parent = typeof bn.data?._parent === 'string' ? bn.data._parent : null
      const merged = { ...defaultData(kind), ...bn.data, kind } as AppNodeData
      delete (merged as { role?: unknown }).role
      // Frame facts live on the canvas node, never in the data: left there, a member dragged out
      // of its frame would still be saved as inside it.
      delete (merged as { _parent?: unknown })._parent
      delete (merged as { _size?: unknown })._size
      const node: AppNode = {
        id: bn.id,
        type: kind,
        position: { x: pos.x ?? 120, y: pos.y ?? 120 },
        data: merged,
        ...(kind === 'group'
          ? { width: size?.w ?? GROUP_W, height: size?.h ?? GROUP_H, zIndex: -1 }
          : {}),
      }
      return { node, parent }
    })
    // `_pos` is absolute on the wire (see toBackendFlow); inside a frame the canvas wants it
    // relative. A `_parent` naming nothing — the frame was deleted by a client that never knew
    // about frames — is dropped rather than trusted.
    const groups = new Map(
      placed.filter((p) => p.node.data.kind === 'group').map((p) => [p.node.id, p.node]),
    )
    const nodes: AppNode[] = parentsFirst(
      placed.map(({ node, parent }) => {
        const frame = parent ? groups.get(parent) : undefined
        if (!frame || node.data.kind === 'group') return node
        return {
          ...node,
          parentId: frame.id,
          position: { x: node.position.x - frame.position.x, y: node.position.y - frame.position.y },
        }
      }),
    )
    set({
      flowId: flow.id ?? null,
      name: flow.name,
      flowMeta: {
        enabled: flow.enabled,
        tags: flow.tags,
        favorite: flow.favorite,
        notifyWebhook: flow.notifyWebhook,
        budgetUsd: flow.budgetUsd,
        variables: flow.variables,
        folder: flow.folder,
        groupId: flow.groupId,
      },
      nodes,
      edges: flow.edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        sourceHandle: e.sourceHandle ?? null,
      })),
      // The selection survives a reload of the flow it points into — a Save answering after
      // the person has already double-clicked a block must not close the inspector under
      // them — and clears when the canvas moves to a flow that has no such block.
      selectedId: nodes.some((n) => n.id === get().selectedId) ? get().selectedId : null,
      focusNodeId: null,
      // Any load lands on the saved flow unless the caller says otherwise; Preview re-sets this
      // right after. Clearing it here means no path can leave the banner claiming a revision the
      // canvas no longer shows.
      previewVersion: null,
      past: [],
      future: [],
      replay: null,
    })
  },

  toBackendFlow: () => {
    const s = get()
    const nodes: BackendFlowNode[] = s.nodes.map((n) => {
      const { kind, ...rest } = n.data
      // One wire shape for both agent kinds. The role also travels inside the data, where the
      // trigger's permission-mode lookup and the sandbox twin read it.
      const role = kind === 'coordinator' ? 'coordinator' : kind === 'agent' ? 'subagent' : null
      // `_pos` is ABSOLUTE even for a framed node. The backend, the MCP flow tools and every
      // client older than frames read `_pos` and nothing else; a relative one would land those
      // nodes in a heap at the frame's origin. `_parent` and `_size` are extra facts for a
      // client that knows them, not a change to the one everybody reads.
      const { x, y } = absolutePosition(n, s.nodes)
      return {
        id: n.id,
        type: kind === 'coordinator' ? 'agent' : kind,
        role,
        data: {
          ...rest,
          ...(role ? { role } : {}),
          _pos: { x, y },
          ...(n.parentId ? { _parent: n.parentId } : {}),
          ...(n.width && n.height ? { _size: { w: n.width, h: n.height } } : {}),
        },
      }
    })
    return {
      id: s.flowId ?? undefined,
      name: s.name,
      ...s.flowMeta, // keep tags / favourite / enabled / webhook across canvas saves
      nodes,
      // sourceHandle travels with the wire: a block has two outputs now, and an edge that
      // forgets which one it left from is an error path that behaves like a success path.
      edges: s.edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        sourceHandle: e.sourceHandle ?? null,
      })),
    }
  },
}))
