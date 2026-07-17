import { useCallback, useEffect, useState } from 'react'
import { api } from './api/client.ts'
import type { BackendFlow, RunSummary } from './api/types.ts'
import { AppHeader, type View } from './components/AppHeader.tsx'
import { Inspector } from './components/Inspector.tsx'
import { Palette } from './components/Palette.tsx'
import { ResourcesPage } from './components/ResourcesPage.tsx'
import { RunsPanel } from './components/RunsPanel.tsx'
import { Toolbar } from './components/Toolbar.tsx'
import { FlowCanvas } from './flow/FlowCanvas.tsx'
import styles from './App.module.scss'

export default function App() {
  const [view, setView] = useState<View>('studio')
  const [flows, setFlows] = useState<BackendFlow[]>([])
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [selectedRun, setSelectedRun] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)

  const refreshFlows = useCallback(() => {
    api.listFlows().then(setFlows).catch((e) => setToast(String(e)))
  }, [])

  const refreshRuns = useCallback(() => {
    api.listRuns().then(setRuns).catch(() => {})
  }, [])

  useEffect(() => {
    refreshFlows()
    refreshRuns()
    const t = setInterval(refreshRuns, 4000)
    return () => clearInterval(t)
  }, [refreshFlows, refreshRuns])

  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), 5000)
    return () => clearTimeout(t)
  }, [toast])

  const onRunStarted = (r: RunSummary) => {
    refreshRuns()
    setSelectedRun(r.id)
  }

  return (
    <div className={styles.app}>
      <AppHeader view={view} onView={setView} />

      {view === 'studio' ? (
        <>
          <Toolbar
            flows={flows}
            onFlowsChanged={refreshFlows}
            onRunStarted={onRunStarted}
            pushError={setToast}
          />
          <div className={styles.main}>
            <Palette />
            <div className={styles.canvas}>
              <FlowCanvas />
            </div>
            <Inspector />
          </div>
          <RunsPanel runs={runs} selected={selectedRun} onSelect={setSelectedRun} />
        </>
      ) : (
        <ResourcesPage />
      )}

      {toast && (
        <div className={styles.toast} onClick={() => setToast(null)}>
          {toast}
        </div>
      )}
    </div>
  )
}
