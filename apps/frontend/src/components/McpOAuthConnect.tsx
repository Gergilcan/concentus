import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import { errMessage } from '../utils/errMessage.ts'
import { beginMcpSignIn } from './McpSignIn.ts'
import styles from './panels.module.scss'

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
