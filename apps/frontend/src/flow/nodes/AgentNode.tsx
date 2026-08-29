import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { AgentRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function AgentNode({ id, data, selected }: NodeProps<AgentRFNode>) {
  const { t } = useTranslation()
  const isCoordinator = data.kind === 'coordinator'
  return (
    <NodeShell
      altHandles={[{ id: 'error', label: t('on error'), tone: 'error', optional: { flag: 'errorOutput', enabled: !!data.errorOutput } }]}
      id={id}
      variant="agent"
      selected={selected}
      coordinator={isCoordinator}
      icon={isCoordinator ? '★' : '◆'}
      title={data.name || (isCoordinator ? t('Coordinator') : t('Agent'))}
      badge={isCoordinator ? t('coordinator') : t('agent')}
      showStatus
    >
      <div className={styles.meta}>{data.model}</div>
      {data.systemPrompt ? (
        <div className={styles.snippet} title={data.systemPrompt}>
          {data.systemPrompt}
        </div>
      ) : (
        <div className={styles.snippetMuted}>{t('no system prompt')}</div>
      )}
    </NodeShell>
  )
}
