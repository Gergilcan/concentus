import { useEffect, useState } from 'react'
import { AppHeader, type View } from './components/AppHeader.tsx'
import { ErrorBoundary } from './components/ErrorBoundary.tsx'
import { FlowsPage } from './components/FlowsPage.tsx'
import { Inspector } from './components/Inspector.tsx'
import { Palette } from './components/Palette.tsx'
import { ResourcesPage } from './components/ResourcesPage.tsx'
import { UsagePage } from './components/UsagePage.tsx'
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

/**
 * Whether a Studio panel is open, remembered across sessions.
 *
 * localStorage rather than the flow: which panels someone keeps open is a fact about their
 * screen and habits, not about any flow — a laptop wants the palette folded, a big monitor
 * doesn't, and neither preference belongs in a shared flow definition.
 */
function usePanelOpen(key: string): [boolean, () => void] {
  const [open, setOpen] = useState(() => localStorage.getItem(key) !== 'closed')
  const toggle = () =>
    setOpen((o) => {
      localStorage.setItem(key, o ? 'closed' : 'open')
      return !o
    })
  return [open, toggle]
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
  // Every Studio panel folds away: on a laptop the canvas is the work and the chrome is the tax.
  const [paletteOpen, togglePalette] = usePanelOpen('studio.palette')
  const [inspectorOpen, toggleInspector] = usePanelOpen('studio.inspector')
  const [runsOpen, toggleRuns] = usePanelOpen('studio.runs')

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
        ) : view === 'usage' ? (
          <UsagePage />
        ) : view === 'studio' ? (
          <>
            <Toolbar
              onFlowsChanged={refreshFlows}
              onRunStarted={onRunStarted}
              onBackToFlows={() => setView('flows')}
              pushError={setToast}
            />
            <div
              className={styles.main}
              style={{
                gridTemplateColumns: `${paletteOpen ? '230px' : '26px'} 1fr ${inspectorOpen ? '300px' : '26px'}`,
              }}
            >
              {paletteOpen ? (
                <div className={styles.sideWrap}>
                  <Palette />
                  <button
                    className={`${styles.collapseSide} ${styles.collapseAtRight}`}
                    onClick={togglePalette}
                    title="Hide the node palette"
                    aria-label="Hide the node palette"
                  >
                    ◂
                  </button>
                </div>
              ) : (
                <button
                  className={styles.rail}
                  onClick={togglePalette}
                  title="Show the node palette"
                  aria-label="Show the node palette"
                >
                  ▸<span className={styles.railLabel}>Add node</span>
                </button>
              )}
              <div className={styles.canvas}>
                <FlowCanvas />
              </div>
              {inspectorOpen ? (
                <div className={styles.sideWrap}>
                  <Inspector />
                  <button
                    className={`${styles.collapseSide} ${styles.collapseAtLeft}`}
                    onClick={toggleInspector}
                    title="Hide the node properties"
                    aria-label="Hide the node properties"
                  >
                    ▸
                  </button>
                </div>
              ) : (
                <button
                  className={styles.rail}
                  onClick={toggleInspector}
                  title="Show the node properties"
                  aria-label="Show the node properties"
                >
                  ◂<span className={styles.railLabel}>Properties</span>
                </button>
              )}
            </div>
            {runsOpen ? (
              <div className={styles.bottomWrap}>
                <RunsPanel
                  runs={runs}
                  loading={runsLoading}
                  selected={selectedRun}
                  onSelect={(id) => void openRun(id)}
                  flowId={openFlowId}
                />
                <button
                  className={styles.collapseBottom}
                  onClick={toggleRuns}
                  title="Hide the executions panel"
                  aria-label="Hide the executions panel"
                >
                  ▾
                </button>
              </div>
            ) : (
              <button
                className={styles.bottomRail}
                onClick={toggleRuns}
                title="Show the executions panel"
                aria-label="Show the executions panel"
              >
                ▴ Executions
              </button>
            )}
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
