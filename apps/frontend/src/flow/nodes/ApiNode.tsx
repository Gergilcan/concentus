import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { ApiRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function ApiNode({ id, data, selected }: NodeProps<ApiRFNode>) {
  const { t } = useTranslation()
  let summary: string
  // An endpoint node says which call it is, because that is the whole node — an ops count would
  // be a number about a specification this node does not have.
  if (data.mode === 'endpoint') {
    summary = data.url ? `${data.method ?? 'GET'} ${data.url}` : t('no URL yet')
  } else if (data.ops.length > 0) summary = t('{{n}} operation(s) allowed', { n: data.ops.length })
  else if (data.specUrl) summary = t('no operations allowed yet')
  else summary = t('no spec loaded')
  return (
    <NodeShell
      altHandles={[{ id: 'error', label: t('on error'), tone: 'error', optional: { flag: 'errorOutput', enabled: !!data.errorOutput } }]}
      id={id}
      variant="api"
      selected={selected}
      icon="🌐"
      title={data.label || t('api')}
      badge={t('API')}
      showStatus
    >
      <div className={styles.snippet}>{summary}</div>
    </NodeShell>
  )
}
