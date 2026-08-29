import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { FacadeProfile, MergeNodeData } from '../api/types.ts'
import { EFFORT_OPTIONS } from '../constants.ts'
import { Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import { ModelField } from './ModelField.tsx'

interface Props {
  data: MergeNodeData
  set: (patch: Record<string, unknown>) => void
}

export function MergeInspector({ data, set }: Props) {
  const { t } = useTranslation()
  const [facades, setFacades] = useState<FacadeProfile[]>([])

  useEffect(() => {
    api.listFacadeProfiles().then(setFacades).catch(() => setFacades([]))
  }, [])

  return (
    <>
      <Field label={t('Name')} value={data.name} onChange={(v) => set({ name: v })} />
      <ModelField value={data.model} onChange={(v) => set({ model: v })} />
      <TextArea
        label={
          <span title={t("The merge step runs after every worker, receives all their reports (failures included), and can read each worker's files. Unlike workers it may run commands — tests and diffs happen here. Tell it how to reconcile and what the final report must contain.")}>
            {t('Merge instructions ⓘ')}
          </span>
        }
        rows={6}
        placeholder={t('Run the test suite before accepting any claim.\nPrefer the stricter reading when workers disagree.')}
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
        <Field
          label={
            <span title={t("Independent workers only. How many extra launches this block gets after its process fails (a crash, a bad exit — not a timeout, which is never retried, and not a verifier rejection, which is what the escalation model is for). Blank uses the deployment's default; 0 means one attempt and no more. The run log says which attempt each launch was.")}>
              {t('Retries after a failure (blank = default) ⓘ')}
            </span>
          }
          type="number"
          value={data.retries ?? ''}
          onChange={(v) => set({ retries: v === '' ? undefined : Math.max(0, Number(v)) })}
        />
        {/* The merge speaks last, so it is the step that sends the report or files the ticket —
            which is exactly why it reaches MCP like a worker rather than not at all. */}
        <SelectField
          label={
            <span title={t('The merge step reaches the MCP servers wired to this node the same way a worker does: remote ones through the facade this backend enforces, local ones launched by its own process. Leave it empty and nothing is filtered. A profile narrows it to an allowlist, read-only, or simulated writes — and a profile that withholds anything withholds the local servers entirely, because a facade cannot enforce a rule on a server it does not proxy.')}>
              {t('Facade profile ⓘ')}
            </span>
          }
          value={data.facadeProfileId ?? ''}
          onChange={(v) => set({ facadeProfileId: v })}
        >
          <option value="">{t('— none: everything wired to this node —')}</option>
          {facades.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
              {p.readOnly ? ` ${t('(read-only)')}` : (p.dryRun ?? true) ? ` ${t('(dry-run writes)')}` : ''}
            </option>
          ))}
        </SelectField>
      </FineTuning>
    </>
  )
}
