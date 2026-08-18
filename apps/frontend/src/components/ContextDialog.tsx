import type { NodeExec } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { contextParts, ctxColor, freeContext } from './contextParts.ts'
import { compact } from './flowFormat.ts'
import { Modal } from './Modal.tsx'
import styles from './contextDialog.module.scss'

/**
 * One block's context window, laid out the way Claude Code's /context prints it: the model and
 * its occupancy on top, a stacked bar, then a category table with tokens and share.
 *
 * It is a dialog rather than an inline block because the breakdown is a five-row table nobody
 * reads while scanning a run — but the one number that matters (ctx N%) is on the row that opens
 * it. Both the node inspector and the golden comparison open this same dialog, so a part can
 * never be labelled or coloured differently in the two places.
 */
export function ContextDialog({ exec, onClose }: { exec: NodeExec; onClose: () => void }) {
  const parts = contextParts(exec)
  const ctx = exec.contextTokens ?? 0
  const win = exec.contextWindow ?? 0
  const free = freeContext(exec)
  // Shares are read against the model's window when it is known, and against the block's own
  // occupancy when it is not — either way the column adds up to 100% instead of to nothing.
  const total = win > 0 ? win : ctx
  const approx = parts.some((p) => p.approx)

  return (
    <Modal title="Context usage" onClose={onClose}>
      <div className={styles.dialog}>
        <div className={styles.model} title={`Context recorded for the “${exec.label}” block.`}>
          {exec.model ?? exec.label}
        </div>
        <div className={styles.headline}>
          {compact(ctx)}
          {win > 0 && ` / ${compact(win)}`} tokens
          {win > 0 && ` (${Math.round((ctx / win) * 100)}%)`}
        </div>

        <div className={styles.bar} aria-hidden>
          {parts.map((p) => (
            <span
              key={p.key}
              style={{ width: `${share(p.value, total)}%`, background: ctxColor(p.hue) }}
            />
          ))}
        </div>

        <table className={styles.table}>
          <thead>
            <tr>
              <th>Category</th>
              <th>Tokens</th>
              <th>Usage</th>
            </tr>
          </thead>
          <tbody>
            {parts.map((p) => (
              <tr key={p.key} title={p.hint}>
                <td>
                  <span className={styles.swatch} style={{ background: ctxColor(p.hue) }} />
                  {p.label}
                  {p.approx && ' ~'}
                </td>
                <td className={styles.num} title={`${p.value.toLocaleString()} tokens`}>
                  {compact(p.value)}
                </td>
                <td className={styles.num}>{pctLabel(p.value, total)}</td>
              </tr>
            ))}
            {free > 0 && (
              <tr title="What is left of the model's context window.">
                <td>
                  <span className={cx(styles.swatch, styles.swatchFree)} />
                  Free space
                </td>
                <td className={styles.num} title={`${free.toLocaleString()} tokens`}>
                  {compact(free)}
                </td>
                <td className={styles.num}>{pctLabel(free, total)}</td>
              </tr>
            )}
          </tbody>
        </table>

        {approx && (
          <p className={styles.note}>
            ~ estimated. The stream reports the starting prompt as a single number, so its split
            between system + tools and the task is derived from the recorded input at ≈4 characters
            per token. The other rows are measured from usage.
          </p>
        )}
      </div>
    </Modal>
  )
}

function share(value: number, total: number): number {
  return total > 0 ? (value / total) * 100 : 0
}

/** One decimal, like /context — with a floor label so a real part never renders as a flat 0%. */
function pctLabel(value: number, total: number): string {
  if (total <= 0) return '—'
  const p = share(value, total)
  if (p > 0 && p < 0.1) return '<0.1%'
  return `${p.toFixed(1)}%`
}
