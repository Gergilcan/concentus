import type { RunSummary } from '../api/types.ts'
import { Console } from './Console.tsx'
import styles from './runs.module.scss'

interface Props {
  runs: RunSummary[]
  loading?: boolean
  selected: string | null
  onSelect: (id: string) => void
}

export function RunsPanel({ runs, loading = false, selected, onSelect }: Props) {
  return (
    <section className={styles.runs}>
      <div className={styles.runList}>
        <h3 className={styles.h3}>Executions</h3>
        {loading ? (
          <div className={styles.muted} role="status">
            Loading executions…
          </div>
        ) : runs.length === 0 ? (
          <div className={styles.muted}>No executions yet. Design a flow and press Run.</div>
        ) : null}
        {!loading &&
          runs.map((r) => (
            <button
              key={r.id}
              className={`${styles.runItem} ${selected === r.id ? styles.active : ''}`}
              onClick={() => onSelect(r.id)}
            >
              <span className={`${styles.dot} ${styles['s_' + r.status]}`} />
              <span className={styles.runName}>{r.flowName || 'flow'}</span>
              {r.trigger && r.trigger !== 'manual' && (
                <span className={styles.trigger}>{r.trigger === 'cron' ? '⏱ auto' : '▶ prompt'}</span>
              )}
              <span className={styles.runStatus}>{r.status}</span>
            </button>
          ))}
      </div>
      <div className={styles.runMain}>
        {selected ? (
          <Console runId={selected} />
        ) : (
          <div className={styles.muted}>Select a run to see its output and send commands.</div>
        )}
      </div>
    </section>
  )
}
