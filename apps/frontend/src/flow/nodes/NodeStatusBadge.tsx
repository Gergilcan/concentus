import { cx } from '../../utils/cx.ts'
import { useFlowStore } from '../../state/store.ts'
import styles from './nodes.module.scss'

/** Live run status + output-token count for a node, shown on the canvas during/after a run. */
export function NodeStatusBadge({ id }: { id: string }) {
  const exec = useFlowStore((s) => s.runExecByNode[id])
  if (!exec) return null
  const tokens = exec.outputTokens ? ` · ${exec.outputTokens.toLocaleString()}t` : ''
  return (
    <>
      <div className={cx(styles.execBadge, styles['eb_' + exec.status])}>
        <span className={styles.ebDot} />
        {exec.status}
        {tokens}
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
