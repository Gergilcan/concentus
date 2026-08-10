import type { NodeProps } from '@xyflow/react'
import type { MergeRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function MergeNode({ id, data, selected }: NodeProps<MergeRFNode>) {
  return (
    <NodeShell
      id={id}
      variant="merge"
      selected={selected}
      icon="⛙"
      title={data.name || 'Merge'}
      badge="MERGE"
      showStatus
    >
      <div className={styles.snippet}>{data.model}</div>
    </NodeShell>
  )
}
