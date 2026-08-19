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

  // Read from the canvas, never from the node: the wiring IS the answer to "when does this run",
  // which is why the dropdown that used to ask it is gone.
  const wiring = useFlowStore((s) => {
    const id = s.selectedId
    const consumers = new Set(
      s.nodes.filter((n) => ['agent', 'merge', 'verifier'].includes(n.data.kind)).map((n) => n.id),
    )
    const before = s.edges.some((e) => e.source === id && consumers.has(e.target))
    const after = s.edges.some((e) => consumers.has(e.source) && e.target === id)
    return before && after ? 'both' : before ? 'before' : after ? 'after' : 'loose'
  })
  // A node saved by an older version said what it was in a field, and the compiler still honours
  // it — so the panel has to report that, not the drawing. Showing the drawing's answer here would
  // promise one thing and run another.
  const legacy = data.mode
  const handOff = legacy ? legacy === 'after' : wiring === 'after'

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

      {legacy && (
        <p className={styles.hint}>
          <b>Saved by an older version.</b>{' '}
          {legacy === 'after'
            ? 'This box runs when the flow finishes, because that is what it was saved as — whatever it is wired to.'
            : 'This box is a tool the agent may call, because that is what it was saved as. It does not run on its own.'}{' '}
          Its saved setting still decides, so nothing about this flow changed under you.{' '}
          <button className={styles.linkBtn} onClick={() => set({ mode: undefined })}>
            Let the wiring decide instead
          </button>
        </p>
      )}

      <p className={styles.hint}>
        <b>{legacy ? 'What the wiring would say:' : 'When it runs is the wiring.'}</b>{' '}
        {wiring === 'after'
          ? 'This box is wired out of an agent, so it runs when the flow finishes, with the run’s final answer as its input.'
          : wiring === 'before'
            ? 'This box is wired into an agent, so it runs first and its answer becomes that agent’s context. The agent also gets a run_flow tool, to ask it again in its own words.'
            : wiring === 'both'
              ? 'Wired both ways: it runs before the agent AND again when the flow finishes. Unusual, but allowed.'
              : 'Not wired to an agent yet, so it would never run. Connect it INTO an agent to run it first, or OUT of one to run it afterwards.'}
      </p>

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
          ? 'A failed or stopped run hands nothing on: the answer it would pass does not exist.'
          : data.waitForResult
            ? 'Waits for the answer, up to ten minutes; after that the child keeps going and the run is told which execution to check.'
            : 'Starts the other flow and moves on — with nothing to inject, since there is no answer yet.'}
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
