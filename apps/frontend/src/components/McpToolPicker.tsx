import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.ts'
import type { McpToolInfo, ToolSearchStatus } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
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
          <>
            <b>All tools</b> this server has. Fine for a small server; on a large one every tool is
            a JSON schema in the prompt, and a self-hosted model’s context will not hold them.
          </>
        ) : (
          <>
            <b>{selected.length} selected:</b> {selected.slice(0, 6).join(', ')}
            {selected.length > 6 && ` … and ${selected.length - 6} more`}
          </>
        )}
      </p>

      {toolSearch?.enabled && selected.length === 0 && (
        <p className={styles.hint}>
          Above <b>{toolSearch.threshold}</b> tools the agent is given a single{' '}
          <code>search_…_tools</code> instead of every definition — it describes what it needs and
          gets back the matching schemas. Every tool stays callable.
          <br />
          {toolSearch.vectorReady && toolSearch.modelPresent ? (
            <>
              ✓ <b>Semantic ranking</b> via <code>{toolSearch.embeddingModel}</code>, served by the
              same model server as your chat model.
            </>
          ) : (
            <>
              ⚠ {toolSearch.detail}
            </>
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
  const [busy, setBusy] = useState(true)
  const [search, setSearch] = useState('')
  const [onlyChosen, setOnlyChosen] = useState(false)
  // Edited locally and committed on Done, so closing with Escape or the backdrop leaves the node
  // as it was — a half-built allowlist saved by accident is worse than losing two clicks.
  const [draft, setDraft] = useState<string[]>(selected)

  useEffect(() => {
    let alive = true
    void api
      .listMcpTools(url.trim(), credentialId)
      .then((res) => {
        if (!alive) return
        if (!res.ok) {
          setError(res.error ?? 'Could not read this server’s tools.')
          return
        }
        setTools(res.tools ?? [])
      })
      .catch((e) => alive && setError(errMessage(e)))
      .finally(() => alive && setBusy(false))
    return () => {
      alive = false
    }
  }, [url, credentialId])

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
      {error && (
        <p className={modal.modalHint}>
          <b>{error}</b>
        </p>
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
            Leaving this empty sends <b>every</b> tool. Each one is a JSON schema in the prompt, so
            on a self-hosted model that is what overflows the context — the server then truncates
            silently and the model reports having only the few that survived.
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
