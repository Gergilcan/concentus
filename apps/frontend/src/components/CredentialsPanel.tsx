import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client.ts'
import type {
  Credential,
  CredentialStatus,
  OAuthCredentialConfig,
  OAuthCredentialStatus,
} from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Field, SelectField } from './fields.tsx'
import styles from './resources.module.scss'

const KINDS = [
  { value: 'mail-password', label: 'Mailbox password (IMAP)' },
  { value: 'api-token', label: 'API token' },
  { value: 'oauth', label: 'OAuth sign-in (browser)' },
]

const OAUTH_KIND = 'oauth'

const BLANK_OAUTH: OAuthCredentialConfig = {
  label: '',
  authorizationUrl: '',
  tokenUrl: '',
  clientId: '',
  clientSecret: '',
  scope: '',
  authParams: '',
}

/**
 * Endpoints nobody should have to look up twice.
 *
 * `authParams` is the part that decides whether this credential still works tomorrow: Google
 * returns a refresh token only for `access_type=offline`, and only on the first consent unless
 * `prompt=consent` forces it. Getting that wrong produces a credential that signs in, works for an
 * hour, and then fails inside a 7am cron run.
 */
const PRESETS: { name: string; config: Partial<OAuthCredentialConfig> }[] = [
  {
    name: 'Google',
    config: {
      authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
      tokenUrl: 'https://oauth2.googleapis.com/token',
      authParams: 'access_type=offline&prompt=consent',
    },
  },
  {
    name: 'Microsoft',
    config: {
      authorizationUrl: 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize',
      tokenUrl: 'https://login.microsoftonline.com/common/oauth2/v2.0/token',
      authParams: '',
    },
  },
]

/**
 * Credentials entered in the app, encrypted before storage.
 *
 * The value field is **write-only**: nothing here ever displays a stored secret, because the API
 * has none to give — a credential comes back as a label, a kind and a masked hint. Editing shows
 * an empty value box, and leaving it empty keeps the stored secret untouched. That last part is
 * what stops "rename it and save" from overwriting the password with a mask.
 */
export function CredentialsPanel({ pushError }: { pushError: (m: string) => void }) {
  const [status, setStatus] = useState<CredentialStatus | null>(null)
  const [items, setItems] = useState<Credential[]>([])
  const [editing, setEditing] = useState<Credential | null>(null)
  const [creating, setCreating] = useState(false)
  const [label, setLabel] = useState('')
  const [kind, setKind] = useState(KINDS[0].value)
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [oauth, setOauth] = useState<OAuthCredentialConfig>(BLANK_OAUTH)
  const [oauthStatus, setOauthStatus] = useState<OAuthCredentialStatus | null>(null)

  const isOAuth = kind === OAUTH_KIND
  const setOauthField = (field: keyof OAuthCredentialConfig) => (v: string) =>
    setOauth((prev) => ({ ...prev, [field]: v }))

  const load = useCallback(async () => {
    try {
      setStatus(await api.credentialStatus())
      setItems(await api.listCredentials())
    } catch (e) {
      pushError(errMessage(e))
    }
  }, [pushError])

  useEffect(() => {
    void load()
  }, [load])

  const startCreate = () => {
    setCreating(true)
    setEditing(null)
    setLabel('')
    setKind(KINDS[0].value)
    setValue('')
    setOauth(BLANK_OAUTH)
    setOauthStatus(null)
  }

  const startEdit = (c: Credential) => {
    setEditing(c)
    setCreating(false)
    setLabel(c.label)
    setKind(c.kind)
    // Empty on purpose: there is nothing to prefill it with, and an empty box is an honest
    // representation of "the app cannot read this".
    setValue('')
    setOauth(BLANK_OAUTH)
    setOauthStatus(null)
    if (c.kind === OAUTH_KIND) void loadOauthStatus(c.id)
  }

  /**
   * The configuration half comes back on purpose — endpoints, client id, scopes are settings, not
   * secrets, and editing a credential you cannot see is guesswork. The client secret and the tokens
   * stay on the server, which is why the secret field shows blank and means "leave it alone".
   */
  const loadOauthStatus = async (id: string) => {
    try {
      const status = await api.oauthCredentialStatus(id)
      setOauthStatus(status)
      setOauth((prev) => ({
        ...prev,
        authorizationUrl: status.authorizationUrl ?? '',
        tokenUrl: status.tokenUrl ?? '',
        clientId: status.clientId ?? '',
        scope: status.scope ?? '',
        authParams: status.authParams ?? '',
      }))
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const connect = async (id: string) => {
    setBusy(true)
    try {
      const started = await api.startOAuthSignIn(id)
      if (!started.ok || !started.authorizationUrl) {
        pushError(started.error ?? 'The sign-in could not be started.')
        return
      }
      // The SYSTEM browser: the desktop shell turns this into shell.openExternal. Never an
      // embedded webview — Google answers those with disallowed_useragent, and RFC 8252 forbids
      // them for native apps because the host app could read what is typed into the login page.
      window.open(started.authorizationUrl, '_blank', 'noopener')
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const cancel = () => {
    setCreating(false)
    setEditing(null)
    setValue('')
    setOauth(BLANK_OAUTH)
    setOauthStatus(null)
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      if (isOAuth) {
        const config = { ...oauth, label }
        if (editing) await api.updateOAuthCredential(editing.id, config)
        else await api.createOAuthCredential(config)
      } else if (editing) {
        await api.updateCredential(editing.id, label, value)
      } else {
        await api.createCredential(label, kind, value)
      }
      cancel()
      await load()
    } catch (err) {
      pushError(errMessage(err))
    } finally {
      setBusy(false)
      setValue('')
    }
  }

  const remove = async (c: Credential) => {
    if (!confirm(`Delete the credential "${c.label}"? Any flow using it will stop working.`)) return
    try {
      await api.deleteCredential(c.id)
      await load()
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  if (status && !status.available) {
    return (
      <div className={styles.muted}>
        <p>
          <b>Credential storage is disabled.</b> {status.hint}
        </p>
        <p>
          Credentials are encrypted with AES-256-GCM before being written. Without a master key
          there is nowhere safe to put them, so nothing is stored rather than being kept in plain
          text.
        </p>
      </div>
    )
  }

  return (
    <div className={styles.crud}>
      <div className={styles.crudList}>
        <div className={styles.crudListHead}>
          <span>Credentials</span>
          <button className={styles.newBtn} onClick={startCreate}>+ New</button>
        </div>
        {items.length === 0 && <p className={styles.muted}>None yet.</p>}
        {items.map((c) => (
          <div
            key={c.id}
            className={editing?.id === c.id ? `${styles.crudItem} ${styles.active}` : styles.crudItem}
            onClick={() => startEdit(c)}
          >
            {/* Grouped: the row is a flex line, so two loose children would sit side by side
                instead of stacking — the label and its metadata each squeezed into half a column. */}
            <div className={styles.crudItemStack}>
              <div>{c.label}</div>
              <div className={styles.muted}>
                {c.kind} · {c.hint ?? '••••'}
                {c.lastUsedAt ? ` · used ${new Date(c.lastUsedAt).toLocaleDateString()}` : ' · never used'}
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className={styles.crudForm}>
        {!creating && !editing ? (
          <p className={styles.muted}>Select a credential, or create one.</p>
        ) : (
          <form onSubmit={submit}>
            <Field label="Label" value={label} onChange={setLabel} placeholder="Buzón presupuestos" />

            {creating && (
              <SelectField label="Kind" value={kind} onChange={setKind}>
                {KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </SelectField>
            )}

            {isOAuth ? (
              <>
                <div className={styles.crudActions}>
                  {PRESETS.map((preset) => (
                    <button
                      key={preset.name}
                      type="button"
                      className={styles.newBtn}
                      onClick={() => setOauth((prev) => ({ ...prev, ...preset.config }))}
                    >
                      {preset.name}
                    </button>
                  ))}
                </div>

                <Field
                  label="Authorization URL"
                  value={oauth.authorizationUrl}
                  onChange={setOauthField('authorizationUrl')}
                  placeholder="https://accounts.google.com/o/oauth2/v2/auth"
                />
                <Field
                  label="Token URL"
                  value={oauth.tokenUrl}
                  onChange={setOauthField('tokenUrl')}
                  placeholder="https://oauth2.googleapis.com/token"
                />
                <Field
                  label="Client ID"
                  value={oauth.clientId}
                  onChange={setOauthField('clientId')}
                  placeholder="123.apps.googleusercontent.com"
                />
                <Field
                  label={editing ? 'Client secret (leave blank to keep the current one)' : 'Client secret'}
                  value={oauth.clientSecret}
                  onChange={setOauthField('clientSecret')}
                  type="password"
                />
                <Field
                  label="Scopes"
                  value={oauth.scope}
                  onChange={setOauthField('scope')}
                  placeholder="https://www.googleapis.com/auth/adwords"
                />
                <Field
                  label="Extra authorization parameters"
                  value={oauth.authParams}
                  onChange={setOauthField('authParams')}
                  placeholder="access_type=offline&prompt=consent"
                />

                {oauthStatus && (
                  <div className={styles.hint}>
                    <p>
                      Register this address as the redirect URI in the provider's console:{' '}
                      <code>{oauthStatus.redirectUri}</code>
                    </p>
                    <p>
                      {oauthStatus.connected
                        ? oauthStatus.hasRefreshToken
                          ? 'Connected.'
                          : 'Connected, but the provider returned no refresh token — this stops working when the access token expires. Add access_type=offline&prompt=consent and connect again.'
                        : 'Not connected yet.'}
                    </p>
                  </div>
                )}

                {editing && (
                  <div className={styles.crudActions}>
                    <button
                      type="button"
                      className={styles.saveBtn}
                      disabled={busy}
                      onClick={() => void connect(editing.id)}
                    >
                      {oauthStatus?.connected ? 'Connect again' : 'Connect'}
                    </button>
                  </div>
                )}

                <p className={styles.hint}>
                  The sign-in opens in your browser and comes back to this app, which keeps the
                  tokens encrypted and refreshes them on its own. A node reads one piece at a time:{' '}
                  <code>credential:&lt;id&gt;:refresh_token</code>, <code>:client_id</code>,{' '}
                  <code>:client_secret</code>, or <code>credential:&lt;id&gt;</code> for a live
                  access token.
                </p>
              </>
            ) : (
              <label className={styles.field}>
                <span>{editing ? 'New value (leave blank to keep the current one)' : 'Value'}</span>
                <input
                  type="password"
                  autoComplete="new-password"
                  value={value}
                  placeholder={editing ? '••••••••' : ''}
                  onChange={(e) => setValue(e.target.value)}
                />
              </label>
            )}

            <p className={styles.hint}>
              Encrypted before it is written, and never sent back — not to this screen, not to any
              API, not to an administrator. To change it, type a new one.
            </p>
            <p className={styles.hint}>
              This protects a leaked database backup or a database-only compromise. It does not
              protect against someone who compromises the server itself, since the key has to be
              readable here to be usable.
            </p>

            <div className={styles.crudActions}>
              <button type="submit" className={styles.saveBtn} disabled={busy || !label || (creating && !isOAuth && !value)}>
                {busy ? 'Saving…' : 'Save'}
              </button>
              <button type="button" className={styles.newBtn} onClick={cancel}>
                Cancel
              </button>
              {editing && (
                <button type="button" className={styles.delBtn} onClick={() => void remove(editing)}>
                  Delete
                </button>
              )}
            </div>
          </form>
        )}
      </div>
    </div>
  )
}
