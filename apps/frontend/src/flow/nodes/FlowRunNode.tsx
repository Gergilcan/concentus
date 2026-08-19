import type { NodeProps } from '@xyflow/react'
import type { FlowRunRFNode } from '../nodeTypes.ts'
import { NodeShell } from './NodeShell.tsx'
import styles from './nodes.module.scss'

/**
 * Another flow, run from this one.
 *
 * The badge carries the distinction that matters and is otherwise invisible on the canvas: a node
 * wired to an agent is a TOOL the agent may decide to call, and the same node left terminal is a
 * hand-off that fires by itself when the run completes.
 */
export function FlowRunNode({ id, data, selected }: NodeProps<FlowRunRFNode>) {
  const handOff = data.mode === 'after'
  return (
    <NodeShell
      id={id}
      variant="flow"
      selected={selected}
      icon="🔗"
      title={data.label || 'flow'}
      badge={handOff ? 'AFTER' : 'TOOL'}
      showStatus
    >
      <div className={styles.snippet}>
        {!data.flowId
          ? 'no flow selected'
          : handOff
            ? 'runs when this flow finishes'
            : data.waitForResult
              ? 'the agent calls it and waits'
              : 'the agent starts it and moves on'}
      </div>
    </NodeShell>
  )
}
