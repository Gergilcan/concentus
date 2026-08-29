import {
  useEffect,
  useMemo,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react'
import { useTranslation } from 'react-i18next'
import { AppHeader, NAV, type View } from './components/AppHeader.tsx'
import { CommandPalette } from './components/CommandPalette.tsx'
import { blockCommands, type Command } from './components/commandPalette.ts'
import { ErrorBoundary } from './components/ErrorBoundary.tsx'
import { timeAgo } from './components/flowFormat.ts'
import { FlowsPage } from './components/FlowsPage.tsx'
import { NodeDetailsDialog } from './components/NodeDetailsDialog.tsx'
import { Palette } from './components/Palette.tsx'
import { ResourcesPage } from './components/ResourcesPage.tsx'
import { UsagePage } from './components/UsagePage.tsx'
import { RunsPanel } from './components/RunsPanel.tsx'
import { SetupWizard } from './components/SetupWizard.tsx'
import { SignIn } from './components/SignIn.tsx'
import { Toolbar } from './components/Toolbar.tsx'
import { FlowCanvas } from './flow/FlowCanvas.tsx'
import { useFlowStore } from './state/store.ts'
import { useFlowActions } from './state/useFlowActions.ts'
import { useFlowsAndRuns } from './state/useFlowsAndRuns.ts'
import { useSelectedRun } from './state/useSelectedRun.ts'
import { PermissionsProvider } from './state/permissions.tsx'
import { useSession } from './state/useSession.ts'
import { TOAST_DURATION_MS } from './constants.ts'
import { cx } from './utils/cx.ts'
import { setTheme, THEMES } from './utils/theme.ts'
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
  // An installation with no accounts cannot ask anybody to sign in, because there is nobody to be.
  if (session?.setupRequired) {
    return (
      <SetupWizard
        onSignedIn={onSignedIn}
        storeUnavailable={!session.storeAvailable}
        providers={session.providers ?? []}
      />
    )
  }
  if (!session?.signedIn) {
    return (
      <SignIn
        onSignedIn={onSignedIn}
        storeUnavailable={!session?.storeAvailable}
        providers={session?.providers ?? []}
      />
    )
  }
  // The role wraps the whole workspace rather than being threaded through it: what an account may
  // do is asked in a dozen unrelated places — a Save button, a Run button, a delete — and passing
  // it down by hand would mean the one component that forgot is the one that offers a 403.
  return (
    <PermissionsProvider role={session?.role}>
      <Workspace
        signedInAs={session?.email ?? null}
        // Named only when there is a choice: one organization's name in the header is a label for
        // nothing, and the moment there are two it is the answer to "which one am I looking at".
        organizationName={(session?.organizationCount ?? 0) > 1 ? (session?.organizationName ?? null) : null}
        onSignOut={signOut}
      />
    </PermissionsProvider>
  )
}

/**
 * Whether a Studio panel is open, remembered across sessions.
 *
 * localStorage rather than the flow: which panels someone keeps open is a fact about their
 * screen and habits, not about any flow — a laptop wants the palette folded, a big monitor
 * doesn't, and neither preference belongs in a shared flow definition.
 */
function usePanelOpen(key: string, defaultOpen = true): [boolean, () => void] {
  const [open, setOpen] = useState(() => {
    const remembered = localStorage.getItem(key)
    return remembered === null ? defaultOpen : remembered !== 'closed'
  })
  const toggle = () =>
    setOpen((o) => {
      localStorage.setItem(key, o ? 'closed' : 'open')
      return !o
    })
  return [open, toggle]
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

/**
 * A panel dimension the user can drag, remembered across sessions — same home as the panels'
 * open state, for the same reason: how much room the palette or the executions deserve is a fact
 * about this screen, not about any flow.
 *
 * `startDrag(measure)` is a pointerdown handler; `measure` turns the pointer position into the
 * new size (distance from the relevant window edge). Written once, on release — resizing is
 * dozens of moves per second and none of the intermediate values is worth persisting.
 */
function usePanelSize(
  key: string,
  fallback: number,
  min: number,
  max: number,
): [number, (measure: (e: PointerEvent) => number) => (down: ReactPointerEvent) => void] {
  const [size, setSize] = useState(() => {
    const stored = Number(localStorage.getItem(key) ?? NaN)
    return Number.isFinite(stored) ? clamp(stored, min, max) : fallback
  })
  const startDrag = (measure: (e: PointerEvent) => number) => (down: ReactPointerEvent) => {
    down.preventDefault()
    let last: number | null = null
    const move = (e: PointerEvent) => {
      last = clamp(measure(e), min, max)
      setSize(last)
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      if (last !== null) localStorage.setItem(key, String(last))
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }
  return [size, startDrag]
}

interface SidePanelProps {
  /** Which edge of the canvas the panel is docked to; every direction below follows from it. */
  side: 'left' | 'right'
  /** Named as the tooltips read it: "Show/Hide <label>". */
  label: string
  /** What the folded rail promises to bring back — shorter than the label, and not a sentence. */
  railLabel: string
  open: boolean
  onToggle: () => void
  startDrag: (measure: (e: PointerEvent) => number) => (down: ReactPointerEvent) => void
  children: ReactNode
}

/**
 * One of Studio's two side panels: open, the panel itself with a collapse chevron and a drag
 * handle on the edge facing the canvas; folded, a slim rail naming what it will bring back.
 *
 * The palette is the only one of these left — the node properties moved into a dialog opened by
 * double-clicking a block, because a 300px column is the wrong shape for writing a prompt in.
 * `side` is kept rather than hardcoded: it decides which way the chevrons point, which edge the
 * handle sits on, and which direction a drag grows the panel. The mirroring is what the
 * component IS, and a second panel on the right is a layout decision, not a rewrite.
 */
function SidePanel({ side, label, railLabel, open, onToggle, startDrag, children }: SidePanelProps) {
  const { t } = useTranslation()
  const onLeft = side === 'left'
  if (!open) {
    return (
      <button
        className={styles.rail}
        onClick={onToggle}
        title={t('Show {{label}}', { label })}
        aria-label={t('Show {{label}}', { label })}
      >
        {onLeft ? '▸' : '◂'}
        <span className={styles.railLabel}>{railLabel}</span>
      </button>
    )
  }
  return (
    <div className={cx(styles.sideWrap, onLeft ? styles.sideWrapLeft : styles.sideWrapRight)}>
      {children}
      <button
        className={cx(styles.collapseSide, onLeft ? styles.collapseAtRight : styles.collapseAtLeft)}
        onClick={onToggle}
        title={t('Hide {{label}}', { label })}
        aria-label={t('Hide {{label}}', { label })}
      >
        {onLeft ? '◂' : '▸'}
      </button>
      <div
        className={styles.resizeX}
        style={onLeft ? { right: -4 } : { left: -4 }}
        onPointerDown={startDrag(
          onLeft ? (e) => e.clientX : (e) => window.innerWidth - e.clientX,
        )}
        title={t('Drag to resize')}
      />
    </div>
  )
}

interface WorkspaceProps {
  signedInAs: string | null
  /** The current organization's name, when the account is in more than one. */
  organizationName: string | null
  onSignOut: () => void
}

function Workspace({ signedInAs, organizationName, onSignOut }: WorkspaceProps) {
  const { t } = useTranslation()
  const [view, setView] = useState<View>('flows')
  const [commandsOpen, setCommandsOpen] = useState(false)
  // The executions panel sits under the flow being edited, so it shows that flow's runs only.
  const openFlowId = useFlowStore((s) => s.flowId)
  const requestFocus = useFlowStore((s) => s.requestFocus)
  const [toast, setToast] = useState<string | null>(null)
  // Every Studio panel folds away: on a laptop the canvas is the work and the chrome is the tax.
  const [paletteOpen, togglePalette] = usePanelOpen('studio.palette')
  // Folded until this installation has run something. Open, its whole content on a flow that
  // has never run is "No executions yet" and "Select a run" — a quarter of the height spent
  // saying nothing, taken from the canvas, which is the thing that is short of room. It is a
  // default, not a rule: the first toggle is remembered and this never overrides it again.
  const [runsOpen, toggleRuns] = usePanelOpen('studio.runs', false)
  // Every Studio panel resizes by dragging its inner edge, and keeps its size across sessions.
  const [paletteW, dragPalette] = usePanelSize('studio.palette.width', 260, 180, 480)
  const [runsH, dragRuns] = usePanelSize('studio.runs.height', 260, 140, 600)

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
    sandboxFlowCopy,
    deleteFlow,
    newFlow,
    openGeneratedFlow,
    saveFlowFromDashboard,
    openRun,
    retryRun,
  } = useFlowActions({ flows, runs, refreshFlows, refreshRuns, setView, setSelectedRun, pushError: setToast })

  // Ctrl+K / Cmd+K, from anywhere. Deliberately NOT excluded inside text fields: Ctrl+K is not a
  // typing key, and a palette that refuses to open while the cursor happens to be in a search box
  // is a palette you cannot rely on.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setCommandsOpen((open) => !open)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  /**
   * What the palette can reach. Ordered by how often it is what someone wants: where to go, then
   * the blocks of the flow they have open, then their flows, then the runs they might be looking
   * for, then the theme.
   *
   * Runs are capped: the palette is for reaching things by name, and a thousand rows of
   * near-identical run ids is a list nobody scrolls.
   *
   * The blocks are read from the store as the palette OPENS rather than subscribed to: every
   * drag on the canvas rewrites the nodes array, and a subscription here would re-render the
   * whole workspace on each pixel of it for a list nobody can see until they press Ctrl+K.
   */
  const commands = useMemo<Command[]>(() => {
    const out: Command[] = NAV.map((nav) => ({
      id: `view:${nav.id}`,
      group: t('Go to'),
      label: nav.label,
      run: () => setView(nav.id),
    }))
    if (commandsOpen) {
      out.push(
        ...blockCommands(useFlowStore.getState().nodes, t, (id) => {
          // The canvas answers the request once it is on screen; from the dashboard that is a
          // view switch away.
          setView('studio')
          requestFocus(id)
        }),
      )
    }
    for (const flow of flows) {
      if (!flow.id) continue
      const id = flow.id
      out.push({
        id: `flow:${id}`,
        group: t('Flows'),
        label: t('Open {{name}}', { name: flow.name }),
        hint: flow.folder || undefined,
        run: () => void openFlow(id),
      })
      out.push({
        id: `run:${id}`,
        group: t('Flows'),
        label: t('Run {{name}}', { name: flow.name }),
        run: () => void runFlow(id),
      })
    }
    for (const run of runs.slice(0, 20)) {
      out.push({
        id: `exec:${run.id}`,
        group: t('Runs'),
        label: t('Open run of {{name}}', { name: run.flowName ?? 'flow' }),
        hint: `${run.status} · ${timeAgo(run.createdAt)}`,
        run: () => void openRun(run.id),
      })
    }
    for (const theme of THEMES) {
      out.push({
        id: `theme:${theme.id}`,
        group: t('Theme'),
        label: `${theme.icon} ${theme.label}`,
        run: () => setTheme(theme.id),
      })
    }
    return out
  }, [flows, runs, openFlow, runFlow, openRun, setView, t, commandsOpen, requestFocus])

  function renderView(): ReactNode {
    switch (view) {
      case 'flows':
        return (
          <FlowsPage
            flows={flows}
            runs={runs}
            onOpen={openFlow}
            onRun={runFlow}
            onDuplicate={duplicateFlow}
            onSandbox={sandboxFlowCopy}
            onDelete={deleteFlow}
            onNew={newFlow}
            onGenerated={openGeneratedFlow}
            onOpenRun={(id) => void openRun(id)}
            onSaveFlow={saveFlowFromDashboard}
            onRetryRun={retryRun}
            pushError={setToast}
          />
        )
      case 'usage':
        return <UsagePage />
      case 'resources':
        return <ResourcesPage pushError={setToast} />
      case 'studio':
        return (
          <>
            <Toolbar
              onFlowsChanged={refreshFlows}
              onRunStarted={onRunStarted}
              onBackToFlows={() => setView('flows')}
              onOpenRun={(id) => void openRun(id)}
              pushError={setToast}
            />
            <div
              className={styles.main}
              style={{
                gridTemplateColumns: `${paletteOpen ? `${paletteW}px` : '26px'} 1fr`,
              }}
            >
              <SidePanel
                side="left"
                label={t('the node palette')}
                railLabel={t('Add node')}
                open={paletteOpen}
                onToggle={togglePalette}
                startDrag={dragPalette}
              >
                <Palette />
              </SidePanel>
              <div className={styles.canvas}>
                <FlowCanvas />
              </div>
            </div>
            <NodeDetailsDialog />
            {runsOpen ? (
              <div className={styles.bottomWrap} style={{ '--runs-h': `${runsH}px` } as CSSProperties}>
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
                  title={t('Hide the executions panel')}
                  aria-label={t('Hide the executions panel')}
                >
                  ▾
                </button>
                <div
                  className={styles.resizeY}
                  style={{ top: -4 }}
                  onPointerDown={dragRuns((e) => window.innerHeight - e.clientY)}
                  title={t('Drag to resize')}
                />
              </div>
            ) : (
              <button
                className={styles.bottomRail}
                onClick={toggleRuns}
                title={t('Show the executions panel')}
                aria-label={t('Show the executions panel')}
              >
                ▴ {t('Executions')}
              </button>
            )}
          </>
        )
    }
  }

  return (
    <div className={styles.app}>
      <AppHeader
        view={view}
        onView={setView}
        signedInAs={signedInAs}
        organizationName={organizationName}
        onSignOut={onSignOut}
      />

      <ErrorBoundary>{renderView()}</ErrorBoundary>

      {commandsOpen && (
        <CommandPalette commands={commands} onClose={() => setCommandsOpen(false)} />
      )}

      {toast && (
        <div className={styles.toast} role="alert">
          <span className={styles.toastMessage}>{toast}</span>
          <button
            type="button"
            className={styles.toastDismiss}
            onClick={() => setToast(null)}
            aria-label={t('Dismiss notification')}
          >
            ×
          </button>
        </div>
      )}
    </div>
  )
}
