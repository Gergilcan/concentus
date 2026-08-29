import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import { cx } from '../../utils/cx.ts'
import type { NoteRFNode } from '../nodeTypes.ts'
import styles from './nodes.module.scss'

/**
 * A sticky note. No NodeShell, because the shell is a block's anatomy — handles, a badge, a
 * status — and a note has none of it: it is paper on the canvas, for whoever reads the flow next.
 * Double-click opens it for writing, the same gesture that opens every other box.
 */
export function NoteNode({ data, selected }: NodeProps<NoteRFNode>) {
  const { t } = useTranslation()
  return (
    <div className={cx(styles.note, styles['tint_' + data.color], selected && styles.selected)}>
      {data.text ? data.text : <span className={styles.noteEmpty}>{t('Double-click to write')}</span>}
    </div>
  )
}
