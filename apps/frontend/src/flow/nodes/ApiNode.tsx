import type { NodeProps } from '@xyflow/react'
import type { ApiRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function ApiNode({ id, data, selected }: NodeProps<ApiRFNode>) {
  let summary: string
  // An endpoint node says which call it is, because that is the whole node — an ops count would
  // be a number about a specification this node does not have.
  if (data.mode === 'endpoint') {
    summary = data.url ? `${data.method ?? 'GET'} ${data.url}` : 'no URL yet'
  } else if (data.ops.length > 0) summary = `${data.ops.length} operation(s) allowed`
  else if (data.specUrl) summary = 'no operations allowed yet'
  else summary = 'no spec loaded'
  return (
    <NodeShell
      altHandle={{ id: 'error', label: 'on error', tone: 'error' }}
      id={id}
      variant="api"
      selected={selected}
      icon="🌐"
      title={data.label || 'api'}
      badge="API"
      showStatus
    >
      <div className={styles.snippet}>{summary}</div>
    </NodeShell>
  )
}
