import { useEffect, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
import { api } from '../api/client.ts'
import type { McpCapabilities, McpServerInfo } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import styles from './panels.module.scss'

interface Props {
  name: string
  /**
   * Optional because a stdio definition has none: the backend stores null for a server that is a
   * command. Reading it as a string here is what made opening one of those (Google Ads, say) throw
   * on `url.trim()` and take the whole panel down with it.
   */
  url?: string
  credentialId?: string
  authHeader?: string
}

/**
 * The CLI registration as ONE value rather than four overlapping booleans. The dot and the label
 * are two views of the same state, so they read it from the same place and cannot drift apart.
 * The four states are exclusive by construction — a listed server is exactly one of the last
 * three, which is why "listed but otherwise fine" is spelled `connected` and has no fourth label.
 */
type ClaudeState = 'absent' | 'needsAuth' | 'failed' | 'connected'

function claudeState(server: McpServerInfo | undefined): ClaudeState {
  if (!server) return 'absent'
  const status = (server.status ?? '').toLowerCase()
  if (status.includes('fail')) return 'failed'
  if (status.includes('auth')) return 'needsAuth'
  return 'connected'
}

const STATE_LABEL: Record<ClaudeState, string> = {
  connected: 'Connected in Claude Code',
  failed: 'In Claude Code — failed to connect',
  needsAuth: 'In Claude Code — needs authorization',
  absent: 'Not yet in Claude Code',
}

const STATE_DOT: Record<ClaudeState, string> = {
  connected: styles.sOk,
  failed: styles.sErr,
  needsAuth: styles.sWarn,
  absent: '',
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
      .catch(() => alive && setCaps({ interactiveLogin: false, hint: '' }))
    return () => {
      alive = false
    }
  }, [])

  const current = servers.find((s) => s.name.toLowerCase() === name.trim().toLowerCase())
  const state = claudeState(current)
  const listed = state !== 'absent'
  const failed = state === 'failed'
  const connected = state === 'connected'
  // A stdio definition has no URL, and the CLI registration this panel drives is a URL one. Its
  // status still means something — a server of that name may be in the CLI's list — but there is
  // nothing here to add, so the buttons are replaced by the reason rather than shown dead.
  const endpoint = (url ?? '').trim()
  const canAct = name.trim().length > 0 && endpoint.length > 0

  // Every action here is the same shape: block the buttons, run one CLI call, show whatever it
  // says, and re-read the list only if it succeeded. The three used to be written out separately.
  const act = async (run: () => Promise<{ status: string }>, pending?: string) => {
    setBusy(true)
    setStatus(pending ?? null)
    try {
      const r = await run()
      setStatus(r.status)
      load()
    } catch (e) {
      setStatus(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const add = () => act(() => api.addMcpServer({ name, url: endpoint, credentialId, authHeader }))

  const authorize = () =>
    act(async () => {
      if (!listed) await api.addMcpServer({ name, url: endpoint, credentialId, authHeader })
      return api.loginMcpServer(name)
    }, 'Starting sign-in…')

  const removeSrv = () => act(() => api.removeMcpServer(name))

  return (
    <div className={styles.mcpActions}>
      <div
        className={styles.mcpStatus}
        title="The claude CLI's own registration, used by runs on the Claude backend. The CLI keeps its authorizations to itself — the tool picker and self-hosted runs use 'Sign in to this server' above instead."
      >
        <span className={cx(styles.sDot, STATE_DOT[state])} />
        {STATE_LABEL[state]}
      </div>

      {!endpoint && (
        <div className={styles.previewMeta}>
          This server is a local command, not a URL — runs launch it themselves, so there is nothing
          to register with the CLI here.
        </div>
      )}

      <div className={styles.mcpBtns}>
        {/* The OAuth button only exists where a sign-in can actually complete. In a container it
            would start something that never finishes, so the token route becomes the primary
            action instead of a fallback. */}
        {endpoint !== '' && !connected && caps?.interactiveLogin && (
          <button className={styles.previewBtn} onClick={() => void authorize()} disabled={busy || !canAct}>
            {busy ? 'Working…' : listed ? 'Authorize (OAuth)' : 'Add & authorize'}
          </button>
        )}
        {endpoint !== '' && !listed && (
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
