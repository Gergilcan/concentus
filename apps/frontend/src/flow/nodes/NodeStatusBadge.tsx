import { useFlowStore } from '../../state/store.ts'
import styles from './nodes.module.scss'

/** Live run status + output-token count for a node, shown on the canvas during/after a run. */
export function NodeStatusBadge({ id }: { id: string }) {
  const exec = useFlowStore((s) => s.runExecByNode[id])
  if (!exec) return null
  const tokens = exec.outputTokens ? ` · ${exec.outputTokens.toLocaleString()}t` : ''
  return (
    <div className={`${styles.execBadge} ${styles['eb_' + exec.status]}`}>
      <span className={styles.ebDot} />
      {exec.status}
      {tokens}
    </div>
  )
}
