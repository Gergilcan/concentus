import type { NodeExec } from '../api/types.ts'

/**
 * Laying a run out in time: one bar per block, positioned as a percentage of the run's own span.
 *
 * Percentages rather than pixels because the panel is resizable and the bars must reflow with it;
 * a layout computed in pixels would need a measurement pass and would be wrong for one frame after
 * every drag. Kept as a pure function so the arithmetic — which is the part that can be subtly
 * wrong — is testable without rendering anything.
 */

export interface TimelineRow {
  nodeId: string
  label: string
  kind: string
  status: string
  /** Percentages of the run's span, 0-100. */
  leftPct: number
  widthPct: number
  startedAt: number
  /** 0 while the block is still going. */
  endedAt: number
  durationMs: number
  /** Still running: the bar reaches "now" and means "so far", not "this long". */
  running: boolean
}

export interface Timeline {
  rows: TimelineRow[]
  /** First start and last end across the run — the axis. */
  startedAt: number
  endedAt: number
  spanMs: number
}

/** A bar this narrow is still a bar: a 200ms block in a ten-minute run must not vanish. */
const MIN_WIDTH_PCT = 0.6

/**
 * Blocks in the order they started, with their bars placed across the run's own span.
 *
 * @param nodes the run's per-block records
 * @param now   what "still running" means — passed in rather than read here so the layout is
 *              deterministic in tests and so every bar in one render shares a single clock
 */
export function timelineRows(nodes: NodeExec[], now: number): Timeline {
  // A block that never started has no place on a time axis. It is not an error — a pending node
  // simply has not happened yet — so it is left out rather than drawn at zero.
  const started = nodes.filter((n) => n.startedAt > 0)
  if (started.length === 0) return { rows: [], startedAt: 0, endedAt: 0, spanMs: 0 }

  const startedAt = Math.min(...started.map((n) => n.startedAt))
  const endedAt = Math.max(...started.map((n) => endOf(n, now)))
  // Never zero: a run whose blocks all started and ended within the same millisecond would divide
  // by zero and render bars of NaN width.
  const spanMs = Math.max(1, endedAt - startedAt)

  const rows = started
    .slice()
    .sort((a, b) => a.startedAt - b.startedAt || a.label.localeCompare(b.label))
    .map((n) => {
      const end = endOf(n, now)
      // Clamped at zero: a block whose recorded end precedes its start (a clock step, a restored
      // run) draws as a sliver rather than as a bar running backwards off the panel.
      const durationMs = Math.max(0, end - n.startedAt)
      return {
        nodeId: n.nodeId,
        label: n.label,
        kind: n.kind,
        status: n.status,
        leftPct: ((n.startedAt - startedAt) / spanMs) * 100,
        widthPct: Math.max(MIN_WIDTH_PCT, (durationMs / spanMs) * 100),
        startedAt: n.startedAt,
        endedAt: n.endedAt,
        durationMs,
        running: n.endedAt <= 0,
      }
    })

  return { rows, startedAt, endedAt, spanMs }
}

/** Where a block's bar ends: its own end, or now while it is still going. */
function endOf(node: NodeExec, now: number): number {
  return node.endedAt > 0 ? node.endedAt : Math.max(node.startedAt, now)
}

/** Compact duration for a bar's label: 940ms, 8.4s, 2m 05s. */
export function durationLabel(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms >= 10_000 ? 0 : 1)}s`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.round((ms % 60_000) / 1000)
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`
}
