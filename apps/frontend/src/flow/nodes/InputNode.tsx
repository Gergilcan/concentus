import { Handle, type NodeProps, Position } from '@xyflow/react'
import type { Node } from '@xyflow/react'
import type { InputNodeData } from '../../api/types.ts'
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
    <div className={`${styles.node} ${styles.input} ${selected ? styles.selected : ''}`}>
      <div className={styles.header}>
        <span className={styles.icon}>▶</span>
        <span className={styles.title}>Input</span>
        <span className={styles.badge}>{LABEL[data.mode]}</span>
      </div>
      {data.mode === 'cron' && <div className={styles.meta}>{data.cron || 'no schedule'}</div>}
      {data.mode === 'webhook' ? (
        <div className={styles.snippetMuted}>starts on an external event</div>
      ) : data.mode === 'manual' ? (
        <div className={styles.snippetMuted}>you type the first message</div>
      ) : data.prompt ? (
        <div className={styles.snippet}>{data.prompt.slice(0, 80)}</div>
      ) : (
        <div className={styles.snippetMuted}>no prompt set</div>
      )}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}
