import type { NodeProps } from '@xyflow/react'
import type { McpRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

export function McpNode({ id, data, selected }: NodeProps<McpRFNode>) {
  return (
    <NodeShell id={id} variant="mcp" selected={selected} icon="⚙" title={data.name || 'mcp'} badge="MCP" showStatus>
      <div className={styles.snippet}>{data.url || 'no url'}</div>
    </NodeShell>
  )
}
