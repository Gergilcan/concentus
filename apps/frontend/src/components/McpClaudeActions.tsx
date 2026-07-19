import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { McpServerInfo } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import styles from './panels.module.scss'

interface Props {
  name: string
  url: string
  tokenEnv?: string
}

/**
 * Live Claude Code registration + OAuth status for one MCP server, with one-click
 * "Add & authorize" — no terminal needed. Used by the MCP node inspector and the
 * Resources → MCP Servers panel.
 */
export function McpClaudeActions({ name, url, tokenEnv }: Props) {
  const [servers, setServers] = useState<McpServerInfo[]>([])
  const [status, setStatus] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = () => {
    api
      .listMcpServers()
      .then(setServers)
      .catch(() => setServers([]))
  }
  useEffect(load, [])

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
      const r = await api.addMcpServer({ name, url, tokenEnv })
      setStatus(r.status)
      load()
    } catch (e) {
      setStatus(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const authorize = async () => {
    setBusy(true)
    setStatus('Starting sign-in…')
    try {
      if (!listed) await api.addMcpServer({ name, url, tokenEnv })
      const r = await api.loginMcpServer(name)
      setStatus(r.status)
      load()
    } catch (e) {
      setStatus(e instanceof Error ? e.message : String(e))
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
      setStatus(e instanceof Error ? e.message : String(e))
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
      <div className={styles.mcpStatus}>
        <span className={cx(styles.sDot, dotClass)} />
        {statusText}
      </div>

      <div className={styles.mcpBtns}>
        {!connected && (
          <button className={styles.previewBtn} onClick={() => void authorize()} disabled={busy || !canAct}>
            {busy ? 'Working…' : listed ? 'Authorize (OAuth)' : 'Add & authorize'}
          </button>
        )}
        {!listed && (
          <button className={styles.linkBtn} onClick={() => void add()} disabled={busy || !canAct}>
            Add without sign-in
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

      {failed && (
        <div className={styles.previewMeta}>
          Some servers (e.g. GitHub) don't support OAuth sign-in — they need a token instead. Set a
          Token env var above and use “Add without sign-in”.
        </div>
      )}
      {status && <div className={styles.previewMeta}>{status}</div>}
    </div>
  )
}
