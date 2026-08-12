import { useEffect, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
import { api } from '../api/client.ts'
import type { McpCapabilities, McpServerInfo } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import styles from './panels.module.scss'

interface Props {
  name: string
  url: string
  credentialId?: string
  authHeader?: string
}

/**
 * Registration and status for one MCP server **in the Claude Code CLI's own list**.
 *
 * Only the Claude backends use this: the CLI holds its own MCP registrations and its own
 * authorizations. It is not how the self-hosted-model backend reaches a server — that one connects
 * directly and uses either a stored token or the grant Concentus obtained itself, which is what
 * `McpOAuthConnect` above handles.
 *
 * The two sit next to each other and used to contradict one another: this panel would report that
 * an OAuth sign-in "cannot complete here", which was true of the CLI's flow and false of the app's.
 */
export function McpClaudeActions({ name, url, credentialId, authHeader }: Props) {
  const [servers, setServers] = useState<McpServerInfo[]>([])
  const [caps, setCaps] = useState<McpCapabilities | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = () => {
    api
      .listMcpServers()
      .then(setServers)
      .catch(() => setServers([]))
  }
  useEffect(load, [])

  // Whether an OAuth sign-in can complete here at all. False in a container, where there is no
  // terminal and no browser — offering the button anyway produces a sign-in that appears to start
  // and silently never finishes.
  useEffect(() => {
    let alive = true
    void api
      .mcpCapabilities()
      .then((c) => alive && setCaps(c))
      .catch(() => alive && setCaps({ interactiveLogin: false, hint: "" }))
    return () => {
      alive = false
    }
  }, [])

  const current = servers.find((s) => s.name.toLowerCase() === name.trim().toLowerCase())
  const listed = !!current
  const s = (current?.status ?? '').toLowerCase()
  const failed = listed && s.includes('fail')
  const needsAuth = listed && s.includes('auth')
  const connected = listed && !failed && !needsAuth
  const canAct = name.trim().length > 0 && url.trim().length > 0

  const add = async () => {
    setBusy(true)
    setStatus(null)
    try {
      const r = await api.addMcpServer({ name, url, credentialId, authHeader })
      setStatus(r.status)
      load()
    } catch (e) {
      setStatus(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const authorize = async () => {
    setBusy(true)
    setStatus('Starting sign-in…')
    try {
      if (!listed) await api.addMcpServer({ name, url, credentialId, authHeader })
      const r = await api.loginMcpServer(name)
      setStatus(r.status)
      load()
    } catch (e) {
      setStatus(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const removeSrv = async () => {
    setBusy(true)
    setStatus(null)
    try {
      const r = await api.removeMcpServer(name)
      setStatus(r.status)
      load()
    } catch (e) {
      setStatus(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const dotClass = connected ? styles.sOk : failed ? styles.sErr : listed ? styles.sWarn : ''
  const statusText = connected
    ? 'Connected in Claude Code'
    : failed
      ? 'In Claude Code — failed to connect'
      : needsAuth
        ? 'In Claude Code — needs authorization'
        : listed
          ? 'In Claude Code'
          : 'Not yet in Claude Code'

  return (
    <div className={styles.mcpActions}>
      <div
        className={styles.mcpStatus}
        title="The claude CLI's own registration, used by runs on the Claude backend. The CLI keeps its authorizations to itself — the tool picker and self-hosted runs use 'Sign in to this server' above instead."
      >
        <span className={cx(styles.sDot, dotClass)} />
        {statusText}
      </div>

      <div className={styles.mcpBtns}>
        {/* The OAuth button only exists where a sign-in can actually complete. In a container it
            would start something that never finishes, so the token route becomes the primary
            action instead of a fallback. */}
        {!connected && caps?.interactiveLogin && (
          <button className={styles.previewBtn} onClick={() => void authorize()} disabled={busy || !canAct}>
            {busy ? 'Working…' : listed ? 'Authorize (OAuth)' : 'Add & authorize'}
          </button>
        )}
        {!listed && (
          <button
            className={caps?.interactiveLogin ? styles.linkBtn : styles.previewBtn}
            onClick={() => void add()}
            disabled={busy || !canAct}
          >
            {caps?.interactiveLogin ? 'Add without sign-in' : busy ? 'Working…' : 'Add with token'}
          </button>
        )}
        {listed && (
          <button className={styles.linkBtn} onClick={() => void removeSrv()} disabled={busy}>
            Remove
          </button>
        )}
        <button className={styles.linkBtn} onClick={load} disabled={busy}>
          Recheck
        </button>
      </div>

      {caps && !caps.interactiveLogin && (
        <div className={styles.previewMeta}>
          {caps.hint ||
            'The claude CLI’s sign-in needs a desktop — use “Sign in to this server” above instead.'}
        </div>
      )}
      {failed && (
        <div className={styles.previewMeta}>
          Store a token under <b>Resources → Credentials</b>, select it above, and use “Add with
          token”.
        </div>
      )}
      {status && <div className={styles.previewMeta}>{status}</div>}
    </div>
  )
}
