import type { NodeProps, Node } from '@xyflow/react'
import type { InputNodeData } from '../../api/types.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

type InputRFNode = Node<InputNodeData, 'input'>

const LABEL: Record<InputNodeData['mode'], string> = {
  manual: 'Manual',
  prompt: 'Prompt',
  cron: 'Automatic (cron)',
  webhook: 'Webhook',
}

export function InputNode({ data, selected }: NodeProps<InputRFNode>) {
  return (
    <NodeShell
      variant="input"
      selected={selected}
      showTargetHandle={false}
      icon="▶"
      title="Input"
      badge={LABEL[data.mode]}
    >
      {data.mode === 'cron' && <div className={styles.meta}>{data.cron || 'no schedule'}</div>}
      {data.mode === 'webhook' ? (
        <div className={styles.snippetMuted}>starts on an external event</div>
      ) : data.mode === 'manual' ? (
        <div className={styles.snippetMuted}>you type the first message</div>
      ) : data.prompt ? (
        // Full text, clamped by CSS — slicing here cut mid-word with nothing to show for it.
        <div className={styles.snippet} title={data.prompt}>
          {data.prompt}
        </div>
      ) : (
        <div className={styles.snippetMuted}>no prompt set</div>
      )}
    </NodeShell>
  )
}
