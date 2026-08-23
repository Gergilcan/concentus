import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { BackendFlow } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Modal } from './Modal.tsx'
import { Spinner } from './Spinner.tsx'
import styles from './flows.module.scss'

/**
 * "Describe what you want automated" — one sentence in, a first draft of a flow out.
 *
 * The canvas is easy once you know what a coordinator, a sub-agent and an input node are for, and
 * impossible to start from when you do not. This is the way in for that person: the answer opens
 * in Studio as an ordinary, editable flow.
 *
 * <p>Nothing is saved. The generated flow has no id and reaches the canvas as a draft — pressing
 * Save is still the user's decision, and closing Studio without it leaves no trace. Said out loud
 * in the dialog, because "it made me a flow" and "it made me a flow AND kept it" are very
 * different promises.
 */
export function DescribeFlowModal({
  onClose,
  onGenerated,
  pushError,
}: {
  onClose: () => void
  /** Receives the unsaved draft; the caller puts it on the canvas. */
  onGenerated: (flow: BackendFlow) => void
  pushError: (m: string) => void
}) {
  const { t } = useTranslation()
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const generate = async () => {
    if (!description.trim()) return
    setBusy(true)
    setError(null)
    try {
      const flow = await api.generateFlow(description.trim())
      onGenerated(flow)
      onClose()
    } catch (e) {
      // Shown in the dialog rather than as a toast behind it: the description is still on screen
      // and the next thing to do is edit it and try again.
      setError(errMessage(e))
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={t('Describe a flow')} onClose={onClose}>
      <label className={styles.describeLabel} htmlFor="describe-flow">
        {t('Describe what you want automated')}
      </label>
      <textarea
        id="describe-flow"
        className={styles.describeInput}
        rows={5}
        autoFocus
        disabled={busy}
        placeholder={t('Every morning, read the overnight Sentry errors and post a short summary to my Slack channel — and ask me before opening any issue.')}
        value={description}
        onChange={(e) => setDescription(e.target.value)}
      />
      <p className={styles.describeHint}>
        {t('A first draft, written by an agent from your description, opened in Studio for you to edit.')}
        <b> {t('Nothing is saved')}</b> {t('until you press Save.')}
      </p>
      {error && <p className={styles.describeError}>{error}</p>}
      <div className={styles.modalActions}>
        <button className={styles.ghost} onClick={onClose} disabled={busy}>
          {t('Cancel')}
        </button>
        <button className={styles.primary} onClick={() => void generate()} disabled={busy || !description.trim()}>
          {busy ? <Spinner label={t('Designing your flow')} /> : t('Generate')}
        </button>
      </div>
    </Modal>
  )
}
