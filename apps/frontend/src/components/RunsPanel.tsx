import type { RunSummary } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { Console } from './Console.tsx'
import styles from './runs.module.scss'

/** Every trigger that isn't `manual` gets a badge; an unknown one falls back to its own name. */
const TRIGGER_LABEL: Record<string, string> = {
  cron: '⏱ auto',
  prompt: '▶ prompt',
  webhook: '⚡ hook',
  mail: '✉ mail',
}

interface Props {
  runs: RunSummary[]
  loading?: boolean
  selected: string | null
  onSelect: (id: string) => void
  /** The flow currently open on the canvas; null for an unsaved one. */
  flowId?: string | null
}

export function RunsPanel({ runs, loading = false, selected, onSelect, flowId = null }: Props) {
  // Only this flow's executions. The panel sits under the flow you are editing, so a list mixing
  // in every other flow's runs is noise you have to read past — and worse, it makes the run you
  // just started hard to find. An unsaved flow has no id, so its ad-hoc runs (which have none
  // either) are what it shows.
  const mine = runs.filter((r) => (r.flowId ?? null) === flowId)
  return (
    <section className={styles.runs}>
      <div className={styles.runList}>
        <h3 className={styles.h3}>Executions</h3>
        {loading ? (
          <div className={styles.muted} role="status">
            Loading executions…
          </div>
        ) : mine.length === 0 ? (
          <div className={styles.muted}>
            {runs.length > 0
              ? 'No executions for this flow yet. Press Run, or wait for its trigger.'
              : 'No executions yet. Design a flow and press Run.'}
          </div>
        ) : null}
        {!loading &&
          mine.map((r) => (
            <button
              key={r.id}
              className={cx(styles.runItem, selected === r.id && styles.active)}
              onClick={() => onSelect(r.id)}
            >
              <span className={cx(styles.dot, styles['s_' + r.status])} />
              <span className={styles.runName}>{r.flowName || 'flow'}</span>
              {r.trigger && r.trigger !== 'manual' && (
                <span className={styles.trigger}>{TRIGGER_LABEL[r.trigger] ?? r.trigger}</span>
              )}
              <span className={styles.runStatus}>{r.status}</span>
            </button>
          ))}
      </div>
      <div className={styles.runMain}>
        {selected ? (
          <Console runId={selected} status={mine.find((r) => r.id === selected)?.status} />
        ) : (
          <div className={styles.muted}>Select a run to see its output and send commands.</div>
        )}
      </div>
    </section>
  )
}
