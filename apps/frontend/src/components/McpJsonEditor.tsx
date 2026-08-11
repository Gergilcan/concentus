import { useState } from 'react'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/**
 * The MCP registry as one editable document, in mcp.json's own shape.
 *
 * This is how external servers install: an MCP README ends with "add this to your mcp.json", and
 * this editor accepts exactly that snippet — including the stdio form (command/args/env) that has
 * no place in a URL form. Saving REPLACES the registry: servers are matched by name, new names
 * are created, names you remove are deleted, same contract as editing the real file.
 *
 * Secrets never appear here: HTTP servers reference a credentialId, and a stdio env value can be
 * `credential:<id>` — both resolved only when a run's config is written. Pasting a literal token
 * in `headers` earns a warning pointing at Resources → Credentials instead.
 */
export function McpJsonEditor({ onSaved }: { onSaved: () => void }) {
  const [open, setOpen] = useState(false)
  const [text, setText] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    try {
      setText(JSON.stringify(await api.mcpDefsJson(), null, 2))
      setNote(null)
    } catch (e) {
      setNote(errMessage(e))
    }
  }

  const toggle = () => {
    const next = !open
    setOpen(next)
    if (next) void load()
  }

  const save = async () => {
    if (text === null) return
    let doc: unknown
    try {
      doc = JSON.parse(text)
    } catch (e) {
      setNote(`Not valid JSON: ${errMessage(e)}`)
      return
    }
    setBusy(true)
    setNote(null)
    try {
      const result = await api.saveMcpDefsJson(doc)
      setNote(
        `Saved ${result.saved} server(s)` +
          (result.deleted > 0 ? `, removed ${result.deleted}` : '') +
          (result.warnings.length > 0 ? `. ${result.warnings.join(' ')}` : '.'),
      )
      onSaved()
      await load() // re-read: the canonical form the backend kept is the honest content
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.catalog}>
      <button
        className={styles.collapseHead}
        onClick={toggle}
        aria-expanded={open}
        title="The whole registry in mcp.json's shape. Paste a server's README snippet — URL or command/args/env — and Save. Saving replaces the registry: names you remove are deleted. Tokens belong in Resources → Credentials (credentialId, or env value credential:<id>), never pasted here."
      >
        <span className={styles.collapseArrow}>{open ? '▾' : '▸'}</span>
        Edit as JSON (mcp.json)
      </button>

      {open && (
        <>
          <textarea
            className={styles.jsonEditor}
            aria-label="MCP registry JSON"
            rows={14}
            spellCheck={false}
            value={text ?? 'Loading…'}
            onChange={(e) => setText(e.target.value)}
          />
          <div className={styles.crudActions}>
            <button className={styles.saveBtn} disabled={busy || text === null} onClick={() => void save()}>
              {busy ? 'Saving…' : 'Save registry'}
            </button>
            <button className={styles.newBtn} disabled={busy} onClick={() => void load()}>
              Reload
            </button>
          </div>
        </>
      )}
      {note && <p className={panels.hint}>{note}</p>}
    </div>
  )
}
