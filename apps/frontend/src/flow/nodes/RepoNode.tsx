import type { NodeProps } from '@xyflow/react'
import type { RepoRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function RepoNode({ data, selected }: NodeProps<RepoRFNode>) {
  const group = (data.group ?? '').trim()
  // A group node has no URL to show, so the canvas shows what it stands for instead — otherwise a
  // perfectly configured group would read as "no url".
  const count = data.only?.length ?? 0
  return (
    <NodeShell
      variant="repo"
      selected={selected}
      icon={data.provider === 'gitlab' ? '🦊' : '🐙'}
      title={group || repoName(data.url) || 'repo'}
      badge={data.provider}
    >
      <div className={styles.snippet}>
        {group
          ? count > 0
            ? `${count} of ${group}`
            : `all repos in ${group}`
          : data.url || 'no url'}
      </div>
    </NodeShell>
  )
}

function repoName(url: string): string {
  if (!url) return ''
  const parts = url.replace(/\/+$/, '').split('/')
  return parts.slice(-2).join('/')
}
