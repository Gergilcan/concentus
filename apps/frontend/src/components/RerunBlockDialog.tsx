import { useState } from 'react'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import { useFlowStore } from '../state/store.ts'
import { Modal } from './Modal.tsx'
import { Spinner } from './Spinner.tsx'
import styles from './flows.module.scss'

/**
 * Run one block again, on its own, with the input it received — editable first.
 *
 * <p>Tuning a sub-agent's prompt used to cost a whole execution: to see what the third block did
 * with a different instruction you re-ran the first two, paid for them again, and waited. The
 * input that block actually received reproduces its conditions exactly, so the same work costs one
 * block. That is also why the box opens pre-filled rather than empty — the recorded input is the
 * expensive part to reproduce, and retyping it from memory reproduces something else.
 */
export function RerunBlockDialog({
  runId,
  nodeId,
  label,
  recordedInput,
  onClose,
}: {
  runId: string
  nodeId: string
  label: string
  recordedInput: string
  onClose: () => void
}) {
  const [input, setInput] = useState(recordedInput)
  const [downstream, setDownstream] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const setActiveRun = useFlowStore((s) => s.setActiveRun)

  const run = async () => {
    if (!input.trim()) return
    setBusy(true)
    setError(null)
    try {
      const started = await api.rerunBlock(runId, nodeId, input, downstream)
      // Straight to the new run: the reason to press this button is to watch what the block does
      // differently, and leaving the old run selected shows the old answer.
      setActiveRun(started.id)
      onClose()
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const edited = input !== recordedInput

  return (
    <Modal title={`Run “${label}” again`} onClose={onClose}>
      <label className={styles.describeLabel} htmlFor="rerun-input">
        The input this block received
      </label>
      <textarea
        id="rerun-input"
        className={styles.describeInput}
        rows={10}
        autoFocus
        disabled={busy}
        value={input}
        onChange={(e) => setInput(e.target.value)}
      />
      <label className={styles.checkRow}>
        <input
          type="checkbox"
          checked={downstream}
          disabled={busy}
          onChange={(e) => setDownstream(e.target.checked)}
        />
        <span>
          Also the agents it delegates to
          <b className={styles.checkHint}>
            {' '}
            — for continuing a failed run from here, rather than tuning this block alone.
          </b>
        </span>
      </label>
      <p className={styles.describeHint}>
        Runs as a <b>new execution</b> containing this block alone{downstream ? ' and what it delegates to' : ''}.
        The flow&rsquo;s trigger and its hand-offs stay behind: nothing downstream of the flow fires
        from a block you are still tuning.
        {edited && <b> Your edited input is not saved to the flow.</b>}
      </p>
      {error && <p className={styles.describeError}>{error}</p>}
      <div className={styles.modalActions}>
        <button className={styles.ghost} onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button
          className={styles.primary}
          onClick={() => void run()}
          disabled={busy || !input.trim()}
        >
          {busy ? <Spinner label="Starting" /> : 'Run this block'}
        </button>
      </div>
    </Modal>
  )
}
