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
      <p className={styles.hint}>
        The model chooses the engine. <b>claude-*</b> runs on your Claude subscription or the cloud
        API. Any other configured model (<code>gpt-*</code>, <code>gemini-*</code>,{' '}
        <code>deepseek-*</code>, …) runs on the API backend, which supports delegation, SQL context,
        MCP servers, and file read/write/edit within this agent's <b>context folders</b> — but{' '}
        <b>not bash</b>.
      </p>
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

      <TextArea
        label="Context folders (one per line)"
        rows={3}
        placeholder={'C:\\Users\\me\\code\\wirej\nC:\\Users\\me\\code\\concentus'}
        value={(data.contextFolders ?? []).join('\n')}
        // Split on write, not on every keystroke's trimmed value — otherwise a half-typed
        // line vanishes as soon as it is momentarily blank.
        onChange={(v) => set({ contextFolders: v.split('\n').map((s) => s.trim()).filter(Boolean) })}
      />
      <Field
        label="CLAUDE.md path (file or folder)"
        placeholder="C:\Users\me\code\wirej"
        value={data.claudeMdPath ?? ''}
        onChange={(v) => set({ claudeMdPath: v })}
      />
      <p className={styles.hint}>
        Folders this agent should treat as its source of truth — without them it only sees a scratch
        workspace and guesses from names. Each path must sit under a directory configured in{' '}
        <code>local.context-roots</code> on the backend, otherwise it's skipped and the reason is
        shown in the run console. <b>Local (subscription) runs only</b> — cloud runs execute in a
        sandbox with no access to your machine.
      </p>
    </>
  )
}
