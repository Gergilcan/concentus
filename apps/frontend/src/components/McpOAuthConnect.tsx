import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import styles from './panels.module.scss'

/**
 * Signs Concentus in to an MCP server that uses OAuth.
 *
 * Separate from the token field above it because they answer different questions. A token is
 * something you paste; this is a grant the server issues to *this application*. The distinction
 * matters most on the self-hosted-model backend: the `claude` CLI holds its own MCP
 * authorizations, so a server that works there returns 401 to everything else — which reads as a
 * broken flow rather than as a missing sign-in.
 */
export function McpOAuthConnect({ url }: { url: string }) {
  const [connected, setConnected] = useState<boolean | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const trimmed = url.trim()

  const refresh = useCallback(() => {
    if (!trimmed) {
      setConnected(null)
      return
    }
    void api
      .mcpOAuthStatus(trimmed)
      .then((s) => setConnected(s.connected))
      .catch(() => setConnected(null))
  }, [trimmed])

  useEffect(refresh, [refresh])

  const connect = async () => {
    setBusy(true)
    setError(null)
    try {
      const started = await api.startMcpOAuth(trimmed)
      if (!started.ok || !started.authorizationUrl) {
        setError(started.error ?? 'This server could not be signed in to.')
        return
      }
      // The browser knows where it actually is; the backend only knows what it was configured
      // with. A mismatch is invisible until the very end, when the authorization server sends the
      // browser somewhere nothing is listening and the sign-in dies on ERR_CONNECTION_REFUSED —
      // after the code has been issued and spent. Caught here, before anything is registered.
      if (started.redirectUri && !started.redirectUri.startsWith(window.location.origin)) {
        setError(
          `This sign-in would return to ${started.redirectUri}, but you are using Concentus at ` +
            `${window.location.origin}. Nothing will be listening there when the browser comes ` +
            `back. Set MCP_OAUTH_REDIRECT_BASE=${window.location.origin} and restart the backend.`,
        )
        return
      }
      // A new tab, not a redirect: the canvas has unsaved state, and navigating away from it to
      // approve a tool server would lose the flow being edited.
      window.open(started.authorizationUrl, '_blank', 'noopener')
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const disconnect = async () => {
    setBusy(true)
    try {
      await api.disconnectMcpOAuth(trimmed)
      setConnected(false)
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!trimmed) return null

  return (
    <>
      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => void connect()} disabled={busy}>
          {busy ? 'Opening…' : connected ? 'Re-authorize' : 'Sign in to this server'}
        </button>
        {connected && (
          <button className={styles.previewBtn} onClick={() => void disconnect()} disabled={busy}>
            Disconnect
          </button>
        )}
        <button className={styles.linkBtn} onClick={refresh}>
          Re-check
        </button>
      </div>

      <p className={styles.hint}>
        {connected === true ? (
          <>
            <b>Authorized.</b> Concentus holds its own grant for this server, so agents on a
            self-hosted model can use its tools — not only the Claude backends.
          </>
        ) : (
          <>
            For a server that uses <b>OAuth</b> rather than a pasted token. Approve it once in the
            tab that opens; the grant is stored encrypted and renews itself. Without it a
            self-hosted model gets <code>401</code> from this server even though Claude reaches it
            fine — that sign-in belongs to the <code>claude</code> CLI, not to this app.
          </>
        )}
      </p>
      {error && (
        <p className={styles.hint}>
          <b>{error}</b>
        </p>
      )}
    </>
  )
}
