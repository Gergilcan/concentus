import type { NodeExec, NodeExecStatus } from '../api/types.ts'
import { money } from '../utils/format.ts'
import { cx } from '../utils/cx.ts'
import { contextParts, ctxColor, freeContext } from './contextParts.ts'
import styles from './panels.module.scss'

const STATUS_LABEL: Record<NodeExecStatus, string> = {
  pending: 'Pending',
  running: 'Running',
  passed: 'Passed',
  failed: 'Failed',
}

function StatusBadge({ status }: { status?: NodeExecStatus }) {
  const s = status ?? 'pending'
  return <span className={cx(styles.statusPill, styles['st_' + s])}>{STATUS_LABEL[s]}</span>
}

function fmt(n: number): string {
  return (n ?? 0).toLocaleString()
}

function TokenLine({ exec }: { exec?: NodeExec }) {
  if (!exec) return null
  // Cached tokens are shown apart from fresh input rather than added into it: resuming a session
  // re-reads the whole history from cache each turn, so cache reads dwarf everything else while
  // costing about a tenth as much. Summing them into "in" made runs look far more expensive.
  const cached = (exec.cacheReadTokens ?? 0) + (exec.cacheWriteTokens ?? 0)
  const cost = exec.estimatedCostUsd
  const ctx = exec.contextTokens ?? 0
  const win = exec.contextWindow ?? 0
  // What this block's own work added on top of the context it was handed. 0 for a
  // single-message block — its one turn IS its starting context.
  const grew = ctx > 0 ? ctx - (exec.contextStartTokens ?? 0) : 0
  return (
    <div className={styles.tokenLine}>
      tokens · in {fmt(exec.inputTokens)} · out {fmt(exec.outputTokens)}
      {cached > 0 && (
        <span title="Prompt re-read from cache each turn — billed at roughly a tenth of the input rate">
          {' '}· cached {fmt(cached)}
        </span>
      )}
      {ctx > 0 && (
        <span
          title={
            `Context window in use after this block's latest message — its whole prompt plus ` +
            `what it wrote, like /context in Claude Code.` +
            (win > 0 ? ` ${fmt(ctx)} of ${fmt(win)} tokens.` : '') +
            (grew > 0
              ? ` It started at ${fmt(exec.contextStartTokens ?? 0)} (system prompt, tools, its task) and its work added ${fmt(grew)}.`
              : '')
          }
        >
          {' '}· ctx {fmt(ctx)}
          {win > 0 && ` (${Math.round((ctx / win) * 100)}%)`}
          {grew > 0 && ` ↑${fmt(grew)}`}
        </span>
      )}
      {cost !== undefined && cost !== null && (
        <span
          title={
            `Estimated at ${exec.model ?? 'the configured'} rates, with cached tokens weighted. ` +
            `Runs on a Claude subscription have no per-token bill — treat this as equivalent usage.`
          }
        >
          {' '}· ≈{money(cost)}
        </span>
      )}
    </div>
  )
}

/**
 * A /context-style split of the block's window — bar plus legend. The parts (and their colors)
 * come from contextParts.ts, shared with the golden comparison so the two never disagree.
 */
function CtxBreakdown({ exec }: { exec: NodeExec }) {
  const parts = contextParts(exec)
  if (parts.length === 0) return null
  const win = exec.contextWindow ?? 0
  const free = freeContext(exec)
  const total = win > 0 ? win : (exec.contextTokens ?? 0)
  return (
    <div className={styles.ctxBox}>
      <div className={styles.ctxBar} aria-hidden>
        {parts.map((p) => (
          <span
            key={p.key}
            style={{ width: `${(p.value / total) * 100}%`, background: ctxColor(p.hue) }}
          />
        ))}
      </div>
      <div className={styles.ctxLegend}>
        {parts.map((p) => (
          <span key={p.key} title={p.hint}>
            <span className={styles.ctxSwatch} style={{ background: ctxColor(p.hue) }} />
            {p.label} {p.approx && '~'}
            {fmt(p.value)}
          </span>
        ))}
        {free > 0 && (
          <span title="What is left of the model's context window.">
            free {fmt(free)} ({Math.round((free / win) * 100)}%)
          </span>
        )}
      </div>
    </div>
  )
}

export function InputView({ exec }: { exec?: NodeExec }) {
  if (!exec || !exec.input) {
    return <div className={styles.empty}>No input recorded yet for this run.</div>
  }
  return (
    <div>
      <StatusBadge status={exec.status} />
      <pre className={styles.execText}>{exec.input}</pre>
    </div>
  )
}

export function OutputView({ exec }: { exec?: NodeExec }) {
  if (!exec) {
    return <div className={styles.empty}>No output yet. Run this flow and it appears live.</div>
  }
  return (
    <div>
      <div className={styles.execHead}>
        <StatusBadge status={exec.status} />
        <TokenLine exec={exec} />
      </div>

      <CtxBreakdown exec={exec} />

      {exec.error && <div className={styles.execError}>{exec.error}</div>}

      {exec.format === 'table' && exec.columns ? (
        <div className={styles.previewTable}>
          <table>
            <thead>
              <tr>
                {exec.columns.map((c) => (
                  <th key={c}>{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {(exec.rows ?? []).map((row, i) => (
                <tr key={i}>
                  {row.map((cell, j) => (
                    <td key={j}>{cell}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : exec.output ? (
        <pre className={styles.execText}>{exec.output}</pre>
      ) : exec.status === 'running' ? (
        <div className={styles.empty}>Working…</div>
      ) : (
        <div className={styles.empty}>No output produced.</div>
      )}
    </div>
  )
}
