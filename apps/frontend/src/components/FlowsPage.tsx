import { useMemo, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
import type { BackendFlow, RunSummary } from '../api/types.ts'
import { FlowCard } from './FlowCard.tsx'
import { SettingsModal, VersionsModal } from './FlowModals.tsx'
import { FlowsKpis } from './FlowsKpis.tsx'
import { type Sort } from './flowFormat.ts'
import {
  collectTags,
  computeStats,
  downloadFlowJson,
  groupRunsByFlow,
  normalizeImportedFlow,
  recentRuns,
  visibleFlows,
} from './flowsDashboard.ts'
import { FlowsToolbar } from './FlowsToolbar.tsx'
import { RecentRunsList } from './RecentRunsList.tsx'
import { TagFilterBar } from './TagFilterBar.tsx'
import styles from './flows.module.scss'

interface Props {
  flows: BackendFlow[]
  runs: RunSummary[]
  onOpen: (id: string) => void
  onRun: (id: string) => void
  onDuplicate: (flow: BackendFlow) => void
  onDelete: (id: string) => void
  onNew: () => void
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
  onDelete,
  onNew,
  onOpenRun,
  onSaveFlow,
  onRetryRun,
  pushError,
}: Props) {
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<Sort>('recent')
  const [tagFilter, setTagFilter] = useState<string | null>(null)
  const [settingsFor, setSettingsFor] = useState<BackendFlow | null>(null)
  const [versionsFor, setVersionsFor] = useState<BackendFlow | null>(null)

  const runsByFlow = useMemo(() => groupRunsByFlow(runs), [runs])
  const allTags = useMemo(() => collectTags(flows), [flows])
  const stats = useMemo(() => computeStats(flows, runs), [flows, runs])
  const visible = useMemo(
    () => visibleFlows(flows, runsByFlow, query, sort, tagFilter),
    [flows, query, sort, tagFilter, runsByFlow],
  )
  const recent = useMemo(() => recentRuns(runs), [runs])

  // Folders: root flows first, then one collapsible section per folder. Sections start CLOSED —
  // that is the whole point for a first launch, where every flow on screen is a bundled sample
  // and a wall of them reads as clutter, not help. Open state is a screen habit, so it lives in
  // localStorage; searching or filtering opens everything, because a filter that hides its own
  // matches inside closed folders would just be broken search.
  const grouped = useMemo(() => {
    const root: BackendFlow[] = []
    const byFolder = new Map<string, BackendFlow[]>()
    for (const flow of visible) {
      const folder = (flow.folder ?? '').trim()
      if (!folder) root.push(flow)
      else byFolder.set(folder, [...(byFolder.get(folder) ?? []), flow])
    }
    return { root, folders: [...byFolder.entries()].sort((a, b) => a[0].localeCompare(b[0])) }
  }, [visible])
  const allFolders = useMemo(
    () => [...new Set(flows.map((f) => (f.folder ?? '').trim()).filter(Boolean))].sort(),
    [flows],
  )
  const filtering = query.trim() !== '' || tagFilter !== null
  const [openFolders, setOpenFolders] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem('flows.openFolders') ?? '[]') as string[])
    } catch {
      return new Set()
    }
  })
  const toggleFolder = (name: string) => {
    setOpenFolders((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      localStorage.setItem('flows.openFolders', JSON.stringify([...next]))
      return next
    })
  }

  const patch = async (flow: BackendFlow, changes: Partial<BackendFlow>) => {
    try {
      await onSaveFlow({ ...flow, ...changes })
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  // One card, whatever grid it sits in — the root and every folder render the same thing.
  const renderCard = (flow: BackendFlow) => (
    <FlowCard
      key={flow.id}
      flow={flow}
      flowRuns={flow.id ? (runsByFlow.get(flow.id) ?? []) : []}
      onOpen={onOpen}
      onRun={onRun}
      onDuplicate={onDuplicate}
      onDelete={onDelete}
      patch={patch}
      exportFlow={downloadFlowJson}
      setVersionsFor={setVersionsFor}
      setSettingsFor={setSettingsFor}
      setTagFilter={setTagFilter}
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
        <header className={styles.pageHead}>
          <div>
            <h1 className={styles.title}>Flows</h1>
            <p className={styles.subtitle}>
              Design multi-agent flows, run them on your Claude subscription, and watch every step.
            </p>
          </div>
          <FlowsToolbar
            query={query}
            onQueryChange={setQuery}
            sort={sort}
            onSortChange={setSort}
            onImport={(f) => void importFlow(f)}
            onNew={onNew}
          />
        </header>

        <FlowsKpis stats={stats} />

        <TagFilterBar tags={allTags} activeTag={tagFilter} onSelect={setTagFilter} />

        <div className={styles.body}>
          <section className={styles.gridCol}>
            {visible.length === 0 ? (
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
            ) : (
              <>
                {grouped.root.length > 0 && (
                  <div className={styles.grid}>{grouped.root.map(renderCard)}</div>
                )}
                {grouped.folders.map(([name, items]) => {
                  const open = filtering || openFolders.has(name)
                  return (
                    <div key={name} className={styles.folder}>
                      <button
                        className={styles.folderHead}
                        aria-label={`Folder ${name}`}
                        aria-expanded={open}
                        onClick={() => toggleFolder(name)}
                      >
                        <span className={styles.folderArrow}>{open ? '▾' : '▸'}</span>
                        {name}
                        <span className={styles.folderCount}>{items.length}</span>
                      </button>
                      {open && <div className={styles.grid}>{items.map(renderCard)}</div>}
                    </div>
                  )
                })}
              </>
            )}
          </section>

          <aside className={styles.sideCol}>
            <h2 className={styles.sideTitle}>Recent executions</h2>
            <RecentRunsList runs={recent} onOpenRun={onOpenRun} onRetryRun={onRetryRun} />
          </aside>
        </div>
      </div>

      {settingsFor && (
        <SettingsModal
          flow={settingsFor}
          folders={allFolders}
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
    </div>
  )
}
