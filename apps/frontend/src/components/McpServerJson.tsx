import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { McpDef } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { formatServer, parseServerJson } from './mcpJson.ts'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

/**
 * The selected server's own JSON, inside the form that configures it.
 *
 * The fields above cover a remote server completely; a local one is a command, its arguments and
 * its environment, and those have no fields. This is where they are edited — and where a README's
 * "add this to your mcp.json" snippet is pasted, which is how a server outside the catalogue gets
 * added at all.
 *
 * It follows the form while it is untouched, so picking another server in the list reloads it. Once
 * edited it stops following: whoever is typing here is the one deciding, and a keystroke in the
 * Name field above must not silently rewrite what they wrote.
 */
export function McpServerJson({ def, onApplied }: { def: McpDef; onApplied: (saved: McpDef) => void }) {
  const { t } = useTranslation()
  const [text, setText] = useState(() => formatServer(def))
  const [dirty, setDirty] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // The draft is a new object on every keystroke in the form, so the effect below runs constantly.
  // A ref keeps the current definition reachable without making it a dependency of anything.
  const current = useRef(def)
  current.current = def

  // Another server picked in the list: this box belongs to that one now — its text, and none of
  // the last one's messages. "Saved" left hanging over a different server is a lie about it.
  useEffect(() => {
    setText(formatServer(current.current))
    setDirty(false)
    setNote(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [def.id])

  useEffect(() => {
    if (dirty) return
    setText(formatServer(def))
  }, [def, dirty])

  const reset = () => {
    setText(formatServer(current.current))
    setDirty(false)
    setNote(null)
  }

  const save = async () => {
    setNote(null)
    let parsed
    try {
      parsed = parseServerJson(text, current.current)
    } catch (e) {
      setNote(errMessage(e))
      return
    }
    setBusy(true)
    try {
      const saved = await api.saveMcpDef(parsed.def)
      onApplied(saved)
      setText(formatServer(saved))
      setDirty(false)
      setNote(parsed.warnings.length > 0 ? `${t('Saved.')} ${parsed.warnings.join(' ')}` : t('Saved'))
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.serverJson}>
      <label className={styles.field}>
        <span
          title={t(
            "This one server in mcp.json's own shape — a URL, or the command/args/env of a server launched here. Paste a README's snippet straight in; its mcpServers wrapper is accepted and its key renames the server. Tokens belong in Resources → Credentials: reference them as credentialId, or as an env value credential:<id>.",
          )}
        >
          {t('This server as JSON')} ⓘ
        </span>
        <textarea
          className={styles.jsonEditor}
          aria-label={t('This server as JSON')}
          rows={10}
          spellCheck={false}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            setDirty(true)
          }}
        />
      </label>
      <div className={styles.crudActions}>
        <button className={styles.saveBtn} disabled={busy} onClick={() => void save()}>
          {busy ? t('Saving…') : t('Save JSON')}
        </button>
        <button className={styles.newBtn} disabled={busy || !dirty} onClick={reset}>
          {t('Revert')}
        </button>
        {note && <span className={panels.hint}>{note}</span>}
      </div>
    </div>
  )
}
