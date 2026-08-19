import type { NodeProps } from '@xyflow/react'
import type { ConditionRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

const TEST_LABEL: Record<string, string> = {
  not_empty: 'answered anything',
  contains: 'contains',
  not_contains: 'does not contain',
  equals: 'is exactly',
  matches: 'matches',
}

export function ConditionNode({ id, data, selected }: NodeProps<ConditionRFNode>) {
  const test = TEST_LABEL[data.test] ?? data.test
  // The rule reads on the node itself. A gate whose box says only "if" makes the reader open the
  // inspector to learn what the flow does, which is the habit the node exists to break.
  const summary = data.test === 'not_empty' ? test : `${test} “${data.value || '…'}”`
  return (
    <NodeShell
      id={id}
      variant="condition"
      selected={selected}
      icon="⑂"
      title={data.label || 'if'}
      badge="IF"
    >
      <div className={styles.snippet}>{summary}</div>
    </NodeShell>
  )
}
