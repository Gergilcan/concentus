import type { NodeProps } from '@xyflow/react'
import type { SqlRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function SqlNode({ id, data, selected }: NodeProps<SqlRFNode>) {
  return (
    <NodeShell id={id} variant="sql" selected={selected} icon="🗄" title={data.label || 'sql'} badge="SQL" showStatus>
      <div className={styles.snippet}>{data.query || 'no query'}</div>
    </NodeShell>
  )
}
