import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { McpToolInfo, ToolSearchStatus } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { beginMcpSignIn } from './McpOAuthConnect.tsx'
import { Modal } from './Modal.tsx'
import modal from './flows.module.scss'
import styles from './panels.module.scss'

interface Props {
  url: string
  credentialId?: string
  selected: string[]
  onChange: (tools: string[]) => void
}

/** `list_contacts` → `list`. What a tool does to, grouped by what it does it to. */
function familyOf(name: string): string {
  const parts = name.split('_')
  return parts.length > 1 ? parts[0] : 'other'
}

/**
 * Picks which of a server's tools an agent gets, from the server's own list.
 *
 * <p>A modal rather than an inline list, because the inspector is a narrow column and this is a
 * task: 338 tools need room, a search box, and somewhere to see the running selection without
 * losing your place. Typing names is not an alternative — a name that is nearly right selects
 * nothing, and the run then behaves as though the tool simply is not there.
 *
 * <p>Grouped by verb (`create_`, `list_`, `delete_`…) because that is how the decision is actually
 * made: a read-only agent wants every `list_` and `get_` and none of the `delete_`.
 */
export function McpToolPicker({ url, credentialId, selected, onChange }: Props) {
  const [open, setOpen] = useState(false)
  // Whether tool search will rank semantically. Its two halves — a Postgres extension and an
  // embedding model on the inference server — are configured in different places, and when either
  // is missing the app quietly does something worse. Saying so here is the only way to notice.
  const [toolSearch, setToolSearch] = useState<ToolSearchStatus | null>(null)

  useEffect(() => {
    let alive = true
    void api
      .listModels()
      .then((c) => alive && setToolSearch(c.toolSearch ?? null))
      .catch(() => alive && setToolSearch(null))
    return () => {
      alive = false
    }
  }, [])

  return (
    <>
      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => setOpen(true)} disabled={!url.trim()}>
          Choose tools…
        </button>
        {selected.length > 0 && (
          <button className={styles.linkBtn} onClick={() => onChange([])}>
            Clear ({selected.length})
          </button>
        )}
      </div>

      <p className={styles.hint}>
        {selected.length === 0 ? (
          <span title="Every tool is a JSON schema in the prompt. Claude has the room; a self-hosted model's context often doesn't — give each agent a short list.">
            <b>All tools</b> this server exposes go to the agent. ⓘ
          </span>
        ) : (
          <>
            <b>{selected.length} selected:</b> {selected.slice(0, 6).join(', ')}
            {selected.length > 6 && ` … and ${selected.length - 6} more`}
          </>
        )}
      </p>

      {toolSearch?.enabled && selected.length === 0 && (
        <p className={styles.hint}>
          <span title="The agent describes what it needs and gets back the matching schemas; every tool stays callable.">
            Above <b>{toolSearch.threshold}</b> tools the agent gets one search tool instead of
            every schema. ⓘ
          </span>{' '}
          {toolSearch.vectorReady && toolSearch.modelPresent ? (
            <span title={`Semantic ranking via ${toolSearch.embeddingModel}, served by the same model server as your chat model.`}>
              ✓ semantic
            </span>
          ) : (
            <>⚠ {toolSearch.detail}</>
          )}
        </p>
      )}

      {open && (
        <ToolDialog
          url={url}
          credentialId={credentialId}
          selected={selected}
          onChange={onChange}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}

function ToolDialog({
  url,
  credentialId,
  selected,
  onChange,
  onClose,
}: Props & { onClose: () => void }) {
  const [tools, setTools] = useState<McpToolInfo[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [needsAuth, setNeedsAuth] = useState(false)
  const [busy, setBusy] = useState(true)
  const [signing, setSigning] = useState(false)
  const [search, setSearch] = useState('')
  const [onlyChosen, setOnlyChosen] = useState(false)
  // Edited locally and committed on Done, so closing with Escape or the backdrop leaves the node
  // as it was — a half-built allowlist saved by accident is worse than losing two clicks.
  const [draft, setDraft] = useState<string[]>(selected)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  const load = useCallback(async () => {
    setBusy(true)
    setError(null)
    setNeedsAuth(false)
    try {
      const res = await api.listMcpTools(url.trim(), credentialId)
      if (!alive.current) return
      if (!res.ok) {
        setError(res.error ?? 'Could not read this server’s tools.')
        setNeedsAuth(res.needsAuth === true)
        return
      }
      setTools(res.tools ?? [])
    } catch (e) {
      if (alive.current) setError(errMessage(e))
    } finally {
      if (alive.current) setBusy(false)
    }
  }, [url, credentialId])

  useEffect(() => {
    void load()
  }, [load])

  // The dialog fixes its own 401: start the app's OAuth sign-in, then watch for the grant to
  // land and re-read the list — nobody should have to close this, sign in elsewhere, and reopen.
  const signIn = async () => {
    setSigning(true)
    setError(null)
    try {
      const failed = await beginMcpSignIn(url.trim())
      if (failed) {
        setError(failed)
        setSigning(false)
        return
      }
      const deadline = Date.now() + 120_000
      const poll = async () => {
        if (!alive.current) return
        try {
          const s = await api.mcpOAuthStatus(url.trim())
          if (s.connected) {
            setSigning(false)
            await load()
            return
          }
        } catch {
          /* transient; keep polling until the deadline */
        }
        if (Date.now() < deadline) setTimeout(() => void poll(), 2000)
        else if (alive.current) {
          setSigning(false)
          setError('The sign-in did not complete. Approve it in the tab that opened, then retry.')
          setNeedsAuth(true)
        }
      }
      setTimeout(() => void poll(), 2000)
    } catch (e) {
      setError(errMessage(e))
      setSigning(false)
    }
  }

  const shown = useMemo(() => {
    let list = tools ?? []
    if (onlyChosen) list = list.filter((t) => draft.includes(t.name))
    const q = search.trim().toLowerCase()
    if (!q) return list
    return list.filter(
      (t) => t.name.toLowerCase().includes(q) || (t.description ?? '').toLowerCase().includes(q),
    )
  }, [tools, search, onlyChosen, draft])

  // Grouped by verb: a read-only agent wants every `list_` and `get_`, and none of the `delete_`.
  const groups = useMemo(() => {
    const byFamily = new Map<string, McpToolInfo[]>()
    for (const t of shown) {
      const family = familyOf(t.name)
      if (!byFamily.has(family)) byFamily.set(family, [])
      byFamily.get(family)!.push(t)
    }
    return [...byFamily.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [shown])

  const toggle = (name: string) =>
    setDraft(draft.includes(name) ? draft.filter((n) => n !== name) : [...draft, name])

  const addAll = (names: string[]) => setDraft([...new Set([...draft, ...names])])
  const removeAll = (names: string[]) => {
    const drop = new Set(names)
    setDraft(draft.filter((n) => !drop.has(n)))
  }

  const shownNames = shown.map((t) => t.name)
  const allShownChosen = shownNames.length > 0 && shownNames.every((n) => draft.includes(n))

  return (
    <Modal wide title={`Tools for this server${tools ? ` — ${draft.length} of ${tools.length}` : ''}`} onClose={onClose}>
      {busy && <p className={modal.modalHint}>Reading the server’s tool list…</p>}
      {signing && <p className={modal.modalHint}>Waiting for the sign-in to complete…</p>}
      {error && !signing && (
        <p className={modal.modalHint}>
          <b>{error}</b>
        </p>
      )}
      {needsAuth && !signing && (
        <div className={styles.mcpBtns}>
          <button className={styles.previewBtn} onClick={() => void signIn()}>
            Sign in to this server
          </button>
          <button className={styles.linkBtn} onClick={() => void load()}>
            Retry
          </button>
        </div>
      )}

      {tools && (
        <>
          <label className={modal.field}>
            <span>Search</span>
            <input
              autoFocus
              value={search}
              placeholder="contact, invoice, tax…"
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>

          <label className={modal.toggleRow}>
            <input
              type="checkbox"
              checked={onlyChosen}
              onChange={(e) => setOnlyChosen(e.target.checked)}
            />
            <span>Show only the {draft.length} selected</span>
          </label>

          <div className={styles.mcpBtns}>
            <button
              className={styles.linkBtn}
              onClick={() => (allShownChosen ? removeAll(shownNames) : addAll(shownNames))}
              disabled={shownNames.length === 0}
            >
              {allShownChosen ? 'Deselect' : 'Select'} these {shownNames.length}
            </button>
            {draft.length > 0 && (
              <button className={styles.linkBtn} onClick={() => setDraft([])}>
                Clear all
              </button>
            )}
          </div>

          <div className={`${styles.repoList} ${modal.toolList}`}>
            {groups.map(([family, items]) => (
              <div key={family}>
                <button
                  className={styles.repoItem}
                  onClick={() =>
                    items.every((t) => draft.includes(t.name))
                      ? removeAll(items.map((t) => t.name))
                      : addAll(items.map((t) => t.name))
                  }
                >
                  <span>
                    <b>{family}_*</b>
                  </span>
                  <span className={styles.repoMeta}>
                    {items.filter((t) => draft.includes(t.name)).length}/{items.length}
                  </span>
                </button>
                {items.map((t) => (
                  <button key={t.name} className={styles.repoItem} onClick={() => toggle(t.name)}>
                    <span>
                      {draft.includes(t.name) ? '☑ ' : '☐ '}
                      {t.name}
                    </span>
                    <span className={styles.repoMeta} title={t.description}>
                      {t.description}
                    </span>
                  </button>
                ))}
              </div>
            ))}
            {shown.length === 0 && (
              <p className={modal.modalHint}>
                Nothing matches {search ? `“${search}”` : 'the current filter'}.
              </p>
            )}
          </div>

          <p className={modal.modalHint}>
            Leaving this empty sends <b>every</b> tool — on a self-hosted model that can overflow
            the context and truncate silently.
          </p>
        </>
      )}

      <div className={modal.modalActions}>
        <button className={styles.linkBtn} onClick={onClose}>
          Cancel
        </button>
        <button
          className={styles.previewBtn}
          onClick={() => {
            onChange(draft)
            onClose()
          }}
        >
          Use {draft.length === 0 ? 'all tools' : `these ${draft.length}`}
        </button>
      </div>
    </Modal>
  )
}
