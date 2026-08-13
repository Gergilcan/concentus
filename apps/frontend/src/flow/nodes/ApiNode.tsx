import type { NodeProps } from '@xyflow/react'
import type { ApiRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function ApiNode({ id, data, selected }: NodeProps<ApiRFNode>) {
  let summary: string
  if (data.ops.length > 0) summary = `${data.ops.length} operation(s) allowed`
  else if (data.specUrl) summary = 'no operations allowed yet'
  else summary = 'no spec loaded'
  return (
    <NodeShell
      id={id}
      variant="api"
      selected={selected}
      icon="🌐"
      title={data.label || 'api'}
      badge="API"
      showStatus
    >
      <div className={styles.snippet}>{summary}</div>
    </NodeShell>
  )
}
