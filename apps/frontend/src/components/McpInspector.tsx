import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { McpDef, McpNodeData, McpServerInfo } from '../api/types.ts'
import { McpClaudeActions } from './McpClaudeActions.tsx'
import styles from './panels.module.scss'

interface Props {
  data: McpNodeData
  set: (patch: Record<string, unknown>) => void
}

export function McpInspector({ data, set }: Props) {
  const [servers, setServers] = useState<McpServerInfo[]>([])
  const [defs, setDefs] = useState<McpDef[]>([])

  useEffect(() => {
    api
      .listMcpServers()
      .then(setServers)
      .catch(() => setServers([]))
    api
      .listMcpDefs()
      .then(setDefs)
      .catch(() => setDefs([]))
  }, [])

  const useSaved = (id: string) => {
    const d = defs.find((x) => x.id === id)
    if (d) set({ name: d.name, url: d.url, tokenEnv: d.tokenEnv })
  }

  const selectExisting = (name: string) => {
    const s = servers.find((x) => x.name === name)
    if (s) set({ name: s.name, url: s.url })
  }

  return (
    <>
      {defs.length > 0 && (
        <label className={styles.field}>
          <span>Use saved server (from Resources)</span>
          <select value="" onChange={(e) => useSaved(e.target.value)}>
            <option value="">— choose a saved MCP server —</option>
            {defs.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
      )}

      {servers.length > 0 && (
        <label className={`${styles.field} ${styles.libraryField}`}>
          <span>Select existing (from Claude Code)</span>
          <select value="" onChange={(e) => selectExisting(e.target.value)}>
            <option value="">— choose a configured server —</option>
            {servers.map((s) => (
              <option key={s.name} value={s.name}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
      )}

      <label className={styles.field}>
        <span>Name</span>
        <input value={data.name} onChange={(e) => set({ name: e.target.value })} />
      </label>
      <label className={styles.field}>
        <span>URL</span>
        <input value={data.url} onChange={(e) => set({ url: e.target.value })} />
      </label>
      <label className={styles.field}>
        <span>Token env var (optional)</span>
        <input
          value={data.tokenEnv}
          placeholder="LINEAR_MCP_TOKEN"
          onChange={(e) => set({ tokenEnv: e.target.value })}
        />
      </label>

      <McpClaudeActions name={data.name} url={data.url} tokenEnv={data.tokenEnv} />
    </>
  )
}
