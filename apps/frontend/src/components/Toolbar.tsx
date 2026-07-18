import { api } from '../api/client.ts'
import type { RunSummary } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import styles from './toolbar.module.scss'

interface Props {
  onFlowsChanged: () => void
  onRunStarted: (r: RunSummary) => void
  onBackToFlows: () => void
  pushError: (m: string) => void
}

function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export function Toolbar({ onFlowsChanged, onRunStarted, onBackToFlows, pushError }: Props) {
  const name = useFlowStore((s) => s.name)
  const setName = useFlowStore((s) => s.setName)
  const mode = useFlowStore((s) => s.mode)
  const setMode = useFlowStore((s) => s.setMode)
  const newFlow = useFlowStore((s) => s.newFlow)
  const loadBackendFlow = useFlowStore((s) => s.loadBackendFlow)

  const save = async () => {
    try {
      const saved = await api.saveFlow(useFlowStore.getState().toBackendFlow())
      loadBackendFlow(saved)
      onFlowsChanged()
    } catch (e) {
      pushError(msg(e))
    }
  }

  const run = async () => {
    try {
      const r = await api.startRun(useFlowStore.getState().toBackendFlow())
      onRunStarted(r)
    } catch (e) {
      pushError(msg(e))
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

      <button className={styles.btn} onClick={newFlow}>
        New
      </button>
      <button className={styles.btn} onClick={save}>
        Save
      </button>
      <button className={`${styles.btn} ${styles.run}`} onClick={run}>
        ▶ Run
      </button>
    </header>
  )
}
