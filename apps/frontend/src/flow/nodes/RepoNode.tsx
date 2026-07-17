import { Handle, type NodeProps, Position } from '@xyflow/react'
import type { RepoRFNode } from '../nodeTypes.ts'
import styles from './nodes.module.scss'

export function RepoNode({ data, selected }: NodeProps<RepoRFNode>) {
  return (
    <div className={`${styles.node} ${styles.repo} ${selected ? styles.selected : ''}`}>
      <Handle type="target" position={Position.Left} />
      <div className={styles.header}>
        <span className={styles.icon}>{data.provider === 'gitlab' ? '🦊' : '🐙'}</span>
        <span className={styles.title}>{repoName(data.url) || 'repo'}</span>
        <span className={styles.badge}>{data.provider}</span>
      </div>
      <div className={styles.snippet}>{data.url || 'no url'}</div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function repoName(url: string): string {
  if (!url) return ''
  const parts = url.replace(/\/+$/, '').split('/')
  return parts.slice(-2).join('/')
}
