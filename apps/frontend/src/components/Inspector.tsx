import type { AppNodeData } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { AgentInspector } from './AgentInspector.tsx'
import { InputInspector } from './InputInspector.tsx'
import { McpInspector } from './McpInspector.tsx'
import { SqlInspector } from './SqlInspector.tsx'
import styles from './panels.module.scss'

function title(data: AppNodeData): string {
  if (data.kind === 'agent') return 'Agent'
  if (data.kind === 'input') return 'Input / trigger'
  if (data.kind === 'mcp') return 'MCP server'
  if (data.kind === 'sql') return 'SQL source'
  return 'Repository'
}

export function Inspector() {
  const selectedId = useFlowStore((s) => s.selectedId)
  const node = useFlowStore((s) => s.nodes.find((n) => n.id === selectedId) ?? null)
  const update = useFlowStore((s) => s.updateNodeData)
  const remove = useFlowStore((s) => s.deleteNode)

  if (!node) {
    return (
      <aside className={styles.inspector}>
        <div className={styles.empty}>Select a node to edit its settings.</div>
      </aside>
    )
  }

  const id = node.id
  const data = node.data
  const set = (patch: Record<string, unknown>) => update(id, patch)

  return (
    <aside className={styles.inspector}>
      <div className={styles.inspectorHead}>
        <h3 className={styles.h3}>{title(data)}</h3>
        <button className={styles.del} onClick={() => remove(id)}>
          Delete
        </button>
      </div>

      {data.kind === 'agent' && <AgentInspector data={data} set={set} />}

      {data.kind === 'input' && <InputInspector data={data} set={set} />}

      {data.kind === 'mcp' && <McpInspector data={data} set={set} />}

      {data.kind === 'sql' && <SqlInspector data={data} set={set} />}

      {data.kind === 'repo' && (
        <>
          <label className={styles.field}>
            <span>Provider</span>
            <select value={data.provider} onChange={(e) => set({ provider: e.target.value })}>
              <option value="github">github</option>
              <option value="gitlab">gitlab</option>
            </select>
          </label>
          <label className={styles.field}>
            <span>URL</span>
            <input
              value={data.url}
              placeholder="https://github.com/owner/repo"
              onChange={(e) => set({ url: e.target.value })}
            />
          </label>
          <label className={styles.field}>
            <span>Token env var</span>
            <input value={data.tokenEnv} onChange={(e) => set({ tokenEnv: e.target.value })} />
          </label>
          <label className={styles.field}>
            <span>Mount path</span>
            <input
              value={data.mountPath}
              placeholder="/workspace/repo"
              onChange={(e) => set({ mountPath: e.target.value })}
            />
          </label>
          <label className={styles.field}>
            <span>Branch</span>
            <input value={data.branch} onChange={(e) => set({ branch: e.target.value })} />
          </label>
        </>
      )}
    </aside>
  )
}
