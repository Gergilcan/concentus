import type { RunSummary } from '../api/types.ts'
import { money } from '../utils/format.ts'
import { timeAgo } from './flowFormat.ts'
import { Modal } from './Modal.tsx'
import styles from './runs.module.scss'

/**
 * Which execution to compare the selected one against.
 *
 * <p>Comparing used to mean one thing: this run against the golden reference. That is the right
 * default and the wrong only option — the pairs worth reading are usually neither of them golden.
 * Two runs of the same block on different models, the run before a prompt edit and the run after,
 * this morning's cron against yesterday's: all of those are "these two", and none of them is
 * "this one against the known-good one".
 *
 * <p>The golden run sits first when there is one, because it is still the answer more often than
 * any single other row.
 */
export function ComparePickerModal({
  runs,
  goldenId,
  onPick,
  onClose,
}: {
  /** The flow's other executions — the selected one is not among them. */
  runs: RunSummary[]
  goldenId: string | null
  onPick: (runId: string) => void
  onClose: () => void
}) {
  const ordered = [...runs].sort((a, b) => {
    if (a.id === goldenId) return -1
    if (b.id === goldenId) return 1
    return b.createdAt - a.createdAt
  })

  return (
    <Modal title="Compare with which execution?" onClose={onClose}>
      <div className={styles.pickList}>
        {ordered.map((r) => (
          <button key={r.id} className={styles.pickRow} onClick={() => onPick(r.id)}>
            <span className={styles.pickWhen}>
              {r.id === goldenId && <b>⭐ </b>}
              {timeAgo(r.createdAt)}
            </span>
            <span className={styles.pickMeta}>
              {r.status}
              {r.trigger && r.trigger !== 'manual' ? ` · ${r.trigger}` : ''}
              {r.flowVersion ? ` · v${r.flowVersion}` : ''}
              {r.estimatedCostUsd ? ` · ${money(r.estimatedCostUsd)}` : ''}
            </span>
          </button>
        ))}
      </div>
    </Modal>
  )
}
