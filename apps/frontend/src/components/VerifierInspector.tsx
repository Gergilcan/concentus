import { useTranslation } from 'react-i18next'
import type { VerifierNodeData } from '../api/types.ts'
import { EFFORT_OPTIONS } from '../constants.ts'
import { Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import { ModelField } from './ModelField.tsx'

interface Props {
  data: VerifierNodeData
  set: (patch: Record<string, unknown>) => void
}

export function VerifierInspector({ data, set }: Props) {
  const { t } = useTranslation()
  return (
    <>
      <Field label={t('Name')} value={data.name} onChange={(v) => set({ name: v })} />
      <ModelField value={data.model} onChange={(v) => set({ model: v })} />
      <TextArea
        label={
          <span title={t("The verifier runs after every worker and BEFORE the merge, with the opposite objective: find the reason each output should be REJECTED. A rejected output is withheld from the merge — the kill is real. It reads the workers' files but cannot edit or run commands. Add here what disqualifies an output in this flow.")}>
            {t('Rejection criteria ⓘ')}
          </span>
        }
        rows={6}
        placeholder={t('Reject any output whose claims cite no file it actually wrote.\nReject numbers that appear in no worker file.')}
        value={data.systemPrompt}
        onChange={(v) => set({ systemPrompt: v })}
      />
      <FineTuning>
        <SelectField label={t('Effort')} value={data.effort} onChange={(v) => set({ effort: v })}>
          {EFFORT_OPTIONS.map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </SelectField>
        <Field
          label={t('Max tokens')}
          type="number"
          value={data.maxTokens}
          onChange={(v) => set({ maxTokens: Number(v) })}
        />
      </FineTuning>
    </>
  )
}
