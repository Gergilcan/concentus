import type { NodeProps } from '@xyflow/react'
import type { VerifierRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function VerifierNode({ id, data, selected }: NodeProps<VerifierRFNode>) {
  return (
    <NodeShell
      id={id}
      variant="verifier"
      selected={selected}
      icon="⚖"
      title={data.name || 'Verifier'}
      badge="VERIFY"
      showStatus
    >
      <div className={styles.snippet}>{data.model}</div>
    </NodeShell>
  )
}
