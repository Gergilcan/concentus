import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { KnowledgeDef, KnowledgeNodeData } from '../api/types.ts'
import { Field, SelectField } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: KnowledgeNodeData
  set: (patch: Record<string, unknown>) => void
}

export function KnowledgeInspector({ data, set }: Props) {
  const [bases, setBases] = useState<KnowledgeDef[]>([])

  useEffect(() => {
    api
      .listKnowledge()
      .then(setBases)
      .catch(() => setBases([]))
  }, [])

  return (
    <>
      <Field label="Label" value={data.label} onChange={(v) => set({ label: v })} />
      <SelectField
        label="Knowledge base"
        value={data.baseId}
        onChange={(v) => set({ baseId: v })}
      >
        <option value="">— choose a base —</option>
        {bases.map((b) => (
          <option key={b.id} value={b.id}>
            {b.name}
          </option>
        ))}
      </SelectField>
      <Field
        label="Passages to inject (top-K)"
        type="number"
        value={data.topK}
        onChange={(v) => set({ topK: Math.max(1, Math.min(20, Number(v) || 5)) })}
      />
      <p className={styles.hint}>
        When a run starts, the passages most relevant to its prompt are retrieved from this base
        and handed to the connected agent as context. Bases and their documents are managed under{' '}
        <b>Resources → Knowledge</b>.
        {bases.length === 0 && (
          <>
            <br />
            <b>No bases exist yet</b> — create one there first.
          </>
        )}
      </p>
    </>
  )
}
