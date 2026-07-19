import type { NodeProps } from '@xyflow/react'
import type { AgentRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function AgentNode({ id, data, selected }: NodeProps<AgentRFNode>) {
  const isCoordinator = data.role === 'coordinator'
  return (
    <NodeShell
      id={id}
      variant="agent"
      selected={selected}
      coordinator={isCoordinator}
      icon={isCoordinator ? '★' : '◆'}
      title={data.name || 'Agent'}
      badge={data.role}
      showStatus
    >
      <div className={styles.meta}>{data.model}</div>
      {data.systemPrompt ? (
        <div className={styles.snippet} title={data.systemPrompt}>
          {data.systemPrompt}
        </div>
      ) : (
        <div className={styles.snippetMuted}>no system prompt</div>
      )}
    </NodeShell>
  )
}
