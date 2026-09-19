import type { NodeProps } from '@xyflow/react'
import { useTranslation } from 'react-i18next'
import type { McpRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function McpNode({ id, data, selected }: NodeProps<McpRFNode>) {
  const { t } = useTranslation()
  // A stdio server has no url — the command IS where it lives. Saying "no url" for one described
  // a correctly wired npx server as unconfigured, which is the one thing a canvas must not do.
  const where = data.url || [data.command ?? '', ...(data.args ?? [])].join(' ').trim()
  return (
    <NodeShell id={id} variant="mcp" selected={selected} icon="⚙" title={data.name || t('mcp')} badge={t('MCP')} showStatus>
      <div className={where ? styles.snippet : styles.snippetMuted}>{where || t('no url')}</div>
    </NodeShell>
  )
}
