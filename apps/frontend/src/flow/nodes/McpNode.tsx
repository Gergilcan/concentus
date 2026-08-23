import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { McpRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function McpNode({ id, data, selected }: NodeProps<McpRFNode>) {
  const { t } = useTranslation()
  return (
    <NodeShell id={id} variant="mcp" selected={selected} icon="⚙" title={data.name || t('mcp')} badge={t('MCP')} showStatus>
      <div className={styles.snippet}>{data.url || t('no url')}</div>
    </NodeShell>
  )
}
