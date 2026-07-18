import type { NodeExec, NodeExecStatus } from '../api/types.ts'
import styles from './panels.module.scss'

const STATUS_LABEL: Record<NodeExecStatus, string> = {
  pending: 'Pending',
  running: 'Running',
  passed: 'Passed',
  failed: 'Failed',
}

export function StatusBadge({ status }: { status?: NodeExecStatus }) {
  const s = status ?? 'pending'
  return <span className={`${styles.statusPill} ${styles['st_' + s]}`}>{STATUS_LABEL[s]}</span>
}

function fmt(n: number): string {
  return (n ?? 0).toLocaleString()
}

export function TokenLine({ exec }: { exec?: NodeExec }) {
  if (!exec) return null
  return (
    <div className={styles.tokenLine}>
      tokens · in ≈{fmt(exec.inputTokens)} · out {fmt(exec.outputTokens)}
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
