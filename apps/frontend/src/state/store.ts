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
  NodeExec,
  NodeExecReport,
  NodeKind,
} from '../api/types.ts'
import { DEFAULT_MAX_TOKENS, DEFAULT_MODEL } from '../constants.ts'

export type AppNode = Node<AppNodeData>

let spawnCount = 0
/** Successive pastes of the same clipboard cascade instead of stacking. */
let pasteCount = 0

function uid(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().slice(0, 8)}`
}

/** Canvas offset applied to each successive paste so copies never land on the original. */
const PASTE_OFFSET = 40

/** The field each node kind uses as its human-facing identifier, if it has one. */
function nameKey(kind: NodeKind): 'name' | 'label' | null {
  if (kind === 'agent' || kind === 'mcp') return 'name'
  if (kind === 'sql') return 'label'
  return null
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
 * Inserts clones of `src` into the flow, offset by `offset`, remapping ids so the
 * copies wire up among themselves. Edges are carried over only when BOTH endpoints
 * were part of the copied set — a dangling half-edge would point at the original.
 */
function insertClones(s: FlowState, src: Clipboard, offset: number) {
  const taken = new Set<string>()
  for (const n of s.nodes) {
    const key = nameKey(n.data.kind)
    if (key) taken.add((n.data as unknown as Record<string, string>)[key])
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
      return { kind: 'mcp', name: 'github', url: 'https://api.githubcopilot.com/mcp/', tokenEnv: 'GITHUB_MCP_TOKEN' }
    case 'repo':
      return { kind: 'repo', provider: 'github', url: '', tokenEnv: 'GITHUB_TOKEN', mountPath: '', branch: '' }
    case 'sql':
      return {
        kind: 'sql',
        label: 'db',
        jdbcUrl: 'jdbc:postgresql://localhost:5432/postgres',
        username: 'postgres',
        passwordEnv: 'PGPASSWORD',
        query: 'SELECT * FROM my_table LIMIT 20',
        maxRows: 50,
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
}

interface FlowState {
  flowId: string | null
  name: string
  mode: 'managed' | 'local'
  flowMeta: FlowMeta
  nodes: AppNode[]
  edges: Edge[]
  selectedId: string | null

  // Live execution overlay for the currently-inspected run.
  activeRunId: string | null
  runExecByNode: Record<string, NodeExec>
  runTotals: { input: number; output: number }
  setActiveRun: (id: string | null) => void
  setRunExec: (report: NodeExecReport | null) => void

  onNodesChange: (changes: NodeChange<AppNode>[]) => void
  onEdgesChange: (changes: EdgeChange[]) => void
  onConnect: (conn: Connection) => void
  deleteEdge: (id: string) => void
  addNode: (kind: NodeKind) => void
  updateNodeData: (id: string, patch: Record<string, unknown>) => void
  deleteNode: (id: string) => void
  selectNode: (id: string | null) => void

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
}

export const useFlowStore = create<FlowState>((set, get) => ({
  flowId: null,
  name: 'Untitled flow',
  mode: 'managed',
  flowMeta: {},
  nodes: [],
  edges: [],
  selectedId: null,

  activeRunId: null,
  runExecByNode: {},
  runTotals: { input: 0, output: 0 },
  setActiveRun: (id) =>
    set((s) => (s.activeRunId === id ? {} : { activeRunId: id, runExecByNode: {}, runTotals: { input: 0, output: 0 } })),
  setRunExec: (report) => {
    if (!report) {
      set({ runExecByNode: {}, runTotals: { input: 0, output: 0 } })
      return
    }
    const byNode: Record<string, NodeExec> = {}
    for (const n of report.nodes) byNode[n.nodeId] = n
    set({
      runExecByNode: byNode,
      runTotals: { input: report.totalInputTokens, output: report.totalOutputTokens },
    })
  },

  onNodesChange: (changes) => set((s) => ({ nodes: applyNodeChanges(changes, s.nodes) })),
  onEdgesChange: (changes) => set((s) => ({ edges: applyEdgeChanges(changes, s.edges) })),
  onConnect: (conn) => set((s) => ({ edges: addEdge({ ...conn, id: uid('e') }, s.edges) })),
  deleteEdge: (id) => set((s) => ({ edges: s.edges.filter((e) => e.id !== id) })),

  addNode: (kind) =>
    set((s) => {
      const isFirstAgent = kind === 'agent' && !s.nodes.some((n) => n.data.kind === 'agent')
      spawnCount += 1
      const node: AppNode = {
        id: uid(kind),
        type: kind,
        position: { x: 120 + (spawnCount % 5) * 60, y: 80 + (spawnCount % 7) * 50 },
        data: defaultData(kind, isFirstAgent),
      }
      return { nodes: [...s.nodes, node], selectedId: node.id }
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
    const ids = new Set(picked.map((n) => n.id))
    set({
      clipboard: {
        nodes: picked,
        edges: s.edges.filter((e) => ids.has(e.source) && ids.has(e.target)),
      },
    })
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
      const ids = new Set(picked.map((n) => n.id))
      const edges = s.edges.filter((e) => ids.has(e.source) && ids.has(e.target))
      return insertClones(s, { nodes: picked, edges }, PASTE_OFFSET)
    }),

  duplicateNode: (id) =>
    set((s) => {
      const node = s.nodes.find((n) => n.id === id)
      return node ? insertClones(s, { nodes: [node], edges: [] }, PASTE_OFFSET) : {}
    }),

  selectNode: (id) => set({ selectedId: id }),
  setName: (name) => set({ name }),
  setMode: (mode) => set({ mode }),

  newFlow: () =>
    set({
      flowId: null,
      name: 'Untitled flow',
      mode: 'managed',
      flowMeta: {},
      nodes: [],
      edges: [],
      selectedId: null,
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
      },
      nodes,
      edges: flow.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })),
      selectedId: null,
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
