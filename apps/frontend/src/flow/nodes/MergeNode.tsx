import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { MergeRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function MergeNode({ id, data, selected }: NodeProps<MergeRFNode>) {
  const { t } = useTranslation()
  return (
    <NodeShell
      altHandle={{ id: 'error', label: t('on error'), tone: 'error' }}
      id={id}
      variant="merge"
      selected={selected}
      icon="⛙"
      title={data.name || t('Merge')}
      badge={t('MERGE')}
      showStatus
    >
      <div className={styles.snippet}>{data.model}</div>
    </NodeShell>
  )
}
