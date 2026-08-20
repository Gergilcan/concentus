import { useState } from 'react'
import { api } from '../api/client.ts'
import type { RunSummary } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { money } from '../utils/format.ts'
import { CompareRunsModal } from './CompareRunsModal.tsx'
import { ComparePickerModal } from './ComparePickerModal.tsx'
import { Console } from './Console.tsx'
import { Spinner } from './Spinner.tsx'
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
  // The run the selected one is compared AGAINST. Null while nothing is being compared.
  const [compareWith, setCompareWith] = useState<string | null>(null)
  const [picking, setPicking] = useState(false)
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
  // Anything the selected run can be read against: this flow's other executions. Comparing used
  // to be available only against the golden reference, which is the right default and the wrong
  // only option — two runs of the same block on different models is the pair worth reading now.
  const others = mine.filter((r) => selectedRun && r.id !== selectedRun.id)
  const canCompare = selectedRun !== null && others.length > 0

  const startCompare = () => {
    if (!canCompare) return
    // One other run means there is no choice to present, and a dialog offering a single option is
    // a click that teaches nothing.
    if (others.length === 1) setCompareWith(others[0].id)
    else setPicking(true)
  }

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
          </div>
        )}
        {/* Outside the golden bar on purpose: comparing two ordinary runs is the common case, and
            it used to be unreachable for a flow that had never marked a reference. */}
        <div className={styles.goldenBar}>
          <button
            className={styles.goldenAction}
            disabled={!canCompare}
            title={
              canCompare
                ? 'Compare the selected execution with another one — the golden reference, or any other run of this flow'
                : 'Select an execution, with at least one other to read it against'
            }
            onClick={startCompare}
          >
            ⇄ Compare
          </button>
        </div>
        {err && <div className={styles.err}>{err}</div>}
        {loading ? (
          <Spinner />
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
              {!!r.flowVersion && (
                <span
                  className={styles.version}
                  title={`Ran flow version ${r.flowVersion}. Opening this execution puts exactly that revision on the canvas.`}
                >
                  v{r.flowVersion}
                </span>
              )}
              <span className={styles.runStatus}>{r.status}</span>
              {/* Who started it, where an argument about who started it would be had. Absent for a
                  schedule or a webhook: the trigger badge beside this already says what they
                  were, and a name invented for them would be worse than the gap. */}
              {r.startedBy && (
                <span className={styles.runCost} title={`Started by ${r.startedBy}`}>
                  {r.startedBy.split('@')[0]}
                </span>
              )}
              {/* What this execution cost, where the choice to run another one is made. It was
                  only visible inside a comparison, which is one click and one decision too late
                  for the question it answers. */}
              {!!r.estimatedCostUsd && (
                <span
                  className={styles.runCost}
                  title="Estimated at this run's own model rates. On a Claude subscription there is no per-token bill — read it as equivalent usage."
                >
                  {money(r.estimatedCostUsd)}
                </span>
              )}
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
          <Console
            runId={selected}
            status={selectedRun?.status}
            flowVersion={selectedRun?.flowVersion}
          />
        ) : (
          <div className={styles.runEmpty}>Select a run to see its output and send commands.</div>
        )}
      </div>
      {picking && selectedRun && (
        <ComparePickerModal
          runs={others}
          goldenId={goldenRun?.id ?? null}
          onPick={(id) => {
            setPicking(false)
            setCompareWith(id)
          }}
          onClose={() => setPicking(false)}
        />
      )}
      {compareWith && selectedRun && (
        <CompareRunsModal
          referenceId={compareWith}
          candidateId={selectedRun.id}
          onClose={() => setCompareWith(null)}
        />
      )}
    </section>
  )
}
