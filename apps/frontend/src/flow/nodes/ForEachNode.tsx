import type { NodeProps } from '@xyflow/react'
import type { ForEachRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function ForEachNode({ id, data, selected }: NodeProps<ForEachRFNode>) {
  const source = data.source === 'json' ? 'a JSON array' : 'one item per line'
  return (
    <NodeShell
      id={id}
      variant="foreach"
      selected={selected}
      icon="⟳"
      title={data.label || 'for each'}
      badge="EACH"
    >
      <div className={styles.snippet}>
        {source} · up to {data.limit}
      </div>
    </NodeShell>
  )
}
