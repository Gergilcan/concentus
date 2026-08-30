import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type {
  Credential,
  CredentialStatus,
  OAuthCredentialConfig,
  OAuthCredentialStatus,
} from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Field, SelectField } from './fields.tsx'
import { GroupChip } from './GroupChip.tsx'
import { VisibleTo } from './VisibleTo.tsx'
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
 * Credentials entered in the app, sealed before storage when the installation has a key.
 *
 * The value field is **write-only**: nothing here ever displays a stored secret, because the API
 * has none to give — a credential comes back as a label, a kind and a masked hint. Editing shows
 * an empty value box, and leaving it empty keeps the stored secret untouched. That last part is
 * what stops "rename it and save" from overwriting the password with a mask.
 *
 * A **locked** credential is one sealed under a key this installation does not have. It is listed
 * under its name with a badge, and editing it asks for the value again — which is the whole
 * repair: the id stays, so every node pointing at it keeps working. This screen never calls that
 * "not configured", because that is how the same row used to present and it sent people to
 * create duplicates.
 */
export function CredentialsPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
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

  // Whatever secret the form held goes whenever the selection changes. Empty on purpose: there is
  // nothing to prefill it with, and an empty box is an honest representation of "the app cannot
  // read this".
  const clearSecrets = () => {
    setValue('')
    setOauth(BLANK_OAUTH)
    setOauthStatus(null)
  }

  const startCreate = () => {
    setCreating(true)
    setEditing(null)
    setLabel('')
    setKind(KINDS[0].value)
    clearSecrets()
  }

  const startEdit = (c: Credential) => {
    setEditing(c)
    setCreating(false)
    setLabel(c.label)
    setKind(c.kind)
    clearSecrets()
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
        pushError(started.error ?? t('The sign-in could not be started.'))
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
    clearSecrets()
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
    if (!confirm(t('Delete the credential "{{label}}"? Any flow using it will stop working.', { label: c.label })))
      return
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
          <b>{t('Credential storage is unavailable.')}</b> {status.hint}
        </p>
      </div>
    )
  }

  const editingLocked = editing?.locked === true

  return (
    <div className={styles.crud}>
      <div className={styles.crudList}>
        <div className={styles.crudListHead}>
          <span>{t('Credentials')}</span>
          <button className={styles.newBtn} onClick={startCreate}>{t('+ New')}</button>
        </div>
        {items.length === 0 && <p className={styles.muted}>{t('None yet.')}</p>}
        {items.map((c) => (
          <div
            key={c.id}
            className={editing?.id === c.id ? `${styles.crudItem} ${styles.active}` : styles.crudItem}
            onClick={() => startEdit(c)}
          >
            {/* Grouped: the row is a flex line, so two loose children would sit side by side
                instead of stacking — the label and its metadata each squeezed into half a column. */}
            <div className={styles.crudItemStack}>
              <div>
                {c.label} <GroupChip groupId={c.groupId} />
              </div>
              <div className={styles.muted}>
                {c.kind} · {c.hint ?? '••••'}
                {c.lastUsedAt
                  ? ` · ${t('used {{date}}', { date: new Date(c.lastUsedAt).toLocaleDateString() })}`
                  : ` · ${t('never used')}`}
              </div>
              {c.locked && (
                <div className={styles.muted} style={{ color: 'var(--warn, #fbbf24)' }}>
                  {t('Locked — enter the value again')}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      <div className={styles.crudForm}>
        {!creating && !editing ? (
          <p className={styles.muted}>{t('Select a credential, or create one.')}</p>
        ) : (
          <form onSubmit={submit}>
            <Field label={t('Label')} value={label} onChange={setLabel} placeholder={t('Buzón presupuestos')} />

            {creating && (
              <SelectField label={t('Kind')} value={kind} onChange={setKind}>
                {KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {t(k.label)}
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
                  label={t('Authorization URL')}
                  value={oauth.authorizationUrl}
                  onChange={setOauthField('authorizationUrl')}
                  placeholder="https://accounts.google.com/o/oauth2/v2/auth"
                />
                <Field
                  label={t('Token URL')}
                  value={oauth.tokenUrl}
                  onChange={setOauthField('tokenUrl')}
                  placeholder="https://oauth2.googleapis.com/token"
                />
                <Field
                  label={t('Client ID')}
                  value={oauth.clientId}
                  onChange={setOauthField('clientId')}
                  placeholder="123.apps.googleusercontent.com"
                />
                <Field
                  label={editing ? t('Client secret (leave blank to keep the current one)') : t('Client secret')}
                  value={oauth.clientSecret}
                  onChange={setOauthField('clientSecret')}
                  type="password"
                />
                <Field
                  label={t('Scopes')}
                  value={oauth.scope}
                  onChange={setOauthField('scope')}
                  placeholder="https://www.googleapis.com/auth/adwords"
                />
                <Field
                  label={t('Extra authorization parameters')}
                  value={oauth.authParams}
                  onChange={setOauthField('authParams')}
                  placeholder="access_type=offline&prompt=consent"
                />

                {oauthStatus && (
                  <div className={styles.hint}>
                    <p>
                      {t("Register this address as the redirect URI in the provider's console:")}{' '}
                      <code>{oauthStatus.redirectUri}</code>
                    </p>
                    <p>
                      {oauthStatus.connected
                        ? oauthStatus.hasRefreshToken
                          ? t('Connected.')
                          : t(
                              'Connected, but the provider returned no refresh token — this stops working when the access token expires. Add access_type=offline&prompt=consent and connect again.',
                            )
                        : t('Not connected yet.')}
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
                      {oauthStatus?.connected ? t('Connect again') : t('Connect')}
                    </button>
                  </div>
                )}

                <p className={styles.hint}>
                  {t(
                    'The sign-in opens in your browser and comes back to this app, which keeps the tokens encrypted and refreshes them on its own. A node reads one piece at a time:',
                  )}{' '}
                  <code>credential:&lt;id&gt;:refresh_token</code>, <code>:client_id</code>,{' '}
                  <code>:client_secret</code>, {t('or')} <code>credential:&lt;id&gt;</code>{' '}
                  {t('for a live access token.')}
                </p>
              </>
            ) : (
              <label className={styles.field}>
                <span>
                  {editingLocked
                    ? t('New value (locked — enter it again)')
                    : editing
                      ? t('New value (leave blank to keep the current one)')
                      : t('Value')}
                </span>
                <input
                  type="password"
                  autoComplete="new-password"
                  value={value}
                  placeholder={editing && !editingLocked ? '••••••••' : ''}
                  onChange={(e) => setValue(e.target.value)}
                />
              </label>
            )}

            {editingLocked && (
              <p className={styles.hint} style={{ color: 'var(--warn, #fbbf24)' }}>
                {t(
                  "This value is locked: it was encrypted with a key this installation does not have. Enter it again and it is stored under this installation's key; every node that points at it keeps working.",
                )}
              </p>
            )}
            {status?.encrypted ? (
              <>
                <p className={styles.hint}>
                  {t(
                    'Encrypted with AES-256-GCM before it is written, under a key kept outside the database, and never sent back — not to this screen, not to any API, not to an administrator. To change it, type a new one.',
                  )}
                </p>
                <p className={styles.hint}>
                  {t(
                    'This protects a leaked database backup or a database-only compromise. It does not protect against someone who compromises the server itself, since the key has to be readable here to be usable.',
                  )}
                </p>
              </>
            ) : (
              <p className={styles.hint}>
                {t(
                  'Stored as typed — this installation has no CONCENTUS_SECRET_KEY, so anyone who can read the database can read it. Never sent back to this screen or any API. To change it, type a new one.',
                )}
              </p>
            )}

            {editing && (
              <VisibleTo
                kind="credential"
                resourceId={editing.id}
                groupId={editing.groupId}
                pushError={pushError}
                onAssigned={(groupId) => {
                  setEditing({ ...editing, groupId })
                  setItems((prev) => prev.map((c) => (c.id === editing.id ? { ...c, groupId } : c)))
                }}
              />
            )}

            <div className={styles.crudActions}>
              <button
                type="submit"
                className={styles.saveBtn}
                disabled={busy || !label || (!isOAuth && !value && (creating || editingLocked))}
              >
                {busy ? t('Saving…') : t('Save')}
              </button>
              <button type="button" className={styles.newBtn} onClick={cancel}>
                {t('Cancel')}
              </button>
              {editing && (
                <button type="button" className={styles.delBtn} onClick={() => void remove(editing)}>
                  {t('Delete')}
                </button>
              )}
            </div>
          </form>
        )}
      </div>
    </div>
  )
}
