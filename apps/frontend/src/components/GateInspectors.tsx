import { useTranslation } from 'react-i18next'
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
  const { t } = useTranslation()
  const needsValue = data.test !== 'not_empty'
  return (
    <>
      <Field label={t('Label')} value={data.label} onChange={(v) => set({ label: v })} />
      <SelectField
        label={
          <span title={t('Tested against the answer of the agent this gate is wired to. After a for-each, it tests one item rather than the whole list.')}>
            {t('Run the branch when the answer ⓘ')}
          </span>
        }
        value={data.test}
        onChange={(v) => set({ test: v })}
      >
        <option value="not_empty">{t('is not empty')}</option>
        <option value="contains">{t('contains')}</option>
        <option value="not_contains">{t('does not contain')}</option>
        <option value="equals">{t('is exactly')}</option>
        <option value="matches">{t('matches a regular expression')}</option>
      </SelectField>
      {needsValue && (
        <Field
          label={data.test === 'matches' ? t('Regular expression') : t('Text')}
          value={data.value}
          onChange={(v) => set({ value: v })}
        />
      )}
      {needsValue && (
        <CheckboxField
          label={t('Case sensitive')}
          checked={data.caseSensitive}
          onChange={(v) => set({ caseSensitive: v })}
        />
      )}
      <p className={styles.hint}>
        {t('Wire it')} <b>{t('agent → condition → the flow to hand off to')}</b>.{' '}
        {t('When the test fails the branch does not run, and the run log says which gate stopped it — a branch that silently did not fire looks exactly like one nobody drew.')}
        {data.test === 'matches' && (
          <>
            {' '}
            {t('A pattern that does not compile fails the gate rather than the run.')}
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
  const { t } = useTranslation()
  return (
    <>
      <Field label={t('Label')} value={data.label} onChange={(v) => set({ label: v })} />
      <SelectField
        label={
          <span title={t('JSON is the reliable shape when the agent was told to answer with a list. Prose falls back to lines, so asking for JSON and getting a sentence still works.')}>
            {t('Read the list as ⓘ')}
          </span>
        }
        value={data.source}
        onChange={(v) => set({ source: v })}
      >
        <option value="lines">{t('one item per line')}</option>
        <option value="json">{t('a JSON array')}</option>
      </SelectField>
      <Field
        label={t('At most')}
        type="number"
        value={data.limit}
        onChange={(v) => set({ limit: Math.max(1, Math.min(500, Number(v) || 25)) })}
      />
      <p className={styles.hint}>
        {t('Each item starts its own run of the flow behind this gate, with the item as its input. A longer list is cut at this ceiling and the run log says by how much. Add a')}{' '}
        <b>{t('condition')}</b> {t('after this gate to run only some of the items.')}
      </p>
    </>
  )
}
