import { type CSSProperties, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { NodeExec } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { clockTime } from '../utils/format.ts'
import { hueOf } from '../utils/hueOf.ts'
import { compact } from './flowFormat.ts'
import { durationLabel, timelineRows } from './timeline.ts'
import styles from './runs.module.scss'

/**
 * The run as time rather than as text: one bar per block, laid over the run's own span.
 *
 * The console answers "what did it say"; this answers "what was actually happening, and when".
 * For a fan-out that is the difference between believing you got parallelism and seeing it —
 * overlapping bars are the evidence — and for anything else it is where the dead gaps show up:
 * the minute between two blocks that no transcript makes visible.
 *
 * Bars carry each agent's own hue, the same one its console chips and log lines wear, so a block
 * is recognisable across the three views without reading a single label.
 */
export function RunTimeline({ nodes, now }: {
  nodes: NodeExec[]
  /** Injected so a still-running bar has one clock for the whole render (and none in tests). */
  now?: number
}) {
  const { t } = useTranslation()
  const timeline = useMemo(() => timelineRows(nodes, now ?? Date.now()), [nodes, now])

  if (timeline.rows.length === 0) {
    return <div className={styles.logMuted}>{t('Nothing has run yet — blocks appear here as they start.')}</div>
  }

  return (
    <div className={styles.timeline}>
      <div className={styles.timelineAxis}>
        <span>{clockTime(timeline.startedAt)}</span>
        <span className={styles.timelineSpan}>
          {t('{{duration}} end to end', { duration: durationLabel(timeline.spanMs) })}
        </span>
        <span>{clockTime(timeline.endedAt)}</span>
      </div>
      <ul className={styles.timelineRows}>
        {timeline.rows.map((row) => (
          <li key={row.nodeId} className={styles.timelineRow}>
            <span className={styles.timelineLabel} title={row.label}>
              {row.label}
            </span>
            <span className={styles.timelineTrack}>
              <span
                className={cx(styles.timelineBar, styles['tl_' + row.status], row.running && styles.timelineRunning)}
                style={{
                  left: `${row.leftPct}%`,
                  width: `${row.widthPct}%`,
                  '--h': hueOf(row.label),
                } as CSSProperties}
                title={tooltip(t, row.label, row.startedAt, row.endedAt, row.durationMs, row.running,
                  nodes.find((n) => n.nodeId === row.nodeId))}
              />
            </span>
            <span className={styles.timelineDuration}>
              {durationLabel(row.durationMs)}
              {row.running && '…'}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Exact times and tokens, since the bar itself can only ever be approximate. */
function tooltip(t: TFunction, label: string, startedAt: number, endedAt: number, durationMs: number,
                 running: boolean, exec?: NodeExec): string {
  const when = running
    ? t('{{time}} → still running', { time: clockTime(startedAt) })
    : `${clockTime(startedAt)} → ${clockTime(endedAt)}`
  const tokens = exec && (exec.inputTokens || exec.outputTokens)
    ? ` · ${t('{{in}} in / {{out}} out', { in: compact(exec.inputTokens), out: compact(exec.outputTokens) })}`
    : ''
  return `${label}: ${when} (${durationLabel(durationMs)})${tokens}`
}
