import type { NodeProps } from '@xyflow/react'
import type { KnowledgeRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function KnowledgeNode({ id, data, selected }: NodeProps<KnowledgeRFNode>) {
  return (
    <NodeShell
      id={id}
      variant="knowledge"
      selected={selected}
      icon="📚"
      title={data.label || 'knowledge'}
      badge="KB"
      showStatus
    >
      <div className={styles.snippet}>
        {data.baseId ? `top ${data.topK} passages` : 'no base selected'}
      </div>
    </NodeShell>
  )
}
