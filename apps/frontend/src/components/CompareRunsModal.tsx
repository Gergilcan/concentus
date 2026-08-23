import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { api } from '../api/client.ts'
import type { NodeExec, RunComparison, RunComparisonSide } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { money } from '../utils/format.ts'
import { cx } from '../utils/cx.ts'
import { pctDelta, peakContext, stepSummary } from './compareRuns.ts'
import { contextParts, ctxColor } from './contextParts.ts'
import { ContextDialog } from './ContextDialog.tsx'
import { compact, timeAgo } from './flowFormat.ts'
import { Modal } from './Modal.tsx'
import { Spinner } from './Spinner.tsx'
import styles from './runs.module.scss'

const GOLD = '⭐'

/**
 * What to call one side. The golden reference keeps its name because that is what makes it the
 * baseline; anything else is identified the way a person picking it out of a list would — when it
 * ran, and what started it. "Candidate" was accurate only while every comparison had a reference.
 */
function sideLabel(side: RunComparisonSide, t: TFunction): string {
  if (side.run.golden) return `${GOLD} ${t('Golden')}`
  const trigger = side.run.trigger && side.run.trigger !== 'manual' ? ` · ${side.run.trigger}` : ''
  return `${timeAgo(side.run.createdAt)}${trigger}`
}

/**
 * Two runs side by side: cost, tokens, per-node steps and each run's final answer. Facts on both
 * sides, deltas where a baseline exists — whether a difference is a regression is the reader's
 * call, so nothing here says pass or fail.
 *
 * <p>The left side is whatever the reader chose to read the selected run against. It is usually
 * the golden reference and it does not have to be: two runs of the same block on different models
 * is a comparison with no known-good side at all.
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
  const { t } = useTranslation()
  const [cmp, setCmp] = useState<RunComparison | null>(null)
  const [err, setErr] = useState<string | null>(null)
  // The step whose /context breakdown is open, over this dialog. Both columns feed the same
  // slot: two breakdowns at once would be a comparison nobody asked for, stacked on a comparison.
  const [ctxNode, setCtxNode] = useState<NodeExec | null>(null)

  useEffect(() => {
    api
      .compareRuns(referenceId, candidateId)
      .then(setCmp)
      .catch((e) => setErr(errMessage(e)))
  }, [referenceId, candidateId])

  return (
    <Modal title={cmp && sideLabel(cmp.reference, t).startsWith(GOLD)
        ? t('Compared with the golden reference')
        : t('Two executions, side by side')} onClose={onClose} wide>
      {err && <div className={styles.err}>{err}</div>}
      {!cmp && !err && <Spinner />}
      {cmp && (
        <div className={styles.compare}>
          <MetricsTable reference={cmp.reference} candidate={cmp.candidate} />
          <CtxKey sides={[cmp.reference, cmp.candidate]} />
          <div className={styles.compareGrid}>
            <SideColumn side={cmp.reference} label={sideLabel(cmp.reference, t)} onOpenContext={setCtxNode} />
            <SideColumn side={cmp.candidate} label={sideLabel(cmp.candidate, t)} onOpenContext={setCtxNode} />
          </div>
        </div>
      )}
      {ctxNode && <ContextDialog exec={ctxNode} onClose={() => setCtxNode(null)} />}
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
  const { t } = useTranslation()
  // Context compares by its PEAK, not a sum: each node runs in its own window, and the fullest
  // one is what decides whether the flow fits. A side without recorded context shows an honest
  // dash — runs from before context tracking have nothing to compare.
  const refCtx = peakContext(reference.nodes)
  const candCtx = peakContext(candidate.nodes)
  const ctxLabel = (c: { tokens: number; window: number | null } | null): string =>
    c ? `${compact(c.tokens)}${c.window ? ` (${Math.round((c.tokens / c.window) * 100)}%)` : ''}` : '—'
  // stepSummary's counts are numbers plus statuses; only its fixed empty-state has words to translate.
  const steps = (nodes: NodeExec[]): string => {
    const s = stepSummary(nodes)
    return s === 'no steps' ? t('no steps') : s
  }
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
      ref: steps(reference.nodes),
      cand: steps(candidate.nodes),
      delta: null,
    },
  ]
  return (
    <table className={styles.compareMetrics}>
      <thead>
        <tr>
          <th />
          <th>{sideLabel(reference, t)}</th>
          <th>{sideLabel(candidate, t)}</th>
          <th title={t('The right-hand run relative to the left-hand one')}>Δ ⓘ</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.label}>
            <td>{t(r.label)}</td>
            <td>{r.ref}</td>
            <td>{r.cand}</td>
            <td>{r.delta ?? ''}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function SideColumn({
  side,
  label,
  onOpenContext,
}: {
  side: RunComparisonSide
  label: string
  onOpenContext: (node: NodeExec) => void
}) {
  const { t } = useTranslation()
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
          <NodeStep key={n.nodeId} node={n} onOpenContext={onOpenContext} />
        ))}
        {side.nodes.length === 0 && <li className={styles.muted}>{t('No recorded steps.')}</li>}
      </ul>
      <div className={styles.compareOutput}>
        {side.finalOutput ? (
          <pre>{side.finalOutput}</pre>
        ) : (
          <span className={styles.muted}>{t('This run produced no final answer.')}</span>
        )}
      </div>
    </section>
  )
}

/** One colour key for both columns — every step's bar below uses exactly these parts. */
function CtxKey({ sides }: { sides: { nodes: NodeExec[] }[] }) {
  const { t } = useTranslation()
  // Only the parts that actually occur in either run, so the key never promises unseen colours.
  const seen = new Map<string, { label: string; hue: number; hint: string }>()
  for (const side of sides)
    for (const n of side.nodes)
      for (const p of contextParts(n)) if (!seen.has(p.key)) seen.set(p.key, p)
  if (seen.size === 0) return null
  return (
    <div className={styles.ctxKey}>
      <span className={styles.muted}>{t('context:')}</span>
      {[...seen.values()].map((p) => (
        <span key={p.label} title={t(p.hint)}>
          <span className={styles.ctxSwatch} style={{ background: ctxColor(p.hue) }} />
          {t(p.label)}
        </span>
      ))}
      <span title={t("What is left of the model's context window.")}>
        <span className={cx(styles.ctxSwatch, styles.ctxSwatchFree)} />
        {t('free')}
      </span>
    </div>
  )
}

function NodeStep({
  node,
  onOpenContext,
}: {
  node: NodeExec
  onOpenContext: (node: NodeExec) => void
}) {
  const { t } = useTranslation()
  const tokens = (node.inputTokens ?? 0) + (node.outputTokens ?? 0)
  const ctx = node.contextTokens ?? 0
  const win = node.contextWindow ?? 0
  const parts = contextParts(node)
  const total = win > 0 ? win : ctx
  // The bar and the figure open the same dialog: whichever a reader aims at, the breakdown is
  // one click away, and neither is a dead decoration.
  const open = () => onOpenContext(node)
  const openLabel = t('Context breakdown for {{label}}', { label: node.label })
  return (
    <li className={styles.compareStep} title={node.error ?? undefined}>
      <span className={cx(styles.stepDot, styles['n_' + node.status])} />
      <span className={styles.stepLabel}>{node.label}</span>
      <span className={styles.stepMeta}>
        {tokens > 0 && t('{{n}} tok', { n: compact(tokens) })}
        {ctx > 0 && ' · '}
        {ctx > 0 && (
          <button type="button" className={styles.ctxOpen} onClick={open} title={openLabel}>
            {t('ctx')} {compact(ctx)}
            {win > 0 && ` (${Math.round((ctx / win) * 100)}%)`}
          </button>
        )}
        {node.estimatedCostUsd ? ` · ${money(node.estimatedCostUsd)}` : ''}
      </span>
      {parts.length > 0 && (
        <button type="button" className={styles.ctxMini} onClick={open} aria-label={openLabel}>
          {parts.map((p) => (
            <span
              key={p.key}
              title={`${t(p.label)}: ${p.approx ? '~' : ''}${t('{{n}} tokens', { n: p.value.toLocaleString() })}`}
              style={{ width: `${(p.value / total) * 100}%`, background: ctxColor(p.hue) }}
            />
          ))}
        </button>
      )}
    </li>
  )
}
