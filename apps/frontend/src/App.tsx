import { useEffect, useState } from 'react'
import { AppHeader, type View } from './components/AppHeader.tsx'
import { ErrorBoundary } from './components/ErrorBoundary.tsx'
import { FlowsPage } from './components/FlowsPage.tsx'
import { Inspector } from './components/Inspector.tsx'
import { Palette } from './components/Palette.tsx'
import { ResourcesPage } from './components/ResourcesPage.tsx'
import { RunsPanel } from './components/RunsPanel.tsx'
import { SignIn } from './components/SignIn.tsx'
import { Toolbar } from './components/Toolbar.tsx'
import { FlowCanvas } from './flow/FlowCanvas.tsx'
import { useFlowStore } from './state/store.ts'
import { useFlowActions } from './state/useFlowActions.ts'
import { useFlowsAndRuns } from './state/useFlowsAndRuns.ts'
import { useSelectedRun } from './state/useSelectedRun.ts'
import { useSession } from './state/useSession.ts'
import { TOAST_DURATION_MS } from './constants.ts'
import styles from './App.module.scss'

/**
 * The session gate.
 *
 * The workspace is a separate component so that none of its data hooks exist until the session is
 * known. Rendering it first and hiding it afterwards would fire every flow, run and resource
 * request before sign-in — a burst of 401s, and a window in which cached data from a previous
 * session could still be on screen.
 */
export default function App() {
  const { session, loading, onSignedIn, signOut } = useSession()

  if (loading) return null
  if (session?.authEnabled && !session.signedIn) {
    return <SignIn onSignedIn={onSignedIn} storeUnavailable={!session.storeAvailable} />
  }
  return <Workspace signedInAs={session?.email ?? null} onSignOut={signOut} />
}

interface WorkspaceProps {
  signedInAs: string | null
  onSignOut: () => void
}

function Workspace({ signedInAs, onSignOut }: WorkspaceProps) {
  const [view, setView] = useState<View>('flows')
  // The executions panel sits under the flow being edited, so it shows that flow's runs only.
  const openFlowId = useFlowStore((s) => s.flowId)
  const [toast, setToast] = useState<string | null>(null)

  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), TOAST_DURATION_MS)
    return () => clearTimeout(t)
  }, [toast])

  const { flows, runs, runsLoading, refreshFlows, refreshRuns } = useFlowsAndRuns(view, setToast)
  const [selectedRun, setSelectedRun] = useSelectedRun(setToast, runs)
  const {
    onRunStarted,
    openFlow,
    runFlow,
    duplicateFlow,
    deleteFlow,
    newFlow,
    saveFlowFromDashboard,
    openRun,
    retryRun,
  } = useFlowActions({ flows, runs, refreshFlows, refreshRuns, setView, setSelectedRun, pushError: setToast })

  return (
    <div className={styles.app}>
      <AppHeader view={view} onView={setView} signedInAs={signedInAs} onSignOut={onSignOut} />

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
              flowId={openFlowId}
            />
          </>
        ) : (
          <ResourcesPage pushError={setToast} />
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
