import { useTranslation } from 'react-i18next'
import { cx } from '../../utils/cx.ts'
import { useFlowStore } from '../../state/store.ts'
import styles from './nodes.module.scss'

/** The replay vocabulary, in words a person standing at the canvas would use. */
const NOW: Record<string, string> = {
  'would-run': 'would run now',
  'would-skip': 'would not run now',
  unknown: 'cannot be decided',
}

const THEN: Record<string, string> = {
  ran: 'ran',
  failed: 'failed',
  skipped: 'did not run',
  absent: 'was not drawn yet',
}

/**
 * What the replay concluded about this block, on the block.
 *
 * Rendered only where the conclusion is worth a chip: a divergence, or an honest "cannot be
 * decided". Painting "same as before" on every box would bury the three that changed — the ring
 * around the card (canvas-overrides.css) already carries the at-a-glance part.
 */
export function NodeReplayBadge({ id }: { id: string }) {
  const { t } = useTranslation()
  const entry = useFlowStore((s) => s.replay?.nodes.find((n) => n.nodeId === id))
  if (!entry) return null
  if (!entry.divergent && entry.now !== 'unknown') return null
  const then = t(THEN[entry.then] ?? entry.then)
  const now = t(NOW[entry.now] ?? entry.now)
  return (
    <div
      className={cx(styles.execBadge, entry.divergent ? styles.rpDivergent : styles.rpUnknown)}
      title={entry.reason ?? undefined}
    >
      ⟲ {then} → {now}
    </div>
  )
}
