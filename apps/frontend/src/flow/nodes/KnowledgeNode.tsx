import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { KnowledgeRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function KnowledgeNode({ id, data, selected }: NodeProps<KnowledgeRFNode>) {
  const { t } = useTranslation()
  return (
    <NodeShell
      id={id}
      variant="knowledge"
      selected={selected}
      icon="📚"
      title={data.label || t('knowledge')}
      badge={t('KB')}
      showStatus
    >
      <div className={styles.snippet}>
        {data.baseId ? t('top {{n}} passages', { n: data.topK }) : t('no base selected')}
      </div>
    </NodeShell>
  )
}
