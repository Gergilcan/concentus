import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { KnowledgeDef, KnowledgeNodeData } from '../api/types.ts'
import { Field, FineTuning, SelectField } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: KnowledgeNodeData
  set: (patch: Record<string, unknown>) => void
}

export function KnowledgeInspector({ data, set }: Props) {
  const { t } = useTranslation()
  const [bases, setBases] = useState<KnowledgeDef[]>([])

  useEffect(() => {
    api
      .listKnowledge()
      .then(setBases)
      .catch(() => setBases([]))
  }, [])

  return (
    <>
      <Field label={t('Label')} value={data.label} onChange={(v) => set({ label: v })} />
      <SelectField
        label={
          <span title={t("Managed under Resources → Knowledge. At run start, the passages most relevant to the run's prompt are injected into the connected agent.")}>
            {t('Knowledge base ⓘ')}
          </span>
        }
        value={data.baseId}
        onChange={(v) => set({ baseId: v })}
      >
        <option value="">{t('— choose a base —')}</option>
        {bases.map((b) => (
          <option key={b.id} value={b.id}>
            {b.name}
          </option>
        ))}
      </SelectField>
      <FineTuning>
        <Field
          label={t('Passages to inject (top-K)')}
          type="number"
          value={data.topK}
          onChange={(v) => set({ topK: Math.max(1, Math.min(20, Number(v) || 5)) })}
        />
      </FineTuning>
      {bases.length === 0 && (
        <p className={styles.hint}>
          {t('No bases yet — create one under')} <b>{t('Resources → Knowledge')}</b>.
        </p>
      )}
    </>
  )
}
