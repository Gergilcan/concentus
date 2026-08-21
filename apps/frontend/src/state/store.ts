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
  RunEvent,
} from '../api/types.ts'
import { DEFAULT_MAX_TOKENS, DEFAULT_MODEL } from '../constants.ts'
import { canConnect, wiringRoleOf } from '../flow/wiring.ts'

export type AppNode = Node<AppNodeData>

/** Successive pastes of the same clipboard cascade instead of stacking. */
let pasteCount = 0

function uid(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().slice(0, 8)}`
}

/** Canvas offset applied to each successive paste so copies never land on the original. */
/** A node card's width, from nodes.module.scss. Placement has to know it to leave a real gap. */
const NODE_W = 214

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

const PASTE_OFFSET = 40

/** Cap on retained console events; the backend keeps the authoritative buffer. */
const MAX_RUN_EVENTS = 4000

/** The field each node kind uses as its human-facing identifier, if it has one. */
function nameKey(kind: NodeKind): 'name' | 'label' | null {
  if (kind === 'agent' || kind === 'mcp' || kind === 'merge' || kind === 'verifier') return 'name'
  if (kind === 'sql' || kind === 'knowledge' || kind === 'flow') return 'label'
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
  if (copy.kind === 'agent' && copy.role === 'coordinator') copy.role = 'subagent'
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

/** Nodes the user is acting on: the multi-selection if there is one, else the inspected node. */
function targetNodes(s: FlowState): AppNode[] {
  const selected = s.nodes.filter((n) => n.selected)
  if (selected.length) return selected
  const inspected = s.nodes.find((n) => n.id === s.selectedId)
  return inspected ? [inspected] : []
}

/**
 * The self-contained block `nodes` form: the nodes plus only the edges with BOTH endpoints inside
 * the set, since an edge reaching outside it would dangle once the block is cloned.
 */
function blockOf(nodes: AppNode[], edges: Edge[]): Clipboard {
  const ids = new Set(nodes.map((n) => n.id))
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
  const nodes: AppNode[] = src.nodes.map((n) => {
    const id = uid(n.data.kind)
    idMap.set(n.id, id)
    return {
      ...n,
      id,
      selected: true,
      position: { x: n.position.x + offset, y: n.position.y + offset },
      data: cloneData(n.data, taken),
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

function defaultData(kind: NodeKind, isFirstAgent: boolean): AppNodeData {
  switch (kind) {
    case 'agent':
      return {
        kind: 'agent',
        name: isFirstAgent ? 'Coordinator' : 'Sub-agent',
        role: isFirstAgent ? 'coordinator' : 'subagent',
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
}

interface FlowState {
  flowId: string | null
  name: string
  mode: 'managed' | 'local'
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
  setActiveRun: (id: string | null) => void
  setRunExec: (report: NodeExecReport | null) => void
  /**
   * Live console events for the active run. Held here rather than inside Console so a node's
   * inspector can show that one agent's lines without opening a second socket.
   */
  runEvents: RunEvent[]
  addRunEvent: (e: RunEvent) => void
  clearRunEvents: () => void

  onNodesChange: (changes: NodeChange<AppNode>[]) => void
  onEdgesChange: (changes: EdgeChange[]) => void
  onConnect: (conn: Connection) => void
  deleteEdge: (id: string) => void
  /** `at` is a flow-space position, from a palette drag; omitted, the node cascades. */
  addNode: (kind: NodeKind, at?: { x: number; y: number }) => void
  updateNodeData: (id: string, patch: Record<string, unknown>) => void
  deleteNode: (id: string) => void
  selectNode: (id: string | null) => void
  openNodeDetails: () => void
  closeNodeDetails: () => void

  // Copy / paste / duplicate of canvas blocks.
  clipboard: Clipboard | null
  copySelection: () => number
  paste: () => void
  duplicateSelection: () => void
  duplicateNode: (id: string) => void
  setName: (name: string) => void
  setMode: (mode: 'managed' | 'local') => void

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
function emptyOverlay(): Pick<FlowState, 'runExecByNode' | 'runTotals' | 'runGraph'> {
  return { runExecByNode: {}, runTotals: { input: 0, output: 0, costUsd: 0 }, runGraph: null }
}

export const useFlowStore = create<FlowState>((set, get) => ({
  flowId: null,
  name: 'Untitled flow',
  mode: 'managed',
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

  onNodesChange: (changes) => set((s) => ({ nodes: applyNodeChanges(changes, s.nodes) })),
  onEdgesChange: (changes) => set((s) => ({ edges: applyEdgeChanges(changes, s.edges) })),
  onConnect: (conn) =>
    set((s) => {
      // A wire that means nothing is not drawn. The canvas already greys the handle out, but a
      // programmatic connect (paste, a generated flow) reaches here without ever touching a handle.
      const kindOf = (id: string | null) => s.nodes.find((n) => n.id === id)?.data.kind
      const source = kindOf(conn.source)
      const target = kindOf(conn.target)
      if (source && target && !canConnect(source, target)) return {}
      return { edges: addEdge({ ...conn, id: uid('e') }, s.edges) }
    }),
  deleteEdge: (id) => set((s) => ({ edges: s.edges.filter((e) => e.id !== id) })),

  addNode: (kind, at) =>
    set((s) => {
      const isFirstAgent = kind === 'agent' && !s.nodes.some((n) => n.data.kind === 'agent')

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
        data: defaultData(kind, isFirstAgent),
      }
      const edges = partner
        ? addEdge(
            feeds
              ? { id: uid('e'), source: node.id, target: partner.id }
              : { id: uid('e'), source: partner.id, target: node.id },
            s.edges,
          )
        : s.edges
      return { nodes: [...s.nodes, node], edges, selectedId: node.id }
    }),

  updateNodeData: (id, patch) =>
    set((s) => ({
      nodes: s.nodes.map((n) =>
        n.id === id ? { ...n, data: { ...n.data, ...patch } as AppNodeData } : n,
      ),
    })),

  deleteNode: (id) =>
    set((s) => ({
      nodes: s.nodes.filter((n) => n.id !== id),
      edges: s.edges.filter((e) => e.source !== id && e.target !== id),
      selectedId: s.selectedId === id ? null : s.selectedId,
    })),

  clipboard: null,

  copySelection: () => {
    const s = get()
    const picked = targetNodes(s)
    if (!picked.length) return 0
    set({ clipboard: blockOf(picked, s.edges) })
    pasteCount = 0
    return picked.length
  },

  paste: () =>
    set((s) => {
      if (!s.clipboard?.nodes.length) return {}
      pasteCount += 1
      return insertClones(s, s.clipboard, PASTE_OFFSET * pasteCount)
    }),

  // Duplicate acts in place and leaves the clipboard alone.
  duplicateSelection: () =>
    set((s) => {
      const picked = targetNodes(s)
      if (!picked.length) return {}
      return insertClones(s, blockOf(picked, s.edges), PASTE_OFFSET)
    }),

  duplicateNode: (id) =>
    set((s) => {
      const node = s.nodes.find((n) => n.id === id)
      return node ? insertClones(s, { nodes: [node], edges: [] }, PASTE_OFFSET) : {}
    }),

  selectNode: (id) => set({ selectedId: id }),
  openNodeDetails: () => set({ detailsOpen: true }),
  closeNodeDetails: () => set({ detailsOpen: false }),
  setName: (name) => set({ name }),
  setMode: (mode) => set({ mode }),

  previewVersion: null,
  setPreviewVersion: (version) => set({ previewVersion: version }),

  newFlow: () =>
    set({
      flowId: null,
      name: 'Untitled flow',
      mode: 'managed',
      flowMeta: {},
      nodes: [],
      edges: [],
      selectedId: null,
      previewVersion: null,
    }),

  loadBackendFlow: (flow) => {
    const nodes: AppNode[] = flow.nodes.map((bn) => {
      const kind = bn.type
      const pos = (bn.data?._pos ?? {}) as { x?: number; y?: number }
      const merged = { ...defaultData(kind, bn.role === 'coordinator'), ...bn.data, kind } as AppNodeData
      if (kind === 'agent' && bn.role) {
        ;(merged as { role: string }).role = bn.role
      }
      return {
        id: bn.id,
        type: kind,
        position: { x: pos.x ?? 120, y: pos.y ?? 120 },
        data: merged,
      }
    })
    set({
      flowId: flow.id ?? null,
      name: flow.name,
      mode: flow.mode,
      flowMeta: {
        enabled: flow.enabled,
        tags: flow.tags,
        favorite: flow.favorite,
        notifyWebhook: flow.notifyWebhook,
        budgetUsd: flow.budgetUsd,
        variables: flow.variables,
        folder: flow.folder,
      },
      nodes,
      edges: flow.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })),
      selectedId: null,
      // Any load lands on the saved flow unless the caller says otherwise; Preview re-sets this
      // right after. Clearing it here means no path can leave the banner claiming a revision the
      // canvas no longer shows.
      previewVersion: null,
    })
  },

  toBackendFlow: () => {
    const s = get()
    const nodes: BackendFlowNode[] = s.nodes.map((n) => {
      const { kind, ...rest } = n.data
      return {
        id: n.id,
        type: kind,
        role: kind === 'agent' ? (n.data as { role: string }).role : null,
        data: { ...rest, _pos: { x: n.position.x, y: n.position.y } },
      }
    })
    return {
      id: s.flowId ?? undefined,
      name: s.name,
      mode: s.mode,
      ...s.flowMeta, // keep tags / favourite / enabled / webhook across canvas saves
      nodes,
      edges: s.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })),
    }
  },
}))
