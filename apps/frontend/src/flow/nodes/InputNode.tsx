import type { NodeProps, Node } from '@xyflow/react'
import type { InputNodeData } from '../../api/types.ts'
import { describeCron } from '../../components/cron.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

type InputRFNode = Node<InputNodeData, 'input'>

const LABEL: Record<InputNodeData['mode'], string> = {
  manual: 'Manual',
  prompt: 'Prompt',
  cron: 'Automatic (cron)',
  webhook: 'Webhook',
  mail: 'Mail (IMAP)',
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
      {/* The schedule in words when it can be said in words ("Working days at 07:00"), the raw
          expression otherwise — with the expression on hover either way. */}
      {data.mode === 'cron' && (
        <div className={styles.meta} title={data.cron || undefined}>
          {data.cron ? (describeCron(data.cron) ?? data.cron) : 'no schedule'}
        </div>
      )}
      {/* The folder is what someone scanning the canvas needs to see — it is the thing that
          decides which mail this flow acts on. */}
      {data.mode === 'mail' && (
        <div className={styles.meta}>{data.mailFolder || 'no folder set'}</div>
      )}
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
