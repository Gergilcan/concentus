import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { ForEachRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function ForEachNode({ id, data, selected }: NodeProps<ForEachRFNode>) {
  const { t } = useTranslation()
  const source = data.source === 'json' ? t('a JSON array') : t('one item per line')
  return (
    <NodeShell
      id={id}
      variant="foreach"
      selected={selected}
      icon="⟳"
      title={data.label || t('for each')}
      badge={t('EACH')}
    >
      <div className={styles.snippet}>
        {source} · {t('up to {{n}}', { n: data.limit })}
      </div>
    </NodeShell>
  )
}
