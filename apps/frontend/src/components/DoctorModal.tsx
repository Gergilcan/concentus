import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { DoctorFinding } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Modal } from './Modal.tsx'
import { Spinner } from './Spinner.tsx'
import styles from './flows.module.scss'

/**
 * The pre-run check: everything that would fail at run time, said before the run.
 *
 * Each of these checks already existed, scattered across the code that hits them — a missing
 * credential surfaced as a 401 four minutes into a run, an un-installed plugin as a CLI error
 * nobody reads. This asks them all at once and phrases each answer as something to DO.
 *
 * It never blocks anything. Some findings are honest guesses (a server that may need no auth at
 * all), and a check that refused to let you try would be worse than the failure it predicted.
 */
export function DoctorModal({ flowId, flowName, onClose }: {
  flowId: string
  flowName: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [findings, setFindings] = useState<DoctorFinding[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    void api
      .runFlowDoctor(flowId)
      .then((f) => alive && setFindings(f))
      .catch((e) => {
        if (!alive) return
        // The check failing is not a finding about the flow, and must not be dressed as one.
        setError(errMessage(e))
        setFindings([])
      })
    return () => {
      alive = false
    }
  }, [flowId])

  const errors = findings?.filter((f) => f.level === 'error') ?? []
  const warnings = findings?.filter((f) => f.level === 'warn') ?? []

  return (
    <Modal title={t('Check — {{name}}', { name: flowName })} onClose={onClose}>
      {findings === null ? (
        <div className={styles.sideEmpty}>
          <Spinner label={t('Checking this flow')} />
        </div>
      ) : error ? (
        <p className={styles.describeError}>{t('The check could not run: {{error}}', { error })}</p>
      ) : findings.length === 0 ? (
        <p className={styles.doctorClear}>
          {t('✓ Nothing to fix. Credentials resolve, the graph compiles, and this machine can run it.')}
        </p>
      ) : (
        <>
          <p className={styles.describeHint}>
            {errors.length > 0
              ? errors.length === 1
                ? t('{{n}} thing would fail', { n: errors.length })
                : t('{{n}} things would fail', { n: errors.length })
              : t('Nothing would fail')}
            {warnings.length > 0 && t(', {{n}} worth a look', { n: warnings.length })}
            {t('. Running is still allowed — this informs, it does not block.')}
          </p>
          <ul className={styles.doctorList} aria-label={t('Findings')}>
            {[...errors, ...warnings].map((f, i) => (
              <li key={i} className={styles.doctorRow}>
                <span className={f.level === 'error' ? styles.doctorError : styles.doctorWarn}>
                  {f.level === 'error' ? '✖' : '▲'} {f.area}
                </span>
                <span className={styles.doctorMessage}>{f.message}</span>
                <span className={styles.doctorFix}>{f.fix}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </Modal>
  )
}
