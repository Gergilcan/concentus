import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import styles from './panels.module.scss'

const isLoopback = (host: string) =>
  host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1'

/**
 * Whether the browser will NOT be able to reach the sign-in's callback.
 *
 * The callback is handled by the BACKEND, not by this page, so origin equality is not the test —
 * a dev UI on localhost:5173 with the backend on 127.0.0.1:8734 works fine, and so does a
 * backend on its own api. host. The one setup that genuinely dies is a loopback callback used
 * from another machine: the provider sends the browser to ITS OWN localhost, where nothing
 * listens, after the code has been issued and spent. That is the only case flagged.
 */
export function callbackUnreachable(redirectUri: string | undefined, page: { origin: string; hostname: string }): boolean {
  if (!redirectUri || redirectUri.startsWith(page.origin)) return false
  try {
    return isLoopback(new URL(redirectUri).hostname) && !isLoopback(page.hostname)
  } catch {
    // A malformed redirect URI will fail loudly at the provider; guessing here adds nothing.
    return false
  }
}

/**
 * How a helper outside any component says a sentence in the user's language: the caller lends it
 * a translate function. The default speaks English, so non-component callers (and old tests)
 * still get the message verbatim.
 */
type Translate = (key: string, options?: Record<string, unknown>) => string

const plainTranslate: Translate = (key, options) =>
  key.replace(/\{\{(\w+)\}\}/g, (match, name) =>
    options && name in options ? String(options[name]) : match,
  )

/**
 * Starts the app's own OAuth sign-in for an MCP server and opens the approval tab.
 * Returns an error message, or null when the tab is open and approval is now up to the user.
 * Shared with the tool picker's dialog, which offers this sign-in when a server answers 401.
 */
export async function beginMcpSignIn(url: string, t: Translate = plainTranslate): Promise<string | null> {
  const started = await api.startMcpOAuth(url)
  if (!started.ok || !started.authorizationUrl) {
    return started.error ?? t('This server could not be signed in to.')
  }
  if (callbackUnreachable(started.redirectUri, window.location)) {
    return t(
      "This sign-in would return to {{redirectUri}}, which only exists on the backend's own machine — you are using Concentus from {{origin}}. Set MCP_OAUTH_REDIRECT_BASE={{origin}} and restart the backend.",
      { redirectUri: started.redirectUri, origin: window.location.origin },
    )
  }
  // A new tab, not a redirect: the canvas has unsaved state.
  window.open(started.authorizationUrl, '_blank', 'noopener')
  return null
}

/**
 * Signs Concentus in to an MCP server that uses OAuth.
 *
 * Separate from the token field because they answer different questions. A token is something
 * you paste; this is a grant the server issues to *this application*. The distinction matters
 * most on the self-hosted-model backend: the `claude` CLI holds its own MCP authorizations, so a
 * server that works there returns 401 to everything else — which reads as a broken flow rather
 * than as a missing sign-in.
 *
 * `onStatus` reports the connection state upward: once a server is authorized, the inspector
 * hides the token fields entirely — a grant makes them dead weight, and a leftover token there
 * used to shadow the grant and 401.
 */
export function McpOAuthConnect({
  url,
  onStatus,
}: {
  url: string
  onStatus?: (connected: boolean | null) => void
}) {
  const { t } = useTranslation()
  const [connected, setConnected] = useState<boolean | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    onStatus?.(connected)
    // Reporting upward is a consequence of the state changing, not of the parent re-rendering.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected])

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
      setError(await beginMcpSignIn(trimmed, (key, options) => t(key, options ?? {})))
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
          {busy ? t('Opening…') : connected ? t('Re-authorize') : t('Sign in to this server')}
        </button>
        {connected && (
          <button className={styles.previewBtn} onClick={() => void disconnect()} disabled={busy}>
            {t('Disconnect')}
          </button>
        )}
        <button className={styles.linkBtn} onClick={refresh}>
          {t('Re-check')}
        </button>
      </div>

      <p className={styles.hint}>
        {connected === true ? (
          <span
            title={t(
              'Concentus holds its own encrypted grant for this server and renews it automatically. No token needed — every backend reaches it.',
            )}
          >
            <b>{t('Signed in with OAuth.')}</b> {t('No token needed.')} ⓘ
          </span>
        ) : (
          <span
            title={t(
              "Approve once in the tab that opens; the grant is stored encrypted and renews itself. The claude CLI's own sign-in is separate — without this grant, non-CLI backends get 401 from OAuth servers.",
            )}
          >
            {t('For servers that use')} <b>OAuth</b> {t('instead of a pasted token.')} ⓘ
          </span>
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
