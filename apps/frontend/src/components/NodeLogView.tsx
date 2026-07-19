import { useMemo } from 'react'
import { useFlowStore } from '../state/store.ts'
import { agentKey } from '../utils/agentKey.ts'
import { cx } from '../utils/cx.ts'
import styles from './runs.module.scss'

function fmt(ts: number): string {
  return new Date(ts).toLocaleTimeString()
}

/**
 * One agent's own console output, shown inside its node inspector.
 *
 * <p>Reads the same event stream the Console does (held in the store) and keeps only the lines
 * this node produced, matched on the event's agentId — the canvas node id, which stays unique
 * even when two agents share a display name.
 */
export function NodeLogView({ nodeId, label }: { nodeId: string; label: string }) {
  const events = useFlowStore((s) => s.runEvents)
  const activeRunId = useFlowStore((s) => s.activeRunId)

  const mine = useMemo(() => events.filter((e) => agentKey(e) === nodeId), [events, nodeId])

  if (!activeRunId) {
    return <div className={styles.logMuted}>Select a run below to see this agent's output.</div>
  }
  if (mine.length === 0) {
    return <div className={styles.logMuted}>No output from {label} yet.</div>
  }

  return (
    <div className={styles.nodeLog}>
      {mine.map((e, i) => (
        <div key={i} className={cx(styles.line, styles['t_' + e.type])}>
          <span className={styles.lts}>{fmt(e.ts)}</span>
          <span className={styles.ltext}>{e.text}</span>
        </div>
      ))}
    </div>
  )
}
