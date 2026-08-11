import type { MergeNodeData } from '../api/types.ts'
import { EFFORT_OPTIONS } from '../constants.ts'
import { Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import { ModelField } from './ModelField.tsx'

interface Props {
  data: MergeNodeData
  set: (patch: Record<string, unknown>) => void
}

export function MergeInspector({ data, set }: Props) {
  return (
    <>
      <Field label="Name" value={data.name} onChange={(v) => set({ name: v })} />
      <ModelField value={data.model} onChange={(v) => set({ model: v })} />
      <TextArea
        label={
          <span title="The merge step runs after every worker, receives all their reports (failures included), and can read each worker's files. Unlike workers it may run commands — tests and diffs happen here. Tell it how to reconcile and what the final report must contain.">
            Merge instructions ⓘ
          </span>
        }
        rows={6}
        placeholder={'Run the test suite before accepting any claim.\nPrefer the stricter reading when workers disagree.'}
        value={data.systemPrompt}
        onChange={(v) => set({ systemPrompt: v })}
      />
      <FineTuning>
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
      </FineTuning>
    </>
  )
}
