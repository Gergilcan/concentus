import { cx } from '../../utils/cx.ts'
import { useFlowStore } from '../../state/store.ts'
import { compact } from '../../components/flowFormat.ts'
import { money } from '../../utils/format.ts'
import styles from './nodes.module.scss'

/** Live run status + output-token count for a node, shown on the canvas during/after a run. */
export function NodeStatusBadge({ id }: { id: string }) {
  const exec = useFlowStore((s) => s.runExecByNode[id])
  if (!exec) return null
  const tokens = exec.outputTokens ? ` · ${exec.outputTokens.toLocaleString()}t` : ''
  // What this block cost, on the block. The run knew it all along (NodeExec carries the
  // estimate), but reading it meant opening the details dialog — and the moment someone asks
  // "which box made this run expensive", the canvas IS the report they are looking at.
  const cost = exec.estimatedCostUsd ?? 0
  const costLabel = cost > 0 ? ` · ${cost < 0.005 ? '<$0.01' : money(cost)}` : ''
  // Context occupancy, like /context: a percentage when the model's window is known, the raw
  // count when it isn't. The exact figures live in the title — the badge has no room for them.
  const ctx = exec.contextTokens ?? 0
  const win = exec.contextWindow ?? 0
  const started = exec.contextStartTokens ?? 0
  let ctxLabel = ''
  let ctxTitle: string | undefined
  if (ctx > 0) {
    ctxLabel = win > 0 ? ` · ctx ${Math.round((ctx / win) * 100)}%` : ` · ctx ${compact(ctx)}`
    const grew = ctx - started
    ctxTitle =
      `Context window in use: ${ctx.toLocaleString()}${win > 0 ? ` of ${win.toLocaleString()}` : ''} tokens` +
      (grew > 0 ? ` — started at ${started.toLocaleString()}, its work added ${grew.toLocaleString()}` : '')
  }
  return (
    <>
      <div
        className={cx(styles.execBadge, styles['eb_' + exec.status])}
        title={cx(
          ctxTitle,
          cost > 0 &&
            `${(exec.inputTokens + exec.outputTokens).toLocaleString()} tokens (${exec.inputTokens.toLocaleString()} in, ${exec.outputTokens.toLocaleString()} out) · estimated ${money(cost)} at this model's rates. On a subscription, read it as equivalent usage.`,
        ) || undefined}
      >
        <span className={styles.ebDot} />
        {exec.status}
        {tokens}
        {costLabel}
        {ctxLabel}
      </div>
      {exec.verdict && (
        // A second badge, not a status override: the worker finished (that is the first badge's
        // truth) and the verifier then judged its output (this one's).
        <div
          className={cx(styles.execBadge, styles['vd_' + exec.verdict])}
          title={
            exec.verdict === 'rejected'
              ? `Rejected by the verifier: ${exec.verdictReason ?? 'no reason recorded'}. This output was withheld from the merge.`
              : 'The verifier tried to reject this output and could not fault it.'
          }
        >
          ⚖ {exec.verdict}
        </div>
      )}
    </>
  )
}
