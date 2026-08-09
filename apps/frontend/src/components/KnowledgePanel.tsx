import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { KnowledgeDef, KnowledgeDoc, KnowledgeHit } from '../api/types.ts'
import { CrudPanel } from './CrudPanel.tsx'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/**
 * Documents of the base being edited: upload, list, delete, and a test search.
 *
 * The test search exists for the same reason the storage settings have a test button — the place
 * to discover a base retrieves nothing useful is here, with the query on screen, not mid-run with
 * an agent quietly working from empty context.
 */
function Documents({ baseId }: { baseId: string }) {
  const [docs, setDocs] = useState<KnowledgeDoc[]>([])
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [hits, setHits] = useState<KnowledgeHit[] | null>(null)
  const [semantic, setSemantic] = useState<boolean | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const folderRef = useRef<HTMLInputElement>(null)

  const refresh = useCallback(() => {
    api.knowledgeDocs(baseId).then(setDocs).catch(() => setDocs([]))
  }, [baseId])

  useEffect(() => {
    refresh()
    setNote(null)
    setHits(null)
    api.knowledgeStatus().then((s) => setSemantic(s.semantic)).catch(() => setSemantic(null))
  }, [refresh])

  /** Extensions the backend can extract text from; anything else in a folder is skipped, counted. */
  const SUPPORTED = ['.pdf', '.docx', '.doc', '.xlsx', '.xls', '.csv', '.txt', '.md', '.html']

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
        const result = await api.uploadKnowledgeDoc(baseId, file)
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
      {semantic === false && (
        <p className={panels.hint}>
          No embedding model is reachable, so retrieval ranks by word overlap. Configure a local
          model server (<code>ollama pull bge-m3</code>) and re-upload to rank by meaning.
        </p>
      )}
      {docs.length === 0 && <div className={styles.muted}>No documents yet.</div>}
      {docs.map((d) => (
        <div key={d.name} className={styles.kbDoc}>
          <span className={styles.kbDocName}>{d.name}</span>
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
            if (files.length > 0) void upload(files)
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
        {
          key: 'description',
          label: 'Description',
          placeholder: 'What lives here — it is how you will tell twelve bases apart',
        },
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
            <b>Save the base first</b> — then this panel grows a Documents section where you upload
            files or whole folders, and a test search.
          </p>
        )
      }
    />
  )
}
