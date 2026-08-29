import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { VerifierRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function VerifierNode({ id, data, selected }: NodeProps<VerifierRFNode>) {
  const { t } = useTranslation()
  return (
    <NodeShell
      // Lowest is what it rejected — the verifier's real second output. Above it, the verifier
      // process itself dying, which is a different fact and keeps its own dot.
      altHandles={[
        { id: 'rejected', label: t('on rejected'), tone: 'rejected', optional: { flag: 'rejectedOutput', enabled: !!data.rejectedOutput } },
        { id: 'error', label: t('on error'), tone: 'error', optional: { flag: 'errorOutput', enabled: !!data.errorOutput } },
      ]}
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
