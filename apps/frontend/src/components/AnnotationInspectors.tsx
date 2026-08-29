import { useTranslation } from 'react-i18next'
import type { AnnotationColor, GroupNodeData, NoteNodeData } from '../api/types.ts'
import { Field, SelectField, TextArea } from './fields.tsx'
import styles from './panels.module.scss'

/**
 * The two annotations — a sticky note and a frame. One file, because they are one idea: things
 * drawn for the person reading the canvas, which the run never sees and the doctor never checks.
 */

const COLORS: { id: AnnotationColor; label: string }[] = [
  { id: 'yellow', label: 'Yellow' },
  { id: 'blue', label: 'Blue' },
  { id: 'green', label: 'Green' },
  { id: 'pink', label: 'Pink' },
]

function ColorField({ value, onChange }: { value: AnnotationColor; onChange: (c: AnnotationColor) => void }) {
  const { t } = useTranslation()
  return (
    <SelectField label={t('Colour')} value={value} onChange={(v) => onChange(v as AnnotationColor)}>
      {COLORS.map((c) => (
        <option key={c.id} value={c.id}>
          {t(c.label)}
        </option>
      ))}
    </SelectField>
  )
}

export function NoteInspector({
  data,
  set,
}: {
  data: NoteNodeData
  set: (patch: Record<string, unknown>) => void
}) {
  const { t } = useTranslation()
  return (
    <>
      <TextArea
        label={t('Text')}
        rows={8}
        value={data.text}
        placeholder={t('What the next person reading this flow should know.')}
        onChange={(v) => set({ text: v })}
      />
      <ColorField value={data.color} onChange={(color) => set({ color })} />
      <p className={styles.hint}>{t('Notes are for people. The run never reads them, and the pre-run check never mentions them.')}</p>
    </>
  )
}

export function GroupInspector({
  data,
  set,
}: {
  data: GroupNodeData
  set: (patch: Record<string, unknown>) => void
}) {
  const { t } = useTranslation()
  return (
    <>
      <Field label={t('Label')} value={data.label} onChange={(v) => set({ label: v })} />
      <ColorField value={data.color} onChange={(color) => set({ color })} />
      <p className={styles.hint}>
        {t('Drop blocks inside the frame and they move with it; drag a block out to release it. Select the frame to resize it from its corners. Deleting the frame keeps the blocks.')}
      </p>
    </>
  )
}
