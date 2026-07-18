import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api/client.ts'
import type { BackendFlow, RunSummary } from './api/types.ts'
import { AppHeader, type View } from './components/AppHeader.tsx'
import { ErrorBoundary } from './components/ErrorBoundary.tsx'
import { FlowsPage } from './components/FlowsPage.tsx'
import { Inspector } from './components/Inspector.tsx'
import { Palette } from './components/Palette.tsx'
import { ResourcesPage } from './components/ResourcesPage.tsx'
import { RunsPanel } from './components/RunsPanel.tsx'
import { Toolbar } from './components/Toolbar.tsx'
import { FlowCanvas } from './flow/FlowCanvas.tsx'
import { useFlowStore } from './state/store.ts'
import styles from './App.module.scss'

function errMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export default function App() {
  const [view, setView] = useState<View>('flows')
  const [flows, setFlows] = useState<BackendFlow[]>([])
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [runsLoading, setRunsLoading] = useState(true)
  const [selectedRun, setSelectedRun] = useState<string | null>(
    () => localStorage.getItem('concentus.selectedRun'),
  )
  const [toast, setToast] = useState<string | null>(null)

  // Keep the selected run across a page refresh (its output/persisted state is reloaded from the API).
  useEffect(() => {
    if (selectedRun) localStorage.setItem('concentus.selectedRun', selectedRun)
    else localStorage.removeItem('concentus.selectedRun')
  }, [selectedRun])

  const refreshFlows = useCallback(() => {
    api.listFlows().then(setFlows).catch((e) => setToast(errMessage(e)))
  }, [])

  const refreshRuns = useCallback(() => {
    return api.listRuns().then(setRuns).catch((e) => setToast(errMessage(e)))
  }, [])

  // Poll the run list every 4s, but only while it's actually useful: skip
  // ticks while the tab is hidden, while the Studio view isn't active, or
  // while a previous poll is still in flight (never overlap requests).
  const pollingRef = useRef(false)
  useEffect(() => {
    refreshFlows()
    refreshRuns().finally(() => setRunsLoading(false))

    const tick = () => {
      if (document.visibilityState === 'hidden') return
      if (view !== 'studio') return
      if (pollingRef.current) return
      pollingRef.current = true
      refreshRuns().finally(() => {
        pollingRef.current = false
      })
    }

    const t = setInterval(tick, 4000)
    return () => clearInterval(t)
  }, [refreshFlows, refreshRuns, view])

  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), 5000)
    return () => clearTimeout(t)
  }, [toast])

  // Poll per-node execution state (Input/Output tabs, status dots, tokens) for the inspected run.
  const setActiveRun = useFlowStore((s) => s.setActiveRun)
  const setRunExec = useFlowStore((s) => s.setRunExec)
  useEffect(() => {
    setActiveRun(selectedRun)
    if (!selectedRun) {
      setRunExec(null)
      return
    }
    let alive = true
    const tick = () =>
      api
        .getRunNodes(selectedRun)
        .then((r) => alive && setRunExec(r))
        .catch((e) => alive && setToast(errMessage(e)))
    tick()
    const t = setInterval(tick, 1500)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [selectedRun, setActiveRun, setRunExec])

  const onRunStarted = (r: RunSummary) => {
    refreshRuns()
    setSelectedRun(r.id)
  }

  // ---- Flows dashboard actions ----
  const openFlow = async (id: string) => {
    try {
      useFlowStore.getState().loadBackendFlow(await api.getFlow(id))
      setView('studio')
    } catch (e) {
      setToast(errMessage(e))
    }
  }

  const runFlow = async (id: string) => {
    try {
      useFlowStore.getState().loadBackendFlow(await api.getFlow(id))
      setView('studio')
      onRunStarted(await api.runSavedFlow(id))
    } catch (e) {
      setToast(errMessage(e))
    }
  }

  const duplicateFlow = async (flow: BackendFlow) => {
    try {
      await api.saveFlow({ ...flow, id: undefined, name: `${flow.name} (copy)` })
      refreshFlows()
    } catch (e) {
      setToast(errMessage(e))
    }
  }

  const deleteFlow = async (id: string) => {
    const flow = flows.find((f) => f.id === id)
    if (!confirm(`Delete "${flow?.name ?? 'this flow'}"? This cannot be undone.`)) return
    try {
      await api.deleteFlow(id)
      refreshFlows()
    } catch (e) {
      setToast(errMessage(e))
    }
  }

  const newFlow = () => {
    useFlowStore.getState().newFlow()
    setView('studio')
  }

  /** Persists a flow edited from the dashboard (favourite, tags, pause, webhook, import). */
  const saveFlowFromDashboard = async (flow: BackendFlow) => {
    const saved = await api.saveFlow(flow)
    // Keep the canvas in sync if this is the flow currently open in Studio.
    if (saved.id && useFlowStore.getState().flowId === saved.id) {
      useFlowStore.getState().loadBackendFlow(saved)
    }
    refreshFlows()
  }

  /**
   * Opens an execution: puts the exact flow that ran onto the canvas first, so every block lines
   * up with the run's recorded input/output and status. Without this the inspector would be
   * looking at whatever flow happened to be open (or none), and show nothing.
   */
  const openRun = async (runId: string) => {
    const current = useFlowStore.getState().flowId
    const run = runs.find((r) => r.id === runId)
    if (!run?.flowId || run.flowId !== current) {
      try {
        useFlowStore.getState().loadBackendFlow(await api.getRunFlow(runId))
      } catch {
        // No stored snapshot (older run) — fall back to the saved flow if there is one.
        if (run?.flowId) {
          try {
            useFlowStore.getState().loadBackendFlow(await api.getFlow(run.flowId))
          } catch (e) {
            setToast(errMessage(e))
          }
        }
      }
    }
    setSelectedRun(runId)
    setView('studio')
  }

  const retryRun = async (runId: string) => {
    try {
      onRunStarted(await api.retryRun(runId))
      setView('studio')
    } catch (e) {
      setToast(errMessage(e))
    }
  }

  return (
    <div className={styles.app}>
      <AppHeader view={view} onView={setView} />

      <ErrorBoundary>
        {view === 'flows' ? (
          <FlowsPage
            flows={flows}
            runs={runs}
            onOpen={openFlow}
            onRun={runFlow}
            onDuplicate={duplicateFlow}
            onDelete={deleteFlow}
            onNew={newFlow}
            onOpenRun={(id) => void openRun(id)}
            onSaveFlow={saveFlowFromDashboard}
            onRetryRun={retryRun}
            pushError={setToast}
          />
        ) : view === 'studio' ? (
          <>
            <Toolbar
              onFlowsChanged={refreshFlows}
              onRunStarted={onRunStarted}
              onBackToFlows={() => setView('flows')}
              pushError={setToast}
            />
            <div className={styles.main}>
              <Palette />
              <div className={styles.canvas}>
                <FlowCanvas />
              </div>
              <Inspector />
            </div>
            <RunsPanel
              runs={runs}
              loading={runsLoading}
              selected={selectedRun}
              onSelect={(id) => void openRun(id)}
            />
          </>
        ) : (
          <ResourcesPage />
        )}
      </ErrorBoundary>

      {toast && (
        <div className={styles.toast} role="alert">
          <span className={styles.toastMessage}>{toast}</span>
          <button
            type="button"
            className={styles.toastDismiss}
            onClick={() => setToast(null)}
            aria-label="Dismiss notification"
          >
            ×
          </button>
        </div>
      )}
    </div>
  )
}
