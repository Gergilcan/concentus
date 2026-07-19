import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { McpDef, McpNodeData, McpServerInfo } from '../api/types.ts'
import { Field, SelectField } from './fields.tsx'
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
        <SelectField label="Use saved server (from Resources)" value="" onChange={useSaved}>
          <option value="">— choose a saved MCP server —</option>
          {defs.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </SelectField>
      )}

      {servers.length > 0 && (
        <SelectField
          label="Select existing (from Claude Code)"
          value=""
          onChange={selectExisting}
          className={styles.libraryField}
        >
          <option value="">— choose a configured server —</option>
          {servers.map((s) => (
            <option key={s.name} value={s.name}>
              {s.name}
            </option>
          ))}
        </SelectField>
      )}

      <Field label="Name" value={data.name} onChange={(v) => set({ name: v })} />
      <Field label="URL" value={data.url} onChange={(v) => set({ url: v })} />
      <Field
        label="Token env var (optional)"
        value={data.tokenEnv}
        placeholder="LINEAR_MCP_TOKEN"
        onChange={(v) => set({ tokenEnv: v })}
      />

      <McpClaudeActions name={data.name} url={data.url} tokenEnv={data.tokenEnv} />
    </>
  )
}
