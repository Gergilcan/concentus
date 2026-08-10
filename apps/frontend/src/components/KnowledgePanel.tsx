import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
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
 * Until the backend answers, the safe common core. The real list comes from
 * /knowledge/capabilities, because a hand-kept copy had already drifted twice: it offered
 * .doc/.xls, which no extractor claims and which failed at ingest — and it silently dropped
 * images, which the backend reads fine wherever OCR is installed.
 */
const FALLBACK_EXTENSIONS = ['.pdf', '.docx', '.xlsx', '.csv', '.txt', '.md', '.html']

function indexable(files: File[], supported: string[]): File[] {
  return files.filter((f) => supported.some((ext) => f.name.toLowerCase().endsWith(ext)))
}

/** Adds or removes a key, returning a new Set — the two toggles here were the same five lines. */
function toggled<T>(prev: Set<T>, key: T): Set<T> {
  const next = new Set(prev)
  if (!next.delete(key)) next.add(key)
  return next
}

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

/** One line of the rendered tree: a folder header or a document, at a nesting depth. */
export type TreeRow =
  | { kind: 'folder'; node: TreeNode; depth: number }
  | { kind: 'file'; doc: KnowledgeDoc; depth: number }

/**
 * Flattens the tree into the rows actually on screen, honouring what is expanded.
 *
 * <p>This is what pagination counts. Paginating files alone made a page of twenty files render as
 * anything from twenty to sixty lines depending on how many folder headers came with them, and a
 * collapsed folder still consumed its files' worth of the page while showing one line. Twenty
 * <em>rows</em> is the thing the user can actually see and count.
 */
export function flattenTree(node: TreeNode, expanded: Set<string>, depth = 0): TreeRow[] {
  const rows: TreeRow[] = []
  for (const child of node.folders.values()) {
    rows.push({ kind: 'folder', node: child, depth })
    if (expanded.has(child.path)) rows.push(...flattenTree(child, expanded, depth + 1))
  }
  for (const doc of node.files) rows.push({ kind: 'file', doc, depth })
  return rows
}

/** Documents anywhere under a folder — what deleting it would actually take. */
export function countUnder(node: TreeNode): number {
  let total = node.files.length
  for (const child of node.folders.values()) total += countUnder(child)
  return total
}

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
  const [typeTab, setTypeTab] = useState<string>('all')
  const [page, setPage] = useState(0)
  // Expanded, not collapsed: the default is everything folded, and tracking what the user opened
  // means a folder that appears after an upload starts folded too, instead of needing to be added
  // to a collapse set nobody remembered to update.
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  // A picked folder, held until its exclusions are confirmed.
  const [pending, setPending] = useState<File[] | null>(null)
  const [supported, setSupported] = useState<string[]>(FALLBACK_EXTENSIONS)
  const [excluded, setExcluded] = useState<Set<string>>(new Set())
  const fileRef = useRef<HTMLInputElement>(null)
  const folderRef = useRef<HTMLInputElement>(null)

  const refresh = useCallback(() => {
    api.knowledgeDocs(baseId).then(setDocs).catch(() => setDocs([]))
  }, [baseId])

  useEffect(() => {
    refresh()
    setNote(null)
    setHits(null)
    setTypeTab('all')
    setPage(0)
    setExpanded(new Set())
    setPending(null)
    api.knowledgeCapabilities().then((c) => setSupported(c.extensions)).catch(() => {})
  }, [refresh])

  // Tabs show only the types actually present — five empty tabs teach nothing.
  const countsByType = useMemo(
    () =>
      docs.reduce<Record<string, number>>((acc, d) => {
        const t = typeOf(d.name)
        acc[t] = (acc[t] ?? 0) + 1
        return acc
      }, {}),
    [docs],
  )
  // Memoised together: a repo-sized base rebuilt the whole tree on every keystroke in the search
  // box and on every status poll, then re-walked each visible folder to count it.
  const filtered = useMemo(
    () => (typeTab === 'all' ? docs : docs.filter((d) => typeOf(d.name) === typeTab)),
    [docs, typeTab],
  )

  // The whole base, not a page of it: what gets paginated is the rows on screen, which depends
  // on what is expanded, so the tree has to exist in full before it can be sliced.
  const tree = useMemo(() => buildTree(filtered), [filtered])
  const rows = useMemo(() => flattenTree(tree, expanded), [tree, expanded])
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE))
  const safePage = Math.min(page, pages - 1)
  const pageRows = rows.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)

  const toggleFolder = (f: string) => setExpanded((prev) => toggled(prev, f))

  const deleteFolder = async (node: TreeNode) => {
    const total = countUnder(node)
    if (!confirm(`Delete "${node.path}" and the ${total} document(s) under it? This cannot be undone.`)) {
      return
    }
    try {
      const result = await api.deleteKnowledgeFolder(baseId, node.path)
      setNote(`Deleted ${result.deleted} document(s) from ${node.path}.`)
      refresh()
    } catch (e) {
      setNote(errMessage(e))
    }
  }

  const renderRow = (row: TreeRow): ReactNode =>
    row.kind === 'folder' ? (
      // A row, not a single button: the toggle and the delete are two actions, and a button
      // inside a button is invalid HTML that browsers resolve by dropping one of them.
      <div key={`d:${row.node.path}`} className={styles.kbFolderRow} style={{ paddingLeft: row.depth * 14 }}>
        <button className={styles.kbFolder} onClick={() => toggleFolder(row.node.path)} title={row.node.path}>
          <span>{expanded.has(row.node.path) ? '▾' : '▸'}</span>
          <span className={styles.kbFolderName}>{row.node.name}/</span>
          <span className={styles.muted}>{countUnder(row.node)}</span>
        </button>
        <button
          className={styles.delBtn}
          title={`Delete this folder and everything under it`}
          onClick={() => void deleteFolder(row.node)}
        >
          Delete
        </button>
      </div>
    ) : (
      <div key={`f:${row.doc.name}`} className={styles.kbDoc} style={{ paddingLeft: row.depth * 14 }}>
        {/* The full stored path, not the basename: a file's folder is part of what it is, and at
            a glance "intro.pdf" three times over says nothing. */}
        <span className={styles.kbDocName} title={row.doc.name}>
          {row.doc.name}
        </span>
        <span className={styles.muted}>
          {row.doc.chunks} passage(s){row.doc.embedded ? '' : ' · word-overlap only'}
        </span>
        <button
          className={styles.delBtn}
          onClick={() => void api.deleteKnowledgeDoc(baseId, row.doc.name).then(refresh)}
        >
          Delete
        </button>
      </div>
    )

  const relPath = (f: File) =>
    (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name

  /**
   * A picked folder waits here until the exclusions are settled, instead of uploading on the
   * spot: dropping a repository in and only then discovering node_modules went with it costs a
   * long wait and a base that has to be cleaned out document by document.
   */
  const pendingUsable = useMemo(() => (pending ? indexable(pending, supported) : undefined), [pending, supported])

  // Every folder name appearing anywhere in the picked tree, with how many indexable files sit
  // under it. By name rather than by full path because that is how the intent is actually held:
  // "not node_modules", wherever it happens to be nested.
  const folderNames = useMemo(() => {
    const counts = new Map<string, number>()
    for (const file of pendingUsable ?? []) {
      for (const segment of new Set(pathSegments(relPath(file)))) {
        counts.set(segment, (counts.get(segment) ?? 0) + 1)
      }
    }
    return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
  }, [pendingUsable])
  const selected = useMemo(
    () => (pendingUsable ?? []).filter((f) => !pathSegments(relPath(f)).some((s) => excluded.has(s))),
    [pendingUsable, excluded],
  )

  const pickFolder = (files: File[]) => {
    setNote(null)
    setPending(files)
    const present = new Set<string>()
    for (const f of files) for (const s of pathSegments(relPath(f))) present.add(s)
    setExcluded(new Set(DEFAULT_EXCLUDES.filter((d) => present.has(d))))
  }

  const upload = async (files: File[]) => {
    const usable = indexable(files, supported)
    const skipped = files.length - usable.length
    if (usable.length === 0) {
      setNote(
        skipped > 0
          ? `Nothing to index: ${skipped} file(s) skipped — supported types are ${supported.join(', ')}.`
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
          relPath(file),
        )
        chunks += result.chunks
        done += 1
      } catch (e) {
        failures.push(`${file.name} (${errMessage(e)})`)
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
      setNote(errMessage(e))
    }
  }

  return (
    <div className={styles.kbDocs}>
      <h4 className={styles.h4}>Documents</h4>
      <EmbeddingModelPanel />
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

      {pageRows.map(renderRow)}

      {pages > 1 && (
        <div className={styles.kbPager}>
          <button disabled={safePage === 0} onClick={() => setPage(safePage - 1)}>‹</button>
          <span className={styles.muted}>{safePage + 1} / {pages} · {rows.length} rows</span>
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
                      onChange={() => setExcluded((prev) => toggled(prev, name))}
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
          accept={supported.join(',')}
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
