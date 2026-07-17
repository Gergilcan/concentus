import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { AgentNodeData, LibraryAgent } from '../api/types.ts'
import styles from './panels.module.scss'

interface Props {
  data: AgentNodeData
  set: (patch: Record<string, unknown>) => void
}

export function AgentInspector({ data, set }: Props) {
  const [library, setLibrary] = useState<LibraryAgent[]>([])

  useEffect(() => {
    api
      .listAgents()
      .then(setLibrary)
      .catch(() => setLibrary([]))
  }, [])

  const applyLibrary = (id: string) => {
    const a = library.find((x) => x.id === id)
    if (!a) return
    set({
      name: a.name,
      model: a.model,
      effort: a.effort,
      maxTokens: a.maxTokens,
      systemPrompt: a.systemPrompt,
      description: a.description ?? '',
    })
  }

  return (
    <>
      {library.length > 0 && (
        <label className={`${styles.field} ${styles.libraryField}`}>
          <span>Load from library</span>
          <select value="" onChange={(e) => applyLibrary(e.target.value)}>
            <option value="">— choose an agent —</option>
            {library.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.model})
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
        <span>Role</span>
        <select value={data.role} onChange={(e) => set({ role: e.target.value })}>
          <option value="coordinator">coordinator</option>
          <option value="subagent">subagent</option>
        </select>
      </label>
      <label className={styles.field}>
        <span>Model</span>
        <input value={data.model} onChange={(e) => set({ model: e.target.value })} />
      </label>
      <label className={styles.field}>
        <span>Effort</span>
        <select value={data.effort} onChange={(e) => set({ effort: e.target.value })}>
          {['low', 'medium', 'high', 'xhigh', 'max'].map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </select>
      </label>
      <label className={styles.field}>
        <span>Max tokens</span>
        <input
          type="number"
          value={data.maxTokens}
          onChange={(e) => set({ maxTokens: Number(e.target.value) })}
        />
      </label>
      {data.role === 'subagent' && (
        <label className={styles.field}>
          <span>Delegate when… (routing)</span>
          <textarea
            rows={3}
            placeholder="Use PROACTIVELY for backend/Java work. Give it only the backend part of the plan."
            value={data.description ?? ''}
            onChange={(e) => set({ description: e.target.value })}
          />
        </label>
      )}
      <label className={styles.field}>
        <span>System prompt</span>
        <textarea
          rows={6}
          value={data.systemPrompt}
          onChange={(e) => set({ systemPrompt: e.target.value })}
        />
      </label>
    </>
  )
}
