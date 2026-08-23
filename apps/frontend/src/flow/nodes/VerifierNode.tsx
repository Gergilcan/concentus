import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { VerifierRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function VerifierNode({ id, data, selected }: NodeProps<VerifierRFNode>) {
  const { t } = useTranslation()
  return (
    <NodeShell
      altHandle={{ id: 'error', label: t('on error'), tone: 'error' }}
      id={id}
      variant="verifier"
      selected={selected}
      icon="⚖"
      title={data.name || t('Verifier')}
      badge={t('VERIFY')}
      showStatus
    >
      <div className={styles.snippet}>{data.model}</div>
    </NodeShell>
  )
}
