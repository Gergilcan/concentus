import type { ConditionNodeData, ForEachNodeData } from '../api/types.ts'
import { CheckboxField, Field, SelectField } from './fields.tsx'
import styles from './panels.module.scss'

/**
 * The two gates that live between an agent and what it hands off to. One file, because they are
 * one idea: the canvas deciding what happens next, measured from the answer, instead of the
 * decision being an instruction an agent may or may not follow.
 */

export function ConditionInspector({
  data,
  set,
}: {
  data: ConditionNodeData
  set: (patch: Record<string, unknown>) => void
}) {
  const needsValue = data.test !== 'not_empty'
  return (
    <>
      <Field label="Label" value={data.label} onChange={(v) => set({ label: v })} />
      <SelectField
        label={
          <span title="Tested against the answer of the agent this gate is wired to. After a for-each, it tests one item rather than the whole list.">
            Run the branch when the answer ⓘ
          </span>
        }
        value={data.test}
        onChange={(v) => set({ test: v })}
      >
        <option value="not_empty">is not empty</option>
        <option value="contains">contains</option>
        <option value="not_contains">does not contain</option>
        <option value="equals">is exactly</option>
        <option value="matches">matches a regular expression</option>
      </SelectField>
      {needsValue && (
        <Field
          label={data.test === 'matches' ? 'Regular expression' : 'Text'}
          value={data.value}
          onChange={(v) => set({ value: v })}
        />
      )}
      {needsValue && (
        <CheckboxField
          label="Case sensitive"
          checked={data.caseSensitive}
          onChange={(v) => set({ caseSensitive: v })}
        />
      )}
      <p className={styles.hint}>
        Wire it <b>agent → condition → the flow to hand off to</b>. When the test fails the branch
        does not run, and the run log says which gate stopped it — a branch that silently did not
        fire looks exactly like one nobody drew.
        {data.test === 'matches' && (
          <>
            {' '}
            A pattern that does not compile fails the gate rather than the run.
          </>
        )}
      </p>
    </>
  )
}

export function ForEachInspector({
  data,
  set,
}: {
  data: ForEachNodeData
  set: (patch: Record<string, unknown>) => void
}) {
  return (
    <>
      <Field label="Label" value={data.label} onChange={(v) => set({ label: v })} />
      <SelectField
        label={
          <span title="JSON is the reliable shape when the agent was told to answer with a list. Prose falls back to lines, so asking for JSON and getting a sentence still works.">
            Read the list as ⓘ
          </span>
        }
        value={data.source}
        onChange={(v) => set({ source: v })}
      >
        <option value="lines">one item per line</option>
        <option value="json">a JSON array</option>
      </SelectField>
      <Field
        label="At most"
        type="number"
        value={data.limit}
        onChange={(v) => set({ limit: Math.max(1, Math.min(500, Number(v) || 25)) })}
      />
      <p className={styles.hint}>
        Each item starts its own run of the flow behind this gate, with the item as its input. A
        longer list is cut at this ceiling and the run log says by how much. Add a{' '}
        <b>condition</b> after this gate to run only some of the items.
      </p>
    </>
  )
}
