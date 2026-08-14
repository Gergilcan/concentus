import { useState } from 'react'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import type { RunSummary } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import { cx } from '../utils/cx.ts'
import { DoctorModal } from './DoctorModal.tsx'
import styles from './toolbar.module.scss'

interface Props {
  onFlowsChanged: () => void
  onRunStarted: (r: RunSummary) => void
  onBackToFlows: () => void
  pushError: (m: string) => void
}

export function Toolbar({ onFlowsChanged, onRunStarted, onBackToFlows, pushError }: Props) {
  const name = useFlowStore((s) => s.name)
  const setName = useFlowStore((s) => s.setName)
  const mode = useFlowStore((s) => s.mode)
  const setMode = useFlowStore((s) => s.setMode)
  const newFlow = useFlowStore((s) => s.newFlow)
  const loadBackendFlow = useFlowStore((s) => s.loadBackendFlow)
  const flowId = useFlowStore((s) => s.flowId)
  const [checking, setChecking] = useState(false)

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
      <button className={styles.back} onClick={onBackToFlows} title="Back to all flows">
        ← Flows
      </button>

      <input
        className={styles.name}
        value={name}
        onChange={(e) => setName(e.target.value)}
        aria-label="Flow name"
      />

      <select
        className={styles.select}
        value={mode}
        onChange={(e) => setMode(e.target.value as 'managed' | 'local')}
        title="managed = multi-agent execution"
      >
        <option value="managed">managed</option>
        <option value="local">local</option>
      </select>

      <div className={styles.spacer} />

      {/* Checks the SAVED flow, so an unsaved canvas has nothing to check yet — the button says
          that rather than disappearing, which would read as a missing feature. */}
      <button
        className={styles.btn}
        onClick={() => flowId && setChecking(true)}
        disabled={!flowId}
        title={
          flowId
            ? 'Check this flow before running it: missing credentials, servers without auth, un-installed plugins, an invalid schedule, an exhausted budget. Informs — never blocks a run.'
            : 'Save the flow first — the check runs against the saved version.'
        }
      >
        ⚕ Check
      </button>
      <button className={styles.btn} onClick={newFlow}>
        New
      </button>
      <button className={styles.btn} onClick={save}>
        Save
      </button>
      <button className={cx(styles.btn, styles.run)} onClick={run}>
        ▶ Run
      </button>

      {checking && flowId && (
        <DoctorModal flowId={flowId} flowName={name} onClose={() => setChecking(false)} />
      )}
    </header>
  )
}
