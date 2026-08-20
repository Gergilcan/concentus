import { useEffect, useMemo, useState, type DragEvent } from 'react'
import { api } from '../api/client.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import type { BackendFlow, GoldenStatus, RunSummary } from '../api/types.ts'
import { CompareRunsModal } from './CompareRunsModal.tsx'
import { DescribeFlowModal } from './DescribeFlowModal.tsx'
import { DoctorModal } from './DoctorModal.tsx'
import { FlowCard } from './FlowCard.tsx'
import { breadcrumbOf, childFolders, flowsAt, folderOf, moveFolder, normalizePath } from './folderTree.ts'
import { SettingsModal, VersionsModal } from './FlowModals.tsx'
import { FlowsKpis } from './FlowsKpis.tsx'
import { decided, type Sort } from './flowFormat.ts'
import {
  computeStats,
  downloadFlowJson,
  groupRunsByFlow,
  normalizeImportedFlow,
  recentRuns,
  visibleFlows,
} from './flowsDashboard.ts'
import { FlowsToolbar } from './FlowsToolbar.tsx'
import { RecipesModal } from './RecipesModal.tsx'
import { RecentRunsList } from './RecentRunsList.tsx'
import styles from './flows.module.scss'

/** dataTransfer types for the dashboard's drag & drop — custom so foreign drags are ignored. */
const FLOW_DND = 'application/x-concentus-flow'
const FOLDER_DND = 'application/x-concentus-folder'

interface Props {
  flows: BackendFlow[]
  runs: RunSummary[]
  onOpen: (id: string) => void
  onRun: (id: string) => void
  onDuplicate: (flow: BackendFlow) => void
  /** Makes a plan-mode, dry-run copy of a flow. */
  onSandbox?: (flow: BackendFlow) => Promise<void> | void
  onDelete: (id: string) => void
  onNew: () => void
  /** Puts a generated draft on the canvas and opens Studio. Nothing has been saved. */
  onGenerated: (flow: BackendFlow) => void
  onOpenRun: (runId: string) => void
  onSaveFlow: (flow: BackendFlow) => Promise<void>
  onRetryRun: (runId: string) => void
  pushError: (m: string) => void
}

export function FlowsPage({
  flows,
  runs,
  onOpen,
  onRun,
  onDuplicate,
  onSandbox,
  onDelete,
  onNew,
  onGenerated,
  onOpenRun,
  onSaveFlow,
  onRetryRun,
  pushError,
}: Props) {
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<Sort>('recent')
  /**
   * Whether the recent-executions column is open.
   *
   * <p>Remembered, because it is a fact about this screen and this person — a wide monitor wants
   * it open, a laptop wants the width for the flows — and re-deciding it on every visit is the
   * thing that makes a fold not worth using.
   */
  const [runsOpen, setRunsOpen] = useState(
    () => localStorage.getItem('concentus.recentRuns') !== 'closed',
  )

  useEffect(() => {
    localStorage.setItem('concentus.recentRuns', runsOpen ? 'open' : 'closed')
  }, [runsOpen])
  const [settingsFor, setSettingsFor] = useState<BackendFlow | null>(null)
  const [versionsFor, setVersionsFor] = useState<BackendFlow | null>(null)
  const [describing, setDescribing] = useState(false)
  const [doctorFor, setDoctorFor] = useState<BackendFlow | null>(null)
  const [pickingRecipe, setPickingRecipe] = useState(false)
  // Which flows have a golden reference and whether they drifted from it. Derived server-side, so
  // this is a read: re-fetched whenever the flow list changes, which is what a save produces.
  const [goldenStatuses, setGoldenStatuses] = useState<GoldenStatus[]>([])
  // A check this page started, waiting for its run to finish so the comparison can open by itself.
  const [checking, setChecking] = useState<{ flowId: string; runId: string; referenceId: string } | null>(null)
  const [comparing, setComparing] = useState<{ referenceId: string; candidateId: string } | null>(null)

  useEffect(() => {
    let alive = true
    void api
      .listGoldenStatus()
      .then((s) => alive && setGoldenStatuses(s))
      // A dashboard that cannot read this still works — it just cannot offer the check.
      .catch(() => alive && setGoldenStatuses([]))
    return () => {
      alive = false
    }
  }, [flows])

  const goldenByFlow = useMemo(
    () => new Map(goldenStatuses.map((s) => [s.flowId, s])),
    [goldenStatuses],
  )

  const startGoldenCheck = async (status: GoldenStatus) => {
    setChecking(null)
    try {
      const started = await api.goldenRerun(status.runId)
      setChecking({ flowId: status.flowId, runId: started.id, referenceId: status.runId })
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  // The dashboard already polls runs, so watching the started one costs nothing extra: when it
  // reaches a decided state the comparison opens on its own — which is the whole point of firing
  // it from a card rather than making the user go and find it.
  useEffect(() => {
    if (!checking) return
    const run = runs.find((r) => r.id === checking.runId)
    if (!run || !decided(run)) return
    setComparing({ referenceId: checking.referenceId, candidateId: checking.runId })
    setChecking(null)
  }, [runs, checking])

  const runsByFlow = useMemo(() => groupRunsByFlow(runs), [runs])
  const stats = useMemo(() => computeStats(flows, runs), [flows, runs])
  const visible = useMemo(
    // No tag filter any more: the pill bar it came from is gone, and what it did — narrowing to
    // one tag — the search box already does, since it matches tags as well as names.
    () => visibleFlows(flows, runsByFlow, query, sort, null),
    [flows, query, sort, runsByFlow],
  )
  const recent = useMemo(() => recentRuns(runs), [runs])

  // Folders are a real place you enter, not sections you unfold: the dashboard shows the current
  // folder's subfolders as tiles plus its own flows, with a breadcrumb back up. A first launch
  // shows one "Samples" tile instead of six sample cards. Searching or tag-filtering suspends the
  // tree and shows every match flat — a filter that hid its matches inside folders would just be
  // broken search.
  const [path, setPath] = useState('')
  const filtering = query.trim() !== ''
  const tiles = useMemo(() => childFolders(visible, path), [visible, path])
  const here = useMemo(() => flowsAt(visible, path), [visible, path])
  const crumbs = useMemo(() => breadcrumbOf(path), [path])

  // Folders normally exist because a flow lives under them, which left no way to CREATE one
  // before having something to put in it. Drafts fill that gap: "New folder" records a path in
  // localStorage and the tile appears empty, ready to receive a drag. The moment a flow lands
  // there the folder is derivable and the draft is just shadowed; if its flows ever leave, it
  // reappears as an empty tile rather than vanishing under the user. The ✕ removes an empty one.
  const [drafts, setDrafts] = useState<string[]>(() => {
    try {
      return JSON.parse(localStorage.getItem('flows.folderDrafts') ?? '[]') as string[]
    } catch {
      return []
    }
  })
  const saveDrafts = (next: string[]) => {
    setDrafts(next)
    localStorage.setItem('flows.folderDrafts', JSON.stringify(next))
  }
  const draftTiles = useMemo(() => {
    const prefix = path === '' ? '' : path + '/'
    const realNames = new Set(tiles.map((t) => t.name))
    // A nested draft ("Team/Ops") surfaces at each level by its first segment, exactly like a
    // real folder would — dropping it here would make "use / to nest" look like a silent no-op.
    const names = drafts
      .filter((d) => (prefix === '' || d.startsWith(prefix)) && d !== path)
      .map((d) => d.slice(prefix.length).split('/')[0])
    return [...new Set(names)].filter((name) => name !== '' && !realNames.has(name)).sort()
  }, [drafts, tiles, path])
  // Inline naming instead of window.prompt(): Electron's renderer doesn't implement prompt()
  // (it throws), so the tile itself turns into an input. Enter commits, Escape/blur cancels.
  const [namingFolder, setNamingFolder] = useState(false)
  const commitFolder = (raw: string) => {
    setNamingFolder(false)
    const name = normalizePath(raw)
    if (!name) return
    const full = path === '' ? name : path + '/' + name
    if (!drafts.includes(full)) saveDrafts([...drafts, full])
  }
  const removeDraft = (full: string) =>
    saveDrafts(drafts.filter((d) => d !== full && !d.startsWith(full + '/')))


  const patch = async (flow: BackendFlow, changes: Partial<BackendFlow>) => {
    try {
      await onSaveFlow({ ...flow, ...changes })
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  // Drag & drop: flow cards and folder tiles both drag; folder tiles and breadcrumb segments both
  // receive. Payloads travel as custom dataTransfer types so a stray file dropped on a tile is
  // ignored rather than misread. `dropTarget` drives the visual highlight only.
  const [dropTarget, setDropTarget] = useState<string | null>(null)

  const acceptsDrop = (e: DragEvent) =>
    e.dataTransfer.types.includes(FLOW_DND) || e.dataTransfer.types.includes(FOLDER_DND)

  const dragOver = (target: string) => (e: DragEvent) => {
    if (!acceptsDrop(e)) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    setDropTarget(target)
  }

  const drop = (target: string) => (e: DragEvent) => {
    e.preventDefault()
    setDropTarget(null)
    const flowId = e.dataTransfer.getData(FLOW_DND)
    if (flowId) {
      const flow = flows.find((f) => f.id === flowId)
      if (flow && folderOf(flow) !== target) void patch(flow, { folder: target })
      return
    }
    const from = e.dataTransfer.getData(FOLDER_DND)
    if (!from) return
    const changes = moveFolder(flows, from, target)
    if (changes.length === 0) return
    void Promise.all(changes.map(({ flow, folder }) => patch(flow, { folder })))
    // If the folder being viewed just moved, follow it — staying on a dead path shows a void.
    if (path === from || path.startsWith(from + '/')) setPath('')
  }

  // One card, whatever grid it sits in — the flat search view and every folder render the same.
  const renderCard = (flow: BackendFlow) => (
    <FlowCard
      key={flow.id}
      flow={flow}
      flowRuns={flow.id ? (runsByFlow.get(flow.id) ?? []) : []}
      onOpen={onOpen}
      onRun={onRun}
      onDuplicate={onDuplicate}
      onSandbox={onSandbox}
      onDelete={onDelete}
      patch={patch}
      exportFlow={downloadFlowJson}
      setVersionsFor={setVersionsFor}
      setSettingsFor={setSettingsFor}
      setDoctorFor={setDoctorFor}
      // A tag on a card still narrows the list; it does it through the search box now, which is
      // the one place left that filters and the one somebody can clear without hunting for a bar.
      setTagFilter={(tag) => setQuery(tag ?? '')}
      onDragStart={(e) => flow.id && e.dataTransfer.setData(FLOW_DND, flow.id)}
      golden={flow.id ? goldenByFlow.get(flow.id) : undefined}
      onGoldenCheck={startGoldenCheck}
      goldenChecking={!!checking && checking.flowId === flow.id}
    />
  )

  const importFlow = async (file: File) => {
    try {
      await onSaveFlow(normalizeImportedFlow(JSON.parse(await file.text()) as BackendFlow))
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.inner}>
        {/* No title and no strapline. This is the screen the application opens on, seen more
            often than any other, and it was spending its first hundred pixels telling somebody who
            uses it daily what it is. The toolbar says what can be done here, which is the part
            that is not obvious. */}
        <header className={styles.pageHead}>
          <FlowsToolbar
            query={query}
            onQueryChange={setQuery}
            sort={sort}
            onSortChange={setSort}
            onImport={(f) => void importFlow(f)}
            onNew={onNew}
            onDescribe={() => setDescribing(true)}
            onRecipes={() => setPickingRecipe(true)}
          />
        </header>

        <FlowsKpis stats={stats} />

        <div className={styles.body}>
          <section className={styles.gridCol}>
            {visible.length === 0 && draftTiles.length === 0 ? (
              <div className={styles.emptyCard}>
                <div className={styles.emptyIcon}>⬡</div>
                <h3>{flows.length === 0 ? 'No flows yet' : 'Nothing matches those filters'}</h3>
                <p>
                  {flows.length === 0
                    ? 'Create a flow, drop in a coordinator and a couple of sub-agents, and run it.'
                    : 'Try a different name or tag.'}
                </p>
                {flows.length === 0 && (
                  <button className={styles.primary} onClick={onNew}>
                    + New flow
                  </button>
                )}
              </div>
            ) : filtering ? (
              // Filtering: the tree steps aside and every match shows flat, wherever it lives.
              <div className={styles.grid}>{visible.map(renderCard)}</div>
            ) : (
              <>
                {path !== '' && (
                  <nav className={styles.crumbs} aria-label="Folder path">
                    {crumbs.map((crumb, i) => (
                      <span key={crumb.path} className={styles.crumbSeg}>
                        {i > 0 && <span className={styles.crumbSep}>/</span>}
                        <button
                          className={cx(
                            styles.crumb,
                            i === crumbs.length - 1 && styles.crumbHere,
                            dropTarget === 'crumb:' + crumb.path && styles.dropOver,
                          )}
                          onClick={() => setPath(crumb.path)}
                          onDragOver={dragOver('crumb:' + crumb.path)}
                          onDragLeave={() => setDropTarget(null)}
                          onDrop={drop(crumb.path)}
                        >
                          {crumb.label}
                        </button>
                      </span>
                    ))}
                  </nav>
                )}
                <div className={styles.folderGrid}>
                  {tiles.map(({ name, count }) => {
                    const full = path === '' ? name : path + '/' + name
                    return (
                      <button
                        key={full}
                        className={cx(styles.folderTile, dropTarget === full && styles.dropOver)}
                        aria-label={`Folder ${name}`}
                        onClick={() => setPath(full)}
                        draggable
                        onDragStart={(e) => e.dataTransfer.setData(FOLDER_DND, full)}
                        onDragOver={dragOver(full)}
                        onDragLeave={() => setDropTarget(null)}
                        onDrop={drop(full)}
                      >
                        <span className={styles.folderGlyph} aria-hidden>
                          <span className={styles.folderTab} />
                        </span>
                        <span className={styles.folderName}>{name}</span>
                        <span className={styles.folderCount}>{count}</span>
                      </button>
                    )
                  })}
                  {draftTiles.map((name) => {
                    const full = path === '' ? name : path + '/' + name
                    return (
                      <button
                        key={'draft:' + full}
                        className={cx(styles.folderTile, dropTarget === full && styles.dropOver)}
                        aria-label={`Folder ${name}`}
                        onClick={() => setPath(full)}
                        onDragOver={dragOver(full)}
                        onDragLeave={() => setDropTarget(null)}
                        onDrop={drop(full)}
                      >
                        <span className={cx(styles.folderGlyph, styles.folderGlyphEmpty)} aria-hidden>
                          <span className={styles.folderTab} />
                        </span>
                        <span className={styles.folderName}>{name}</span>
                        <span
                          role="button"
                          aria-label={`Remove empty folder ${name}`}
                          title="Remove this empty folder"
                          className={styles.folderRemove}
                          onClick={(e) => {
                            e.stopPropagation()
                            removeDraft(full)
                          }}
                        >
                          ✕
                        </span>
                      </button>
                    )
                  })}
                  {namingFolder ? (
                    <input
                      className={styles.folderNewInput}
                      placeholder="Folder name (use / to nest)"
                      aria-label="New folder name"
                      autoFocus
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') commitFolder(e.currentTarget.value)
                        if (e.key === 'Escape') setNamingFolder(false)
                      }}
                      onBlur={() => setNamingFolder(false)}
                    />
                  ) : (
                    <button className={styles.folderNew} onClick={() => setNamingFolder(true)}>
                      + New folder
                    </button>
                  )}
                </div>
                {here.length > 0 ? (
                  <div className={styles.grid}>{here.map(renderCard)}</div>
                ) : (
                  tiles.length === 0 &&
                  draftTiles.length === 0 && (
                    <p className={styles.folderEmpty}>
                      This folder is empty — drag a flow onto it, or set it in a flow's Settings.
                    </p>
                  )
                )}
              </>
            )}
          </section>

          {/* Sized by what is in it, and foldable. It used to be a fixed column holding a
              handful of rows and a lot of nothing, next to the list people actually came for. */}
          <aside className={runsOpen ? styles.sideCol : styles.sideColClosed}>
            <button
              type="button"
              className={styles.sideToggle}
              aria-expanded={runsOpen}
              onClick={() => setRunsOpen(!runsOpen)}
              title={runsOpen ? 'Hide recent executions' : 'Show recent executions'}
            >
              <span>Recent executions</span>
              <span aria-hidden="true">{runsOpen ? '▸' : '◂'}</span>
            </button>
            {runsOpen && (
              <RecentRunsList runs={recent} onOpenRun={onOpenRun} onRetryRun={onRetryRun} />
            )}
          </aside>
        </div>
      </div>

      {settingsFor && (
        <SettingsModal
          flow={settingsFor}
          onClose={() => setSettingsFor(null)}
          onSave={async (changes) => {
            await patch(settingsFor, changes)
            setSettingsFor(null)
          }}
        />
      )}

      {versionsFor && (
        <VersionsModal flow={versionsFor} onClose={() => setVersionsFor(null)} pushError={pushError} />
      )}

      {pickingRecipe && (
        <RecipesModal
          onClose={() => setPickingRecipe(false)}
          // Straight onto the canvas: the point of a recipe is having something to run, and a
          // flow that lands in a list still has to be found before it exists to you.
          onSaved={(flow) => onGenerated(flow)}
          pushError={pushError}
        />
      )}

      {doctorFor?.id && (
        <DoctorModal
          flowId={doctorFor.id}
          flowName={doctorFor.name}
          onClose={() => setDoctorFor(null)}
        />
      )}

      {comparing && (
        <CompareRunsModal
          referenceId={comparing.referenceId}
          candidateId={comparing.candidateId}
          onClose={() => setComparing(null)}
        />
      )}

      {describing && (
        <DescribeFlowModal
          onClose={() => setDescribing(false)}
          onGenerated={onGenerated}
          pushError={pushError}
        />
      )}
    </div>
  )
}
