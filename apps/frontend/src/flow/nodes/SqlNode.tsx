import { Handle, type NodeProps, Position } from '@xyflow/react'
import type { SqlRFNode } from '../nodeTypes.ts'
import styles from './nodes.module.scss'

export function SqlNode({ data, selected }: NodeProps<SqlRFNode>) {
  return (
    <div className={`${styles.node} ${styles.sql} ${selected ? styles.selected : ''}`}>
      <Handle type="target" position={Position.Left} />
      <div className={styles.header}>
        <span className={styles.icon}>🗄</span>
        <span className={styles.title}>{data.label || 'sql'}</span>
        <span className={styles.badge}>SQL</span>
      </div>
      <div className={styles.snippet}>{data.query || 'no query'}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}
