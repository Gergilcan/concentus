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
  NodeKind,
} from '../api/types.ts'

export type AppNode = Node<AppNodeData>

let spawnCount = 0

function uid(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().slice(0, 8)}`
}

function defaultData(kind: NodeKind, isFirstAgent: boolean): AppNodeData {
  switch (kind) {
    case 'agent':
      return {
        kind: 'agent',
        name: isFirstAgent ? 'Coordinator' : 'Sub-agent',
        role: isFirstAgent ? 'coordinator' : 'subagent',
        model: 'claude-opus-4-8',
        description: '',
        systemPrompt: '',
        maxTokens: 16000,
        effort: 'high',
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
        secret: globalThis.crypto?.randomUUID?.() ?? 'change-me',
      }
  }
}

interface FlowState {
  flowId: string | null
  name: string
  mode: 'managed' | 'local'
  nodes: AppNode[]
  edges: Edge[]
  selectedId: string | null

  onNodesChange: (changes: NodeChange<AppNode>[]) => void
  onEdgesChange: (changes: EdgeChange[]) => void
  onConnect: (conn: Connection) => void
  deleteEdge: (id: string) => void
  addNode: (kind: NodeKind) => void
  updateNodeData: (id: string, patch: Record<string, unknown>) => void
  deleteNode: (id: string) => void
  selectNode: (id: string | null) => void
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
  nodes: [],
  edges: [],
  selectedId: null,

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

  selectNode: (id) => set({ selectedId: id }),
  setName: (name) => set({ name }),
  setMode: (mode) => set({ mode }),

  newFlow: () =>
    set({ flowId: null, name: 'Untitled flow', mode: 'managed', nodes: [], edges: [], selectedId: null }),

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
      nodes,
      edges: s.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })),
    }
  },
}))
