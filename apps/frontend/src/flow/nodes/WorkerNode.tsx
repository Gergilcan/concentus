import type { Node, NodeProps } from '@xyflow/react'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

/** A plan-born worker's box data — synthesized from the run report, never saved with the flow. */
export type WorkerNodeData = {
  kind: 'worker'
  label: string
  model?: string
}

export type WorkerRFNode = Node<WorkerNodeData, 'worker'>

/**
 * A worker the coordinator's plan created at run time. It exists only in the run report — the
 * dashed border says "you did not draw this" — and disappears when another run is selected.
 */
export function WorkerNode({ id, data, selected }: NodeProps<WorkerRFNode>) {
  return (
    <NodeShell
      id={id}
      variant="worker"
      selected={selected}
      icon="⚙"
      title={data.label || id}
      badge="WORKER"
      showTargetHandle={false}
      showSourceHandle={false}
      showStatus
    >
      {data.model && <div className={styles.snippet}>{data.model}</div>}
    </NodeShell>
  )
}
