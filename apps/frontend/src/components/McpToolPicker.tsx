import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
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
  const { t } = useTranslation()
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
          {t('Choose tools…')}
        </button>
        {selected.length > 0 && (
          <button className={styles.linkBtn} onClick={() => onChange([])}>
            {t('Clear ({{n}})', { n: selected.length })}
          </button>
        )}
      </div>

      <p className={styles.hint}>
        {selected.length === 0 ? (
          <span
            title={t(
              "Every tool is a JSON schema in the prompt. Claude has the room; a self-hosted model's context often doesn't — give each agent a short list.",
            )}
          >
            <b>{t('All tools')}</b> {t('this server exposes go to the agent.')} ⓘ
          </span>
        ) : (
          <>
            <b>{t('{{n}} selected:', { n: selected.length })}</b> {selected.slice(0, 6).join(', ')}
            {selected.length > 6 && ` … ${t('and {{n}} more', { n: selected.length - 6 })}`}
          </>
        )}
      </p>

      {toolSearch?.enabled && selected.length === 0 && (
        <p className={styles.hint}>
          <span
            title={t(
              'The agent describes what it needs and gets back the matching schemas; every tool stays callable.',
            )}
          >
            {t('Above')} <b>{toolSearch.threshold}</b>{' '}
            {t('tools the agent gets one search tool instead of every schema.')} ⓘ
          </span>{' '}
          {toolSearch.vectorReady && toolSearch.modelPresent ? (
            // The backend's own sentence, because it knows which embedder ranks — the built-in
            // model or the model server — and a fixed "served by your model server" was wrong
            // for the desktop case, where there is no server at all.
            <span title={toolSearch.detail}>{t('✓ semantic')}</span>
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
  const { t } = useTranslation()
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
        setError(res.error ?? t('Could not read this server’s tools.'))
        setNeedsAuth(res.needsAuth === true)
        return
      }
      setTools(res.tools ?? [])
    } catch (e) {
      if (alive.current) setError(errMessage(e))
    } finally {
      if (alive.current) setBusy(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      const failed = await beginMcpSignIn(url.trim(), (key, options) => t(key, options ?? {}))
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
          setError(t('The sign-in did not complete. Approve it in the tab that opened, then retry.'))
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
    <Modal
      wide
      title={`${t('Tools for this server')}${tools ? ` — ${t('{{n}} of {{total}}', { n: draft.length, total: tools.length })}` : ''}`}
      onClose={onClose}
    >
      {busy && <p className={modal.modalHint}>{t('Reading the server’s tool list…')}</p>}
      {signing && <p className={modal.modalHint}>{t('Waiting for the sign-in to complete…')}</p>}
      {error && !signing && (
        <p className={modal.modalHint}>
          <b>{error}</b>
        </p>
      )}
      {needsAuth && !signing && (
        <div className={styles.mcpBtns}>
          <button className={styles.previewBtn} onClick={() => void signIn()}>
            {t('Sign in to this server')}
          </button>
          <button className={styles.linkBtn} onClick={() => void load()}>
            {t('Retry')}
          </button>
        </div>
      )}

      {tools && (
        <>
          <label className={modal.field}>
            <span>{t('Search')}</span>
            <input
              autoFocus
              value={search}
              placeholder={t('contact, invoice, tax…')}
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>

          <label className={modal.toggleRow}>
            <input
              type="checkbox"
              checked={onlyChosen}
              onChange={(e) => setOnlyChosen(e.target.checked)}
            />
            <span>{t('Show only the {{n}} selected', { n: draft.length })}</span>
          </label>

          <div className={styles.mcpBtns}>
            <button
              className={styles.linkBtn}
              onClick={() => (allShownChosen ? removeAll(shownNames) : addAll(shownNames))}
              disabled={shownNames.length === 0}
            >
              {allShownChosen
                ? t('Deselect these {{n}}', { n: shownNames.length })
                : t('Select these {{n}}', { n: shownNames.length })}
            </button>
            {draft.length > 0 && (
              <button className={styles.linkBtn} onClick={() => setDraft([])}>
                {t('Clear all')}
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
                {items.map((tool) => (
                  <button key={tool.name} className={styles.repoItem} onClick={() => toggle(tool.name)}>
                    <span>
                      {draft.includes(tool.name) ? '☑ ' : '☐ '}
                      {tool.name}
                    </span>
                    <span className={styles.repoMeta} title={tool.description}>
                      {tool.description}
                    </span>
                  </button>
                ))}
              </div>
            ))}
            {shown.length === 0 && (
              <p className={modal.modalHint}>
                {search
                  ? t('Nothing matches “{{search}}”.', { search })
                  : t('Nothing matches the current filter.')}
              </p>
            )}
          </div>

          <p className={modal.modalHint}>
            {t('Leaving this empty sends')} <b>{t('every')}</b>{' '}
            {t('tool — on a self-hosted model that can overflow the context and truncate silently.')}
          </p>
        </>
      )}

      <div className={modal.modalActions}>
        <button className={styles.linkBtn} onClick={onClose}>
          {t('Cancel')}
        </button>
        <button
          className={styles.previewBtn}
          onClick={() => {
            onChange(draft)
            onClose()
          }}
        >
          {draft.length === 0 ? t('Use all tools') : t('Use these {{n}}', { n: draft.length })}
        </button>
      </div>
    </Modal>
  )
}
