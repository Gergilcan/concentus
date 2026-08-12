import { useState } from 'react'
import { api } from '../api/client.ts'
import type { RunSummary } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { CompareRunsModal } from './CompareRunsModal.tsx'
import { Console } from './Console.tsx'
import styles from './runs.module.scss'

/** Every trigger that isn't `manual` gets a badge; an unknown one falls back to its own name. */
const TRIGGER_LABEL: Record<string, string> = {
  cron: '⏱ auto',
  prompt: '▶ prompt',
  webhook: '⚡ hook',
  mail: '✉ mail',
  golden: '⭐ golden',
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

  // The polled list is a few seconds behind a click; overriding locally keeps the star honest
  // until the next poll agrees. Keyed by run id, so the override survives list refreshes.
  const [goldenOverride, setGoldenOverride] = useState<Record<string, boolean>>({})
  const [compareWith, setCompareWith] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const isGolden = (r: RunSummary) => goldenOverride[r.id] ?? r.golden ?? false
  const goldenRun = mine.find(isGolden) ?? null

  const toggleGolden = async (r: RunSummary) => {
    setErr(null)
    try {
      const updated = await api.setGoldenRun(r.id, !isGolden(r))
      setGoldenOverride((prev) => {
        const next: Record<string, boolean> = { ...prev, [r.id]: updated.golden ?? false }
        // One reference per flow: promoting this run demoted whichever held the star.
        if (updated.golden) for (const other of mine) if (other.id !== r.id) next[other.id] = false
        return next
      })
    } catch (e) {
      setErr(errMessage(e))
    }
  }

  const rerunGolden = async () => {
    if (!goldenRun) return
    setBusy(true)
    setErr(null)
    try {
      const started = await api.goldenRerun(goldenRun.id)
      onSelect(started.id)
    } catch (e) {
      setErr(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const selectedRun = mine.find((r) => r.id === selected) ?? null
  const canCompare = goldenRun && selectedRun && selectedRun.id !== goldenRun.id

  return (
    <section className={styles.runs}>
      <div className={styles.runList}>
        <h3 className={styles.h3}>Executions</h3>
        {goldenRun && (
          <div className={styles.goldenBar}>
            <button
              className={styles.goldenAction}
              disabled={busy}
              title="Re-run the golden reference's input against the flow as it is saved now — then compare the two runs"
              onClick={() => void rerunGolden()}
            >
              ⭐▶ Test current flow
            </button>
            <button
              className={styles.goldenAction}
              disabled={!canCompare}
              title={
                canCompare
                  ? 'Compare the selected execution with the golden reference'
                  : 'Select another execution of this flow to compare it with the golden reference'
              }
              onClick={() => selectedRun && setCompareWith(selectedRun.id)}
            >
              ⇄ Compare
            </button>
          </div>
        )}
        {err && <div className={styles.err}>{err}</div>}
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
            // A div, not a button: the golden star nests inside, and a button may not contain
            // another button.
            <div
              key={r.id}
              role="button"
              tabIndex={0}
              className={cx(styles.runItem, selected === r.id && styles.active)}
              onClick={() => onSelect(r.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') onSelect(r.id)
              }}
            >
              <span className={cx(styles.dot, styles['s_' + r.status])} />
              <span className={styles.runName}>{r.flowName || 'flow'}</span>
              {r.trigger && r.trigger !== 'manual' && (
                <span className={styles.trigger}>{TRIGGER_LABEL[r.trigger] ?? r.trigger}</span>
              )}
              <span className={styles.runStatus}>{r.status}</span>
              {r.flowId && (
                <button
                  className={cx(styles.goldStar, isGolden(r) && styles.goldStarOn)}
                  title={
                    isGolden(r)
                      ? 'Golden reference — click to unmark'
                      : 'Mark as this flow’s golden reference'
                  }
                  aria-label={isGolden(r) ? 'Unmark golden reference' : 'Mark as golden reference'}
                  onClick={(e) => {
                    e.stopPropagation()
                    void toggleGolden(r)
                  }}
                >
                  {isGolden(r) ? '★' : '☆'}
                </button>
              )}
            </div>
          ))}
      </div>
      <div className={styles.runMain}>
        {selected ? (
          <Console runId={selected} status={mine.find((r) => r.id === selected)?.status} />
        ) : (
          <div className={styles.runEmpty}>Select a run to see its output and send commands.</div>
        )}
      </div>
      {compareWith && goldenRun && (
        <CompareRunsModal
          referenceId={goldenRun.id}
          candidateId={compareWith}
          onClose={() => setCompareWith(null)}
        />
      )}
    </section>
  )
}
