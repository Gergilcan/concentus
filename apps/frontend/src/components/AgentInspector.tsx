import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { AgentNodeData, LibraryAgent } from '../api/types.ts'
import { EFFORT_OPTIONS } from '../constants.ts'
import { Field, SelectField, TextArea } from './fields.tsx'
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
        <SelectField label="Load from library" value="" onChange={applyLibrary} className={styles.libraryField}>
          <option value="">— choose an agent —</option>
          {library.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name} ({a.model})
            </option>
          ))}
        </SelectField>
      )}

      <Field label="Name" value={data.name} onChange={(v) => set({ name: v })} />
      <SelectField label="Role" value={data.role} onChange={(v) => set({ role: v })}>
        <option value="coordinator">coordinator</option>
        <option value="subagent">subagent</option>
      </SelectField>
      <Field label="Model" value={data.model} onChange={(v) => set({ model: v })} />
      <SelectField label="Effort" value={data.effort} onChange={(v) => set({ effort: v })}>
        {EFFORT_OPTIONS.map((v) => (
          <option key={v} value={v}>
            {v}
          </option>
        ))}
      </SelectField>
      <Field
        label="Max tokens"
        type="number"
        value={data.maxTokens}
        onChange={(v) => set({ maxTokens: Number(v) })}
      />
      {data.role === 'subagent' && (
        <TextArea
          label="Delegate when… (routing)"
          rows={3}
          placeholder="Use PROACTIVELY for backend/Java work. Give it only the backend part of the plan."
          value={data.description ?? ''}
          onChange={(v) => set({ description: v })}
        />
      )}
      <TextArea label="System prompt" rows={6} value={data.systemPrompt} onChange={(v) => set({ systemPrompt: v })} />
    </>
  )
}
