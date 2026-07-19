import type { RunSummary } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { kindOf, timeAgo } from './flowFormat.ts'
import styles from './flows.module.scss'

/** Sidebar list of the most recent executions across all flows, each re-runnable in place. */
export function RecentRunsList({
  runs,
  onOpenRun,
  onRetryRun,
}: {
  runs: RunSummary[]
  onOpenRun: (runId: string) => void
  onRetryRun: (runId: string) => void
}) {
  if (runs.length === 0) return <div className={styles.sideEmpty}>Nothing has run yet.</div>

  return (
    <ul className={styles.runList}>
      {runs.map((r) => (
        <li key={r.id} className={styles.runItem}>
          <button className={styles.runRow} onClick={() => onOpenRun(r.id)}>
            <span className={cx(styles.dot, styles['k_' + kindOf(r.status)])} />
            <span className={styles.runFlow}>{r.flowName || 'flow'}</span>
            {r.trigger && r.trigger !== 'manual' && <span className={styles.runTrigger}>{r.trigger}</span>}
            <span className={styles.runTime}>{timeAgo(r.createdAt)}</span>
          </button>
          <button className={styles.retry} title="Re-run with the same input" onClick={() => onRetryRun(r.id)}>
            ⟳
          </button>
        </li>
      ))}
    </ul>
  )
}
