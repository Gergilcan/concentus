import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { BackendFlow, FlowRunNodeData } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { Field, SelectField } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: FlowRunNodeData
  set: (patch: Record<string, unknown>) => void
}

export function FlowRunInspector({ data, set }: Props) {
  const [flows, setFlows] = useState<BackendFlow[]>([])
  const currentId = useFlowStore((s) => s.flowId)

  useEffect(() => {
    api
      .listFlows()
      .then(setFlows)
      .catch(() => setFlows([]))
  }, [])

  // A flow cannot run itself: the compiler refuses it, so it should not be offerable either.
  const choices = flows.filter((f) => f.id && f.id !== currentId)
  const handOff = data.mode === 'after'

  return (
    <>
      <Field label="Label" value={data.label} onChange={(v) => set({ label: v })} />

      <SelectField
        label={
          <span title="Any other saved flow. It runs with its own budget and its own permission mode — this flow's settings do not carry over.">
            Flow to run ⓘ
          </span>
        }
        value={data.flowId}
        onChange={(v) => set({ flowId: v })}
      >
        <option value="">— choose a flow —</option>
        {choices.map((f) => (
          <option key={f.id} value={f.id}>
            {f.name}
          </option>
        ))}
      </SelectField>

      <SelectField
        label={
          <span title="Wired to an agent, the flow becomes a tool the agent may call. Left unconnected, it fires by itself when this flow completes.">
            When it runs ⓘ
          </span>
        }
        value={data.mode}
        onChange={(v) => set({ mode: v })}
      >
        <option value="tool">When an agent calls it (connect this to an agent)</option>
        <option value="after">When this flow finishes (hand-off)</option>
      </SelectField>

      {!handOff && (
        <label className={styles.checkField}>
          <input
            type="checkbox"
            checked={data.waitForResult}
            onChange={(e) => set({ waitForResult: e.target.checked })}
          />
          <span title="On: the agent waits and receives the other flow's answer. Off: it starts the flow and carries on.">
            Wait for its answer ⓘ
          </span>
        </label>
      )}

      <p className={styles.hint}>
        {handOff
          ? 'Starts when this flow completes, with this run’s final answer as its input. A failed or stopped run hands nothing on.'
          : data.waitForResult
            ? 'The agent gets a run_flow tool. It waits for the answer, up to ten minutes; after that the child keeps going and the agent is told which run to check.'
            : 'The agent gets a run_flow tool that starts the flow and returns immediately with its run id.'}
      </p>
      <p className={styles.hint}>
        The other flow starts fresh: it sees only the text it is given, not this conversation. Loops
        are refused — a flow already running further up the chain will not be started again.
      </p>
      {choices.length === 0 && (
        <p className={styles.hint}>
          There is no other saved flow yet. Save a second flow and it will appear here.
        </p>
      )}
    </>
  )
}
