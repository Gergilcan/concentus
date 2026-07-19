import type { NodeProps } from '@xyflow/react'
import type { RepoRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function RepoNode({ data, selected }: NodeProps<RepoRFNode>) {
  return (
    <NodeShell
      variant="repo"
      selected={selected}
      icon={data.provider === 'gitlab' ? '🦊' : '🐙'}
      title={repoName(data.url) || 'repo'}
      badge={data.provider}
    >
      <div className={styles.snippet}>{data.url || 'no url'}</div>
    </NodeShell>
  )
}

function repoName(url: string): string {
  if (!url) return ''
  const parts = url.replace(/\/+$/, '').split('/')
  return parts.slice(-2).join('/')
}
