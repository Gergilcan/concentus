import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import type { RunSummary } from '../api/types.ts'
import { deniedReason, usePermissions } from '../state/permissions.tsx'
import { useFlowStore } from '../state/store.ts'
import { cx } from '../utils/cx.ts'
import { DoctorModal } from './DoctorModal.tsx'
import { FlowEvaluationPanel } from './FlowEvaluationPanel.tsx'
import { FlowVersions } from './FlowVersions.tsx'
import { Modal } from './Modal.tsx'
import styles from './toolbar.module.scss'

interface Props {
  onFlowsChanged: () => void
  onRunStarted: (r: RunSummary) => void
  onBackToFlows: () => void
  /** Opens an existing execution in the console below — what a failed evaluation case links to. */
  onOpenRun?: (runId: string) => void
  pushError: (m: string) => void
}

export function Toolbar({ onFlowsChanged, onRunStarted, onBackToFlows, onOpenRun, pushError }: Props) {
  const { t } = useTranslation()
  const name = useFlowStore((s) => s.name)
  const permissions = usePermissions()
  const setName = useFlowStore((s) => s.setName)
  const mode = useFlowStore((s) => s.mode)
  const setMode = useFlowStore((s) => s.setMode)
  const newFlow = useFlowStore((s) => s.newFlow)
  const loadBackendFlow = useFlowStore((s) => s.loadBackendFlow)
  const flowId = useFlowStore((s) => s.flowId)
  const [checking, setChecking] = useState(false)
  const [versions, setVersions] = useState(false)
  const [evaluations, setEvaluations] = useState(false)
  const undo = useFlowStore((s) => s.undo)
  const redo = useFlowStore((s) => s.redo)
  const canUndo = useFlowStore((s) => s.past.length > 0)
  const canRedo = useFlowStore((s) => s.future.length > 0)
  const autoLayout = useFlowStore((s) => s.autoLayout)
  const canTidy = useFlowStore((s) => s.nodes.length > 1)

  const save = async () => {
    try {
      const saved = await api.saveFlow(useFlowStore.getState().toBackendFlow())
      loadBackendFlow(saved)
      onFlowsChanged()
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const run = async () => {
    try {
      const r = await api.startRun(useFlowStore.getState().toBackendFlow())
      onRunStarted(r)
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  return (
    <header className={styles.toolbar}>
      <button className={styles.back} onClick={onBackToFlows} title={t('Back to all flows')}>
        ← {t('Flows')}
      </button>

      <input
        className={styles.name}
        value={name}
        onChange={(e) => setName(e.target.value)}
        aria-label={t('Flow name')}
      />

      <select
        className={styles.select}
        value={mode}
        onChange={(e) => setMode(e.target.value as 'managed' | 'local')}
        title={t('managed = multi-agent execution')}
      >
        <option value="managed">{t('managed')}</option>
        <option value="local">{t('local')}</option>
      </select>

      <div className={styles.spacer} />

      {/* Undo/redo live on the drawing, not the flow record: they exist because Delete used to be
          irreversible short of reloading without saving. Ctrl+Z / Ctrl+Y work too. */}
      <button
        className={styles.btn}
        onClick={undo}
        disabled={!canUndo || !permissions.canEdit}
        title={t('Undo the last canvas change (Ctrl+Z)')}
        aria-label={t('Undo')}
      >
        ↶
      </button>
      <button
        className={styles.btn}
        onClick={redo}
        disabled={!canRedo || !permissions.canEdit}
        title={t('Redo what was just undone (Ctrl+Y)')}
        aria-label={t('Redo')}
      >
        ↷
      </button>
      <button
        className={styles.btn}
        onClick={autoLayout}
        disabled={!canTidy || !permissions.canEdit}
        title={t('Tidy up: lay the chain out left to right, capabilities under their agents. One Ctrl+Z away from undone.')}
      >
        ⌗ {t('Tidy')}
      </button>

      {/* Checks the SAVED flow, so an unsaved canvas has nothing to check yet — the button says
          that rather than disappearing, which would read as a missing feature. */}
      <button
        className={styles.btn}
        onClick={() => flowId && setChecking(true)}
        disabled={!flowId}
        title={
          flowId
            ? t('Check this flow before running it: missing credentials, servers without auth, un-installed plugins, an invalid schedule, an exhausted budget. Informs — never blocks a run.')
            : t('Save the flow first — the check runs against the saved version.')
        }
      >
        ⚕ {t('Check')}
      </button>
      {/* Version history lived in the inspector panel, next to the canvas it changes, until the
          panel went. It belongs here rather than only behind the dashboard's History modal for
          the same reason it did then: previewing and restoring are editing actions, and the
          person doing them is looking at the flow. Disabled until there is a saved flow to have
          a history — the same rule as Check, and it says so rather than disappearing. */}
      <button
        className={styles.btn}
        onClick={() => flowId && setVersions(true)}
        disabled={!flowId}
        title={flowId ? t('Earlier versions of this flow — preview one, or roll back to it.')
                      : t('Save the flow first — a version history starts at the first save.')}
      >
        {t('Versions')}
      </button>
      {/* Next to Versions because it is the other half of the same question: Versions says what
          changed, an evaluation says whether it got better. Needs a saved flow for the same
          reason — the cases belong to a flow id, and the score is stamped with a version. */}
      <button
        className={styles.btn}
        onClick={() => flowId && setEvaluations(true)}
        disabled={!flowId}
        title={
          flowId
            ? t('Cases with known answers, run against this flow and scored per version — so an edit can be measured rather than felt.')
            : t('Save the flow first — evaluation cases belong to a saved flow.')
        }
      >
        {t('Evaluations')}
      </button>
      <button className={styles.btn} onClick={newFlow} disabled={!permissions.canEdit}>
        {t('New')}
      </button>
      <button
        className={styles.btn}
        onClick={save}
        disabled={!permissions.canEdit}
        title={permissions.canEdit ? undefined : deniedReason(permissions, 'edit')}
      >
        {t('Save')}
      </button>
      <button
        className={cx(styles.btn, styles.run)}
        onClick={run}
        disabled={!permissions.canRun}
        title={permissions.canRun ? undefined : deniedReason(permissions, 'run')}
      >
        ▶ {t('Run')}
      </button>

      {checking && flowId && (
        <DoctorModal flowId={flowId} flowName={name} onClose={() => setChecking(false)} />
      )}

      {/* Deliberately no onRestored: the dialog STAYS OPEN after a restore. The dashboard's
          History closes because you are on a list and about to go somewhere; here you are already
          looking at the canvas, which has just changed behind this — and what the list then shows
          is the point of the feature. Restoring appends a revision rather than overwriting one,
          and watching "current" move to a NEW row is how that stops being a claim in a tooltip. */}
      {versions && flowId && (
        <Modal title={t('Versions — {{name}}', { name })} onClose={() => setVersions(false)} wide>
          <FlowVersions flowId={flowId} pushError={pushError} />
        </Modal>
      )}
      {evaluations && flowId && (
        <Modal title={t('Evaluations — {{name}}', { name })} onClose={() => setEvaluations(false)} wide>
          <FlowEvaluationPanel
            flowId={flowId}
            pushError={pushError}
            onOpenRun={
              onOpenRun &&
              ((id) => {
                // Opening a run means reading its console, which sits under the canvas this
                // dialog covers — so the dialog gets out of the way.
                setEvaluations(false)
                onOpenRun(id)
              })
            }
          />
        </Modal>
      )}
    </header>
  )
}
