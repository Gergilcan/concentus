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

  const refresh = useCallback(() => {
    api.knowledgeDocs(baseId).then(setDocs).catch(() => setDocs([]))
  }, [baseId])

  useEffect(() => {
    refresh()
    setNote(null)
    setHits(null)
    api.knowledgeStatus().then((s) => setSemantic(s.semantic)).catch(() => setSemantic(null))
  }, [refresh])

  const upload = async (file: File) => {
    setBusy(true)
    setNote(null)
    try {
      const result = await api.uploadKnowledgeDoc(baseId, file)
      setNote(`${result.docName}: ${result.chunks} passage(s). ${result.detail}`)
      refresh()
    } catch (e) {
      setNote(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
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
          {busy ? 'Indexing…' : 'Upload document'}
        </button>
        <input
          ref={fileRef}
          type="file"
          hidden
          accept=".pdf,.docx,.doc,.xlsx,.xls,.csv,.txt,.md,.html"
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) void upload(f)
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
      // Documents only make sense on a saved base: an unsaved draft has no id to attach them to.
      extra={(draft) => (draft.id ? <Documents baseId={draft.id} /> : null)}
    />
  )
}
