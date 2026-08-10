import type { NodeProps } from '@xyflow/react'
import type { ApiRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function ApiNode({ id, data, selected }: NodeProps<ApiRFNode>) {
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
      <div className={styles.snippet}>
        {data.ops.length > 0
          ? `${data.ops.length} operation(s) allowed`
          : data.specUrl
            ? 'no operations allowed yet'
            : 'no spec loaded'}
      </div>
    </NodeShell>
  )
}
