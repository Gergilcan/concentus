import { Handle, type NodeProps, Position } from '@xyflow/react'
import type { AgentRFNode } from '../nodeTypes.ts'
import styles from './nodes.module.scss'

export function AgentNode({ data, selected }: NodeProps<AgentRFNode>) {
  const isCoordinator = data.role === 'coordinator'
  return (
    <div
      className={`${styles.node} ${styles.agent} ${isCoordinator ? styles.coordinator : ''} ${
        selected ? styles.selected : ''
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <div className={styles.header}>
        <span className={styles.icon}>{isCoordinator ? '★' : '◆'}</span>
        <span className={styles.title}>{data.name || 'Agent'}</span>
        <span className={`${styles.badge} ${isCoordinator ? styles.badgeCoord : ''}`}>
          {data.role}
        </span>
      </div>
      <div className={styles.meta}>{data.model}</div>
      {data.systemPrompt ? (
        <div className={styles.snippet}>{data.systemPrompt.slice(0, 80)}</div>
      ) : (
        <div className={styles.snippetMuted}>no system prompt</div>
      )}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}
