import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { SqlRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function SqlNode({ id, data, selected }: NodeProps<SqlRFNode>) {
  const { t } = useTranslation()
  return (
    <NodeShell id={id} variant="sql" selected={selected} icon="🗄" title={data.label || t('sql')} badge={t('SQL')} showStatus>
      <div className={styles.snippet}>{data.query || t('no query')}</div>
    </NodeShell>
  )
}
