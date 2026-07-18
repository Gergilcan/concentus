import { Handle, type NodeProps, Position } from '@xyflow/react'
import type { McpRFNode } from '../nodeTypes.ts'
import { NodeStatusBadge } from './NodeStatusBadge.tsx'
import styles from './nodes.module.scss'

export function McpNode({ id, data, selected }: NodeProps<McpRFNode>) {
  return (
    <div className={`${styles.node} ${styles.mcp} ${selected ? styles.selected : ''}`}>
      <Handle type="target" position={Position.Left} />
      <div className={styles.header}>
        <span className={styles.icon}>⚙</span>
        <span className={styles.title}>{data.name || 'mcp'}</span>
        <span className={styles.badge}>MCP</span>
      </div>
      <div className={styles.snippet}>{data.url || 'no url'}</div>
      <NodeStatusBadge id={id} />
      <Handle type="source" position={Position.Right} />
    </div>
  )
}
