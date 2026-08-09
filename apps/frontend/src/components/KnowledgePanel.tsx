import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { KnowledgeDef, KnowledgeDoc, KnowledgeHit } from '../api/types.ts'
import { CrudPanel } from './CrudPanel.tsx'
import { EmbeddingModelPanel } from './EmbeddingModelPanel.tsx'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/**
 * Documents of the base being edited: upload, list, delete, and a test search.
 *
 * The test search exists for the same reason the storage settings have a test button — the place
 * to discover a base retrieves nothing useful is here, with the query on screen, not mid-run with
 * an agent quietly working from empty context.
 */
/** Type buckets for the tabs; extension-derived because that is all a name can tell us. */
const DOC_TYPES: { key: string; label: string; exts: string[] }[] = [
  { key: 'pdf', label: 'PDF', exts: ['.pdf'] },
  { key: 'word', label: 'Word', exts: ['.doc', '.docx'] },
  { key: 'sheet', label: 'Sheets', exts: ['.xls', '.xlsx', '.csv'] },
  { key: 'text', label: 'Text', exts: ['.txt', '.md'] },
  { key: 'html', label: 'HTML', exts: ['.html'] },
]

function typeOf(name: string): string {
  const lower = name.toLowerCase()
  return DOC_TYPES.find((t) => t.exts.some((e) => lower.endsWith(e)))?.key ?? 'other'
}

/** A folder in the document tree. Files hold the full stored name, which is what rows display. */
interface TreeNode {
  name: string
  path: string
  folders: Map<string, TreeNode>
  files: KnowledgeDoc[]
}

function emptyNode(name: string, path: string): TreeNode {
  return { name, path, folders: new Map(), files: [] }
}

/**
 * Builds the nested folder tree for a page of documents.
 *
 * <p>Nested rather than grouped by full folder string: "apps/backend/docs" was one flat header,
 * which is a list of paths wearing a tree's clothes. Each segment is now its own level.
 */
export function buildTree(docs: KnowledgeDoc[]): TreeNode {
  const root = emptyNode('', '')
  for (const doc of docs) {
    let node = root
    for (const segment of doc.name.split('/').slice(0, -1)) {
      const path = node.path ? `${node.path}/${segment}` : segment
      let next = node.folders.get(segment)
      if (!next) {
        next = emptyNode(segment, path)
        node.folders.set(segment, next)
      }
      node = next
    }
    node.files.push(doc)
  }
  return root
}

const PAGE_SIZE = 20

/**
 * Folder names excluded by default when importing a folder.
 *
 * Dropping a repository on a knowledge base should index the repository, not its dependencies:
 * node_modules alone can be tens of thousands of files whose contents nobody wants an agent
 * retrieving. Matched by path segment, so a nested apps/backend/target is caught as readily as a
 * top-level one, and every entry is de-selectable — this is a default, not a policy.
 */
export const DEFAULT_EXCLUDES = [
  'node_modules', 'target', 'dist', 'build', 'out', 'bin', 'obj', 'vendor', 'coverage',
  '.git', '.next', '.nuxt', '.gradle', '.idea', '.vs', '.cache', '.venv', 'venv', '__pycache__',
]

/** Every folder name along a file's path, minus the chosen root and the file itself. */
export function pathSegments(relativePath: string): string[] {
  const parts = relativePath.split('/')
  return parts.slice(1, -1)
}

function Documents({ baseId }: { baseId: string }) {
  const [docs, setDocs] = useState<KnowledgeDoc[]>([])
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<KnowledgeHit[] | null>(null)
  const [status, setStatus] = useState<{ semantic: boolean; detail: string } | null>(null)
  const [typeTab, setTypeTab] = useState<string>('all')
  const [page, setPage] = useState(0)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  // A picked folder, held until its exclusions are confirmed.
  const [pending, setPending] = useState<File[] | null>(null)
  const [excluded, setExcluded] = useState<Set<string>>(new Set())
  const fileRef = useRef<HTMLInputElement>(null)
  const folderRef = useRef<HTMLInputElement>(null)

  const refresh = useCallback(() => {
    api.knowledgeDocs(baseId).then(setDocs).catch(() => setDocs([]))
  }, [baseId])

  const refreshStatus = useCallback(() => {
    api.knowledgeStatus().then(setStatus).catch(() => setStatus(null))
  }, [])

  useEffect(() => {
    refresh()
    setNote(null)
    setHits(null)
    setTypeTab('all')
    setPage(0)
    setCollapsed(new Set())
    setPending(null)
    refreshStatus()
  }, [refresh, refreshStatus])

  // Tabs show only the types actually present — five empty tabs teach nothing.
  const countsByType = docs.reduce<Record<string, number>>((acc, d) => {
    const t = typeOf(d.name)
    acc[t] = (acc[t] ?? 0) + 1
    return acc
  }, {})
  const filtered = typeTab === 'all' ? docs : docs.filter((d) => typeOf(d.name) === typeTab)
  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage = Math.min(page, pages - 1)
  const pageDocs = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)

  // The tree is built from the page, not the whole base: paging whole folders would make page
  // size depend on folder shape, and 20 files per page is the thing worth keeping steady.
  const tree = buildTree(pageDocs)

  const toggleFolder = (f: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(f)) next.delete(f)
      else next.add(f)
      return next
    })

  /** One folder level: its own row, then its subfolders, then its files. */
  const renderNode = (node: TreeNode, depth: number): ReactNode => (
    <div key={node.path || '(root)'}>
      {node.path !== '' && (
        <button
          className={styles.kbFolder}
          style={{ paddingLeft: depth * 14 }}
          onClick={() => toggleFolder(node.path)}
          title={node.path}
        >
          <span>{collapsed.has(node.path) ? '▸' : '▾'}</span>
          <span className={styles.kbFolderName}>{node.name}/</span>
        </button>
      )}
      {!collapsed.has(node.path) && (
        <>
          {[...node.folders.values()].map((child) => renderNode(child, depth + 1))}
          {node.files.map((d) => (
            <div key={d.name} className={styles.kbDoc} style={{ paddingLeft: (depth + 1) * 14 }}>
              {/* The full stored path, not the basename: a file's folder is part of what it is,
                  and at a glance "intro.pdf" three times over says nothing. */}
              <span className={styles.kbDocName} title={d.name}>
                {d.name}
              </span>
              <span className={styles.muted}>
                {d.chunks} passage(s){d.embedded ? '' : ' · word-overlap only'}
              </span>
              <button
                className={styles.delBtn}
                onClick={() => void api.deleteKnowledgeDoc(baseId, d.name).then(refresh)}
              >
                Delete
              </button>
            </div>
          ))}
        </>
      )}
    </div>
  )

  /** Extensions the backend can extract text from; anything else in a folder is skipped, counted. */
  const SUPPORTED = ['.pdf', '.docx', '.doc', '.xlsx', '.xls', '.csv', '.txt', '.md', '.html']

  const relPath = (f: File) =>
    (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name

  /**
   * A picked folder waits here until the exclusions are settled, instead of uploading on the
   * spot: dropping a repository in and only then discovering node_modules went with it costs a
   * long wait and a base that has to be cleaned out document by document.
   */
  const pendingUsable = pending?.filter((f) =>
    SUPPORTED.some((ext) => f.name.toLowerCase().endsWith(ext)),
  )

  // Every folder name appearing anywhere in the picked tree, with how many indexable files sit
  // under it. By name rather than by full path because that is how the intent is actually held:
  // "not node_modules", wherever it happens to be nested.
  const folderCounts = new Map<string, number>()
  for (const file of pendingUsable ?? []) {
    for (const segment of new Set(pathSegments(relPath(file)))) {
      folderCounts.set(segment, (folderCounts.get(segment) ?? 0) + 1)
    }
  }
  const folderNames = [...folderCounts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
  const selected = (pendingUsable ?? []).filter(
    (f) => !pathSegments(relPath(f)).some((s) => excluded.has(s)),
  )

  const pickFolder = (files: File[]) => {
    setNote(null)
    setPending(files)
    const present = new Set<string>()
    for (const f of files) for (const s of pathSegments(relPath(f))) present.add(s)
    setExcluded(new Set(DEFAULT_EXCLUDES.filter((d) => present.has(d))))
  }

  const upload = async (files: File[]) => {
    const usable = files.filter((f) =>
      SUPPORTED.some((ext) => f.name.toLowerCase().endsWith(ext)),
    )
    const skipped = files.length - usable.length
    if (usable.length === 0) {
      setNote(
        skipped > 0
          ? `Nothing to index: ${skipped} file(s) skipped — supported types are ${SUPPORTED.join(', ')}.`
          : 'No files selected.',
      )
      return
    }

    setBusy(true)
    setNote(null)
    // Sequential, with a live counter: a folder can hold fifty documents, and parallel uploads
    // would race the extractor and hide which file failed.
    let done = 0
    let chunks = 0
    const failures: string[] = []
    for (const file of usable) {
      setNote(`Indexing ${done + 1}/${usable.length}: ${file.name}…`)
      try {
        const result = await api.uploadKnowledgeDoc(
          baseId,
          file,
          // The folder picker exposes the path inside the chosen folder; keeping it in the stored
          // name is what makes the folder tree possible at all.
          (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
        )
        chunks += result.chunks
        done += 1
      } catch (e) {
        failures.push(`${file.name} (${e instanceof Error ? e.message : String(e)})`)
      }
    }
    refresh()
    setBusy(false)
    setNote(
      `Indexed ${done} document(s), ${chunks} passage(s).` +
        (skipped > 0 ? ` ${skipped} unsupported file(s) skipped.` : '') +
        (failures.length > 0 ? ` Failed: ${failures.join('; ')}` : ''),
    )
  }

  const search = async () => {
    if (!query.trim()) return
    try {
      setHits(await api.searchKnowledge(baseId, query.trim(), 5))
    } catch (e) {
      setNote(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div className={styles.kbDocs}>
      <h4 className={styles.h4}>Documents</h4>
      <EmbeddingModelPanel onReady={refreshStatus} />
      {status && !status.semantic && (
        <p className={panels.hint} title={status.detail}>
          Ranking by word overlap. ⓘ
        </p>
      )}
      {docs.length === 0 && <div className={styles.muted}>No documents yet.</div>}

      {docs.length > 0 && (
        <div className={styles.kbTabs}>
          <button
            className={typeTab === 'all' ? styles.kbTabOn : ''}
            onClick={() => { setTypeTab('all'); setPage(0) }}
          >
            All ({docs.length})
          </button>
          {DOC_TYPES.filter((t) => countsByType[t.key]).map((t) => (
            <button
              key={t.key}
              className={typeTab === t.key ? styles.kbTabOn : ''}
              onClick={() => { setTypeTab(t.key); setPage(0) }}
            >
              {t.label} ({countsByType[t.key]})
            </button>
          ))}
          {countsByType.other ? (
            <button
              className={typeTab === 'other' ? styles.kbTabOn : ''}
              onClick={() => { setTypeTab('other'); setPage(0) }}
            >
              Other ({countsByType.other})
            </button>
          ) : null}
        </div>
      )}

      {renderNode(tree, 0)}

      {pages > 1 && (
        <div className={styles.kbPager}>
          <button disabled={safePage === 0} onClick={() => setPage(safePage - 1)}>‹</button>
          <span className={styles.muted}>{safePage + 1} / {pages}</span>
          <button disabled={safePage >= pages - 1} onClick={() => setPage(safePage + 1)}>›</button>
        </div>
      )}

      {pending && (
        <div className={styles.excludeBox}>
          <div className={styles.excludeHead}>
            Importing <b>{selected.length}</b> of {pendingUsable?.length ?? 0} indexable file(s)
            {pending.length !== pendingUsable?.length && (
              <span className={styles.muted}> · {pending.length - (pendingUsable?.length ?? 0)} unsupported</span>
            )}
          </div>
          {folderNames.length > 0 && (
            <>
              <div className={styles.muted}>Untick a folder to leave it out (matches at any depth):</div>
              <div className={styles.excludeList}>
                {folderNames.map(([name, count]) => (
                  <label key={name} className={styles.excludeItem} title={`${count} indexable file(s)`}>
                    <input
                      type="checkbox"
                      checked={!excluded.has(name)}
                      onChange={() =>
                        setExcluded((prev) => {
                          const next = new Set(prev)
                          if (next.has(name)) next.delete(name)
                          else next.add(name)
                          return next
                        })
                      }
                    />
                    <span className={styles.kbFolderName}>{name}</span>
                    <span className={styles.muted}>{count}</span>
                  </label>
                ))}
              </div>
            </>
          )}
          <div className={styles.crudActions}>
            <button
              className={styles.newBtn}
              disabled={busy || selected.length === 0}
              onClick={() => {
                const files = selected
                setPending(null)
                void upload(files)
              }}
            >
              {busy ? 'Indexing…' : `Index ${selected.length} file(s)`}
            </button>
            <button className={styles.textLink} onClick={() => setPending(null)}>
              Cancel
            </button>
          </div>
        </div>
      )}

      <div className={styles.crudActions}>
        <button className={styles.newBtn} disabled={busy} onClick={() => fileRef.current?.click()}>
          {busy ? 'Indexing…' : 'Upload documents'}
        </button>
        <button className={styles.newBtn} disabled={busy} onClick={() => folderRef.current?.click()}>
          Upload folder
        </button>
        <input
          ref={fileRef}
          type="file"
          hidden
          multiple
          accept=".pdf,.docx,.doc,.xlsx,.xls,.csv,.txt,.md,.html"
          onChange={(e) => {
            const files = Array.from(e.target.files ?? [])
            if (files.length > 0) void upload(files)
            e.target.value = ''
          }}
        />
        {/* webkitdirectory turns the picker into a folder picker; the browser then hands over
            every file inside, recursively. Unsupported types are filtered with a count rather
            than erroring — a real folder always has a .gitignore or a .png in it somewhere. */}
        <input
          ref={folderRef}
          type="file"
          hidden
          multiple
          {...({ webkitdirectory: '' } as Record<string, string>)}
          onChange={(e) => {
            const files = Array.from(e.target.files ?? [])
            if (files.length > 0) pickFolder(files)
            e.target.value = ''
          }}
        />
      </div>
      {note && <p className={panels.hint}>{note}</p>}

      <h4 className={styles.h4}>Try a search</h4>
      <div className={styles.kbSearch}>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void search()
          }}
          placeholder="What would an agent ask?"
        />
        <button className={styles.newBtn} onClick={() => void search()}>
          Search
        </button>
      </div>
      {hits && hits.length === 0 && <div className={styles.muted}>No matching passages.</div>}
      {hits?.map((h, i) => (
        <div key={i} className={styles.kbHit}>
          <div className={styles.kbHitHead}>
            {h.docName} · passage {h.seq + 1} · score {h.score.toFixed(3)}
          </div>
          <div className={styles.kbHitBody}>{h.content.slice(0, 400)}</div>
        </div>
      ))}
    </div>
  )
}

export function KnowledgePanel() {
  return (
    <CrudPanel<KnowledgeDef>
      title="Knowledge bases"
      fields={[
        { key: 'name', label: 'Name' },
        { key: 'description', label: 'Description', placeholder: 'What lives here' },
      ]}
      labelOf={(k) => k.name}
      idOf={(k) => k.id}
      empty={() => ({ name: '', description: '' })}
      load={api.listKnowledge}
      save={api.saveKnowledge}
      remove={api.deleteKnowledge}
      // Documents attach to a saved base — an unsaved draft has no id to attach them to. Said on
      // screen rather than implied by absence: a form showing only name and description reads as
      // "this is all there is", and the documents section appearing only after Save looked exactly
      // like the feature not existing.
      extra={(draft) =>
        draft.id ? (
          <Documents baseId={draft.id} />
        ) : (
          <p className={panels.hint}>
            <b>Save the base first</b> — documents and search appear here after.
          </p>
        )
      }
    />
  )
}
