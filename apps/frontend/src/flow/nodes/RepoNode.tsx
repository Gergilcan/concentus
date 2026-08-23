import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { RepoRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function RepoNode({ data, selected }: NodeProps<RepoRFNode>) {
  const { t } = useTranslation()
  const group = (data.group ?? '').trim()
  // A group node has no URL to show, so the canvas shows what it stands for instead — otherwise a
  // perfectly configured group would read as "no url".
  const count = data.only?.length ?? 0
  let summary = data.url || t('no url')
  if (group)
    summary =
      count > 0
        ? t('{{n}} of {{group}}', { n: count, group })
        : t('all repos in {{group}}', { group })
  return (
    <NodeShell
      variant="repo"
      selected={selected}
      icon={data.provider === 'gitlab' ? '🦊' : '🐙'}
      title={group || repoName(data.url) || t('repo')}
      badge={data.provider}
    >
      <div className={styles.snippet}>{summary}</div>
    </NodeShell>
  )
}

function repoName(url: string): string {
  if (!url) return ''
  const parts = url.replace(/\/+$/, '').split('/')
  return parts.slice(-2).join('/')
}
