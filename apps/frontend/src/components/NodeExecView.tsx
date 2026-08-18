import { useState } from 'react'
import type { NodeExec, NodeExecStatus } from '../api/types.ts'
import { money } from '../utils/format.ts'
import { cx } from '../utils/cx.ts'
import { ContextDialog } from './ContextDialog.tsx'
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

function TokenLine({ exec, onOpenContext }: { exec?: NodeExec; onOpenContext: () => void }) {
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
        <>
          {' '}·{' '}
          <button
            type="button"
            className={styles.ctxOpen}
            onClick={onOpenContext}
            title={
              `Context window in use after this block's latest message — its whole prompt plus ` +
              `what it wrote, like /context in Claude Code.` +
              (win > 0 ? ` ${fmt(ctx)} of ${fmt(win)} tokens.` : '') +
              (grew > 0
                ? ` It started at ${fmt(exec.contextStartTokens ?? 0)} (system prompt, tools, its task) and its work added ${fmt(grew)}.`
                : '') +
              ' Click for the full breakdown.'
            }
          >
            ctx {fmt(ctx)}
            {win > 0 && ` (${Math.round((ctx / win) * 100)}%)`}
            {grew > 0 && ` ↑${fmt(grew)}`}
          </button>
        </>
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
 * The answer itself: a declared table, the raw text, or — when there is neither — a line naming
 * which kind of nothing this is. A block still working and a block that finished empty are the
 * same blank box to the eye, so they are never allowed to share a message.
 */
function OutputBody({ exec }: { exec: NodeExec }) {
  if (exec.format === 'table' && exec.columns) {
    return (
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
    )
  }
  if (exec.output) return <pre className={styles.execText}>{exec.output}</pre>
  if (exec.status === 'running') return <div className={styles.empty}>Working…</div>
  return <div className={styles.empty}>No output produced.</div>
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
  // Declared before the early return: hooks cannot live behind a condition.
  const [ctxOpen, setCtxOpen] = useState(false)
  if (!exec) {
    return <div className={styles.empty}>No output yet. Run this flow and it appears live.</div>
  }
  return (
    <div>
      <div className={styles.execHead}>
        <StatusBadge status={exec.status} />
        <TokenLine exec={exec} onOpenContext={() => setCtxOpen(true)} />
      </div>

      {exec.error && <div className={styles.execError}>{exec.error}</div>}

      <OutputBody exec={exec} />

      {ctxOpen && <ContextDialog exec={exec} onClose={() => setCtxOpen(false)} />}
    </div>
  )
}
