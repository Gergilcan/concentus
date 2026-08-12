import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { NodeExec, RunComparison, RunComparisonSide } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { money } from '../utils/format.ts'
import { cx } from '../utils/cx.ts'
import { pctDelta, peakContext, stepSummary } from './compareRuns.ts'
import { contextParts, ctxColor } from './contextParts.ts'
import { compact, timeAgo } from './flowFormat.ts'
import { Modal } from './Modal.tsx'
import styles from './runs.module.scss'

/**
 * The golden reference and a candidate run, side by side: cost, tokens, per-node steps and each
 * run's final answer. Facts on both sides, deltas where a baseline exists — whether a difference
 * is a regression is the reader's call, so nothing here says pass or fail.
 */
export function CompareRunsModal({
  referenceId,
  candidateId,
  onClose,
}: {
  referenceId: string
  candidateId: string
  onClose: () => void
}) {
  const [cmp, setCmp] = useState<RunComparison | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    api
      .compareRuns(referenceId, candidateId)
      .then(setCmp)
      .catch((e) => setErr(errMessage(e)))
  }, [referenceId, candidateId])

  return (
    <Modal title="Compared with the golden reference" onClose={onClose} wide>
      {err && <div className={styles.err}>{err}</div>}
      {!cmp && !err && <div className={styles.muted}>Loading…</div>}
      {cmp && (
        <div className={styles.compare}>
          <MetricsTable reference={cmp.reference} candidate={cmp.candidate} />
          <CtxKey sides={[cmp.reference, cmp.candidate]} />
          <div className={styles.compareGrid}>
            <SideColumn side={cmp.reference} label="⭐ Golden" />
            <SideColumn side={cmp.candidate} label="Candidate" />
          </div>
        </div>
      )}
    </Modal>
  )
}

/** One headline row per number, with the delta beside the candidate when a baseline exists. */
function MetricsTable({
  reference,
  candidate,
}: {
  reference: RunComparisonSide
  candidate: RunComparisonSide
}) {
  // Context compares by its PEAK, not a sum: each node runs in its own window, and the fullest
  // one is what decides whether the flow fits. A side without recorded context shows an honest
  // dash — runs from before context tracking have nothing to compare.
  const refCtx = peakContext(reference.nodes)
  const candCtx = peakContext(candidate.nodes)
  const ctxLabel = (c: { tokens: number; window: number | null } | null): string =>
    c ? `${compact(c.tokens)}${c.window ? ` (${Math.round((c.tokens / c.window) * 100)}%)` : ''}` : '—'
  const rows: Array<{ label: string; ref: string; cand: string; delta: string | null }> = [
    {
      label: 'Cost',
      ref: money(reference.run.estimatedCostUsd ?? 0),
      cand: money(candidate.run.estimatedCostUsd ?? 0),
      delta: pctDelta(reference.run.estimatedCostUsd ?? 0, candidate.run.estimatedCostUsd ?? 0),
    },
    {
      label: 'Peak context',
      ref: ctxLabel(refCtx),
      cand: ctxLabel(candCtx),
      delta: refCtx && candCtx ? pctDelta(refCtx.tokens, candCtx.tokens) : null,
    },
    {
      label: 'Tokens in',
      ref: compact(reference.run.totalInputTokens ?? 0),
      cand: compact(candidate.run.totalInputTokens ?? 0),
      delta: pctDelta(reference.run.totalInputTokens ?? 0, candidate.run.totalInputTokens ?? 0),
    },
    {
      label: 'Tokens out',
      ref: compact(reference.run.totalOutputTokens ?? 0),
      cand: compact(candidate.run.totalOutputTokens ?? 0),
      delta: pctDelta(reference.run.totalOutputTokens ?? 0, candidate.run.totalOutputTokens ?? 0),
    },
    {
      label: 'Steps',
      ref: stepSummary(reference.nodes),
      cand: stepSummary(candidate.nodes),
      delta: null,
    },
  ]
  return (
    <table className={styles.compareMetrics}>
      <thead>
        <tr>
          <th />
          <th>⭐ Golden</th>
          <th>Candidate</th>
          <th title="Candidate relative to golden">Δ ⓘ</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.label}>
            <td>{r.label}</td>
            <td>{r.ref}</td>
            <td>{r.cand}</td>
            <td>{r.delta ?? ''}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function SideColumn({ side, label }: { side: RunComparisonSide; label: string }) {
  return (
    <section className={styles.compareSide}>
      <header className={styles.compareHead}>
        <b>{label}</b>
        <span className={styles.muted}>
          {side.run.status} · {timeAgo(side.run.createdAt)}
          {side.run.trigger && side.run.trigger !== 'manual' ? ` · ${side.run.trigger}` : ''}
        </span>
      </header>
      <ul className={styles.compareSteps}>
        {side.nodes.map((n) => (
          <NodeStep key={n.nodeId} node={n} />
        ))}
        {side.nodes.length === 0 && <li className={styles.muted}>No recorded steps.</li>}
      </ul>
      <div className={styles.compareOutput}>
        {side.finalOutput ? (
          <pre>{side.finalOutput}</pre>
        ) : (
          <span className={styles.muted}>This run produced no final answer.</span>
        )}
      </div>
    </section>
  )
}

/** One colour key for both columns — every step's bar below uses exactly these parts. */
function CtxKey({ sides }: { sides: { nodes: NodeExec[] }[] }) {
  // Only the parts that actually occur in either run, so the key never promises unseen colours.
  const seen = new Map<string, { label: string; hue: number; hint: string }>()
  for (const side of sides)
    for (const n of side.nodes)
      for (const p of contextParts(n)) if (!seen.has(p.key)) seen.set(p.key, p)
  if (seen.size === 0) return null
  return (
    <div className={styles.ctxKey}>
      <span className={styles.muted}>context:</span>
      {[...seen.values()].map((p) => (
        <span key={p.label} title={p.hint}>
          <span className={styles.ctxSwatch} style={{ background: ctxColor(p.hue) }} />
          {p.label}
        </span>
      ))}
      <span title="What is left of the model's context window.">
        <span className={cx(styles.ctxSwatch, styles.ctxSwatchFree)} />
        free
      </span>
    </div>
  )
}

function NodeStep({ node }: { node: NodeExec }) {
  const tokens = (node.inputTokens ?? 0) + (node.outputTokens ?? 0)
  const ctx = node.contextTokens ?? 0
  const win = node.contextWindow ?? 0
  const parts = contextParts(node)
  const total = win > 0 ? win : ctx
  return (
    <li className={styles.compareStep} title={node.error ?? undefined}>
      <span className={cx(styles.stepDot, styles['n_' + node.status])} />
      <span className={styles.stepLabel}>{node.label}</span>
      <span className={styles.stepMeta}>
        {tokens > 0 && `${compact(tokens)} tok`}
        {ctx > 0 && ` · ctx ${compact(ctx)}${win > 0 ? ` (${Math.round((ctx / win) * 100)}%)` : ''}`}
        {node.estimatedCostUsd ? ` · ${money(node.estimatedCostUsd)}` : ''}
      </span>
      {parts.length > 0 && (
        <span className={styles.ctxMini} aria-label={`Context breakdown for ${node.label}`}>
          {parts.map((p) => (
            <span
              key={p.key}
              title={`${p.label}: ${p.approx ? '~' : ''}${p.value.toLocaleString()} tokens`}
              style={{ width: `${(p.value / total) * 100}%`, background: ctxColor(p.hue) }}
            />
          ))}
        </span>
      )}
    </li>
  )
}
