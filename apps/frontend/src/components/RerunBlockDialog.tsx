import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import { useFlowStore } from '../state/store.ts'
import { ModelField } from './ModelField.tsx'
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
  const { t } = useTranslation()
  const [input, setInput] = useState(recordedInput)
  const [downstream, setDownstream] = useState(false)
  // Blank means the model the block already names. The two knobs worth turning on a block are its
  // instruction and its model, and until now the second one cost an edit to the flow plus a whole
  // execution to see — which is expensive enough that people guess instead of measuring.
  const [model, setModel] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const setActiveRun = useFlowStore((s) => s.setActiveRun)

  const run = async () => {
    if (!input.trim()) return
    setBusy(true)
    setError(null)
    try {
      const started = await api.rerunBlock(runId, nodeId, input, downstream, model)
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
    <Modal title={t('Run “{{label}}” again', { label })} onClose={onClose}>
      <label className={styles.describeLabel} htmlFor="rerun-input">
        {t('The input this block received')}
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
      <ModelField
        label={
          <span title={t("Runs this block on another model, with the same input and the same instructions — the only shape in which 'would the cheaper model do this just as well' has an answer. Only this block moves: the agents it delegates to keep theirs, and the flow on disk is not touched.")}>
            {t('Run it on another model (blank = its own) ⓘ')}
          </span>
        }
        value={model}
        onChange={setModel}
        allowNone
      />
      <label className={styles.checkRow}>
        <input
          type="checkbox"
          checked={downstream}
          disabled={busy}
          onChange={(e) => setDownstream(e.target.checked)}
        />
        <span>
          {t('Also the agents it delegates to')}
          <b className={styles.checkHint}>
            {' '}
            {t('— for continuing a failed run from here, rather than tuning this block alone.')}
          </b>
        </span>
      </label>
      <p className={styles.describeHint}>
        {t('Runs as a')} <b>{t('new execution')}</b>{' '}
        {downstream
          ? t('containing this block alone and what it delegates to.')
          : t('containing this block alone.')}{' '}
        {t('The flow’s trigger and its hand-offs stay behind: nothing downstream of the flow fires from a block you are still tuning.')}
        {edited && <b> {t('Your edited input is not saved to the flow.')}</b>}
      </p>
      {error && <p className={styles.describeError}>{error}</p>}
      <div className={styles.modalActions}>
        <button className={styles.ghost} onClick={onClose} disabled={busy}>
          {t('Cancel')}
        </button>
        <button
          className={styles.primary}
          onClick={() => void run()}
          disabled={busy || !input.trim()}
        >
          {busy ? <Spinner label={t('Starting')} /> : t('Run this block')}
        </button>
      </div>
    </Modal>
  )
}
