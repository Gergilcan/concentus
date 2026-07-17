import { api } from '../api/client.ts'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import { useFlowStore } from '../state/store.ts'
import styles from './toolbar.module.scss'

interface Props {
  flows: BackendFlow[]
  onFlowsChanged: () => void
  onRunStarted: (r: RunSummary) => void
  pushError: (m: string) => void
}

function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export function Toolbar({ flows, onFlowsChanged, onRunStarted, pushError }: Props) {
  const name = useFlowStore((s) => s.name)
  const setName = useFlowStore((s) => s.setName)
  const mode = useFlowStore((s) => s.mode)
  const setMode = useFlowStore((s) => s.setMode)
  const flowId = useFlowStore((s) => s.flowId)
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

  const openFlow = async (id: string) => {
    if (!id) {
      newFlow()
      return
    }
    try {
      loadBackendFlow(await api.getFlow(id))
    } catch (e) {
      pushError(msg(e))
    }
  }

  return (
    <header className={styles.toolbar}>
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

      <select className={styles.select} value={flowId ?? ''} onChange={(e) => openFlow(e.target.value)}>
        <option value="">— open flow —</option>
        {flows.map((f) => (
          <option key={f.id} value={f.id}>
            {f.name}
          </option>
        ))}
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
