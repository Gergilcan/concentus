import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { errMessage } from '../utils/errMessage.ts'
import { api } from '../api/client.ts'
import { usePermissions } from '../state/permissions.tsx'
import type { StorageConfig, StorageDraft } from '../api/types.ts'
import { Field, SelectField } from './fields.tsx'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/** One table and how much of it there is. */
type TableCount = { table: string; rows: number }

/**
 * Where Concentus keeps its own data.
 *
 * Deliberately not in the "Databases" tab: that one lists databases an *agent* reads as context,
 * and putting the application's own storage beside them would invite someone to point a flow at it
 * or, worse, to change it thinking they were adding a data source.
 */
export function StoragePanel({ pushError }: { pushError: (message: string) => void }) {
  const { t } = useTranslation()
  const [config, setConfig] = useState<StorageConfig | null>(null)
  const [mode, setMode] = useState<'embedded' | 'external'>('embedded')
  const [url, setUrl] = useState('')
  const [username, setUsername] = useState('')
  // Null means "unchanged" all the way to the backend, which is what lets an existing connection be
  // edited without the password ever being sent to the browser.
  const [password, setPassword] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)
  const [test, setTest] = useState<{ ok: boolean; detail: string } | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  useEffect(() => {
    api
      .getStorage()
      .then((c) => {
        setConfig(c)
        setMode(c.mode === 'external' ? 'external' : 'embedded')
        setUrl(c.url)
        setUsername(c.username)
      })
      .catch((e) => pushError(String(e)))
  }, [pushError])

  const draft: StorageDraft = { mode, url, username, password }

  const onTest = async () => {
    setTesting(true)
    setTest(null)
    try {
      setTest(await api.testStorage(draft))
    } catch (e) {
      setTest({ ok: false, detail: errMessage(e) })
    } finally {
      setTesting(false)
    }
  }

  const onSave = async () => {
    setStatus(null)
    try {
      const saved = await api.saveStorage(draft)
      setConfig(saved)
      setPassword(null)
      setStatus(saved.restartRequired ? t('Saved. Restart Concentus to start using it.') : t('Saved.'))
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  if (!config) return <Spinner />

  const pendingRestart = config.activeMode !== mode || config.activeMode !== config.mode

  return (
    <div className={`${styles.crudForm} ${styles.lone}`}>
      <h3 className={styles.h4}>{t('Application storage')}</h3>
      <SelectField
        label={t('Where Concentus stores its data')}
        value={mode}
        onChange={(v) => setMode(v === 'external' ? 'external' : 'embedded')}
      >
        <option value="embedded">{t('Embedded — ships with the app, nothing to install')}</option>
        <option value="external">{t('External PostgreSQL — your own server')}</option>
      </SelectField>

      {mode === 'embedded' ? (
        <p
          className={panels.hint}
          title={t(
            'A real PostgreSQL in your app-data folder, started and stopped with the app. No server, no credentials — but only as backed up as that folder is.',
          )}
        >
          {t('Ships with the app; nothing to install.')} ⓘ
        </p>
      ) : (
        <>
          <Field
            label={t('JDBC URL')}
            value={url}
            placeholder="jdbc:postgresql://db.internal:5432/concentus"
            onChange={setUrl}
          />
          <Field label={t('Username')} value={username} onChange={setUsername} />
          <Field
            label={t('Password')}
            type="password"
            value={password ?? ''}
            placeholder={config.hasPassword ? t('•••••••• (unchanged)') : ''}
            onChange={setPassword}
          />
          <p
            className={panels.hint}
            title={t(
              "PostgreSQL only (the schema uses jsonb). An empty database is all it needs — tables are created on first connection. Switching alone copies nothing over; use 'Move my data' below to bring across what you already have.",
            )}
          >
            {t('For teams: shared, backed up, audited. Switching alone starts empty — move your data below.')} ⓘ
          </p>
        </>
      )}

      <div className={styles.crudActions}>
        <button className={styles.saveBtn} onClick={() => void onSave()}>
          {t('Save')}
        </button>
        {mode === 'external' && (
          <button className={styles.newBtn} onClick={() => void onTest()} disabled={testing}>
            {testing ? t('Testing…') : t('Test connection')}
          </button>
        )}
        {status && <span className={styles.status}>{status}</span>}
      </div>

      {test && (
        <p
          className={panels.hint}
          style={{ color: test.ok ? 'var(--ok, #4ade80)' : 'var(--danger, #f3b6b6)' }}
        >
          {test.ok ? '✓ ' : '✗ '}
          {test.detail}
        </p>
      )}

      {pendingRestart && (
        <p
          className={panels.hint}
          title={t(
            'The setting is read at startup: every store opens its tables against one connection, so it cannot be swapped under a running app.',
          )}
        >
          <b>{t('Restart required')}</b> —{' '}
          {t('currently running on the {{mode}} database.', { mode: config.activeMode })} ⓘ
        </p>
      )}

      <MigrateSection draft={draft} activeMode={config.activeMode} />
      <BackupSection />
    </div>
  )
}

/**
 * Bringing an installation's data to another PostgreSQL.
 *
 * Separate from the setting above, and on purpose: copying adds rows and changes nothing here,
 * while switching costs a restart. Kept apart, somebody can copy today, look around the company
 * database at leisure, and switch when they trust it — then repeat the copy for whatever they
 * built in between, because nothing is ever deleted or overwritten.
 *
 * Which way round is not a question anybody should have to answer: it follows from where the app
 * is running. Still on the embedded database, the copy goes out to the server configured above.
 * Already switched — and staring at an empty screen, which is how most people arrive here — it
 * comes the other way, out of the data directory this installation left behind.
 */
function MigrateSection({ draft, activeMode }: { draft: StorageDraft; activeMode: string }) {
  const { t } = useTranslation()
  const pulling = activeMode === 'external'
  const [contents, setContents] = useState<TableCount[] | null>(null)
  const [skip, setSkip] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [report, setReport] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  const look = async () => {
    setReport(null)
    setFailed(false)
    try {
      const c = await api.storageContents(pulling ? 'embedded' : 'active')
      setContents(c.tables)
    } catch (e) {
      setFailed(true)
      setReport(errMessage(e))
    }
  }

  const move = async () => {
    setBusy(true)
    setReport(null)
    setFailed(false)
    try {
      const r = await api.migrateStorage({
        ...draft,
        mode: 'external',
        from: pulling ? 'embedded' : 'active',
        skip: [...skip],
      })
      const moved = r.copied.filter((c) => c.rows > 0)
      setReport(
        (r.totalRows === 0
          ? t('Nothing new to copy — the target already holds everything.')
          : t('Copied {{count}} rows: {{list}}.', {
              count: r.totalRows,
              list: moved.map((c) => `${c.rows} ${c.table}`).join(', '),
            })) +
          (r.warnings.length ? ` ${r.warnings.join(' ')}` : '') +
          ' ' +
          (pulling
            ? t('Reload the page to see it.')
            : t('Nothing here changed; switch above and restart when you are ready.')),
      )
    } catch (e) {
      setFailed(true)
      // Half a copy is a normal outcome of a dropped connection, and repeating it is the fix: the
      // rows already across are not sent again.
      setReport(`${errMessage(e)} ${t('Nothing was deleted; press Move again to continue.')}`)
    } finally {
      setBusy(false)
    }
  }

  // Pulling needs no target typed in: the destination is the database this app already runs on.
  const ready = pulling || (draft.mode === 'external' && draft.url.trim() !== '')
  const total = (contents ?? []).reduce((n, t) => (skip.has(t.table) ? n : n + t.rows), 0)

  return (
    <div className={styles.subSection}>
      <h3
        className={styles.h4}
        title={
          pulling
            ? t(
                'Opens the embedded database this installation left behind and copies every flow, agent, MCP server, credential, knowledge base and run into the PostgreSQL you are running on now. Rows already here are left exactly as they are — nothing is deleted or overwritten, so a copy that stops halfway is simply repeated.',
              )
            : t(
                'Copies every flow, agent, MCP server, credential, knowledge base and run into the PostgreSQL configured above, over the connection this app already has — no pg_dump, nothing to install. Rows already there are left exactly as they are: nothing is deleted or overwritten, so a copy that stops halfway is simply repeated. It does not switch — the app keeps running on its current database until you save the setting above and restart.',
              )
        }
      >
        {pulling ? t('Bring my old data here') : t('Move my data to that database')} ⓘ
      </h3>
      <div className={styles.crudActions}>
        <button className={styles.newBtn} disabled={busy} onClick={() => void look()}>
          {contents ? t('Refresh the list') : pulling ? t('See what is still there') : t('See what would move')}
        </button>
        <button
          className={styles.saveBtn}
          disabled={busy || !ready}
          onClick={() => void move()}
          title={
            ready
              ? t('Adds only; never deletes. Safe to press twice.')
              : t('Fill in the external PostgreSQL above first — that is where the data goes.')
          }
        >
          {busy ? t('Moving…') : pulling ? t('Bring it across') : t('Move it now')}
        </button>
      </div>

      {contents && (
        <table className={styles.migrateTable}>
          <tbody>
            {contents
              .filter((t) => t.rows > 0)
              .map((t) => (
                <tr key={t.table}>
                  <td>
                    <label>
                      <input
                        type="checkbox"
                        checked={!skip.has(t.table)}
                        onChange={(e) => {
                          const next = new Set(skip)
                          if (e.target.checked) next.delete(t.table)
                          else next.add(t.table)
                          setSkip(next)
                        }}
                      />{' '}
                      {t.table}
                    </label>
                  </td>
                  <td style={{ textAlign: 'right' }}>{t.rows.toLocaleString()}</td>
                </tr>
              ))}
          </tbody>
        </table>
      )}
      {contents && (
        <p className={panels.hint}>
          {pulling
            ? t('{{count}} rows selected from the embedded database.', { count: total.toLocaleString() })
            : t('{{count}} rows selected.', { count: total.toLocaleString() })}{' '}
          {t(
            'Unticking run history or knowledge chunks moves your configuration now and leaves the bulk for later — the copy can be repeated.',
          )}
        </p>
      )}
      {report && (
        <p
          className={panels.hint}
          style={{ color: failed ? 'var(--danger, #f3b6b6)' : 'var(--ok, #4ade80)' }}
        >
          {report}
        </p>
      )}
    </div>
  )
}

/**
 * The whole configuration as one file, and back.
 *
 * What travels: flows (with variables), agents, MCP servers, facade profiles, database
 * connections, knowledge base definitions, skills, org variables. What doesn't: knowledge
 * DOCUMENTS (huge, re-upload cleanly), run history, and — unless an administrator ticks the box —
 * SECRETS. By default credentials export as metadata only and import as placeholders under the
 * SAME ids, so every reference keeps working and each value is re-entered once. With the box
 * ticked the file carries every value this installation can open, in the clear: that is the
 * recovery path for a lost key, and it is said in the note, the file name and the file itself.
 */
function BackupSection() {
  const { t } = useTranslation()
  const { canAdminister } = usePermissions()
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [reenter, setReenter] = useState<string[]>([])
  const [withSecrets, setWithSecrets] = useState(false)

  const exportAll = async () => {
    setBusy(true)
    setNote(null)
    const includeSecrets = canAdminister && withSecrets
    try {
      const blob = await api.exportBackup(includeSecrets)
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = `concentus-export-${includeSecrets ? 'with-secrets-' : ''}${new Date().toISOString().slice(0, 10)}.json`
      a.click()
      URL.revokeObjectURL(a.href)
      setNote(
        includeSecrets
          ? t('Exported with credential values in the clear — keep the file like the passwords it holds. Locked credentials travelled as names only.')
          : t('Exported. Credentials travel as names only — their values never leave this machine.'),
      )
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const importAll = async (file: File) => {
    setBusy(true)
    setNote(null)
    setReenter([])
    try {
      const report = await api.importBackup(JSON.parse(await file.text()))
      const parts = Object.entries(report.imported)
        .filter(([, n]) => n > 0)
        .map(([k, n]) => `${n} ${k}`)
      setNote(
        (parts.length ? t('Imported {{list}}.', { list: parts.join(', ') }) : t('Nothing new to import.')) +
          (report.warnings.length ? ` ${report.warnings.join(' ')}` : ''),
      )
      setReenter(report.credentialsToReenter)
    } catch (e) {
      setNote(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    // Not another .crudForm: this sits INSIDE the storage panel, which already provides the
    // gutter, and a second one indented the whole block away from the settings above it.
    <div className={styles.subSection}>
      <h3
        className={styles.h4}
        title={t(
          'Everything as one .json: flows, agents, MCP servers, facades, databases, knowledge bases, skills and variables — importable on another machine with every cross-reference intact. Unless you tick the box, secrets never travel: credentials arrive as placeholders under their original ids, and you re-enter each value once. Knowledge documents and run history stay out.',
        )}
      >
        {t('Backup — export / import everything')} ⓘ
      </h3>
      {canAdminister && (
        <label
          className={panels.hint}
          style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-start', cursor: 'pointer' }}
          title={t(
            'Off, the file carries credential names only and is safe to pass around. On, it carries every value this installation can open, in the clear — for restoring after a lost key or moving to a new machine. Keep that file like the passwords it holds.',
          )}
        >
          <input
            type="checkbox"
            checked={withSecrets}
            disabled={busy}
            onChange={(e) => setWithSecrets(e.target.checked)}
          />
          <span>{t('Include credential values')} ⓘ</span>
        </label>
      )}
      <div className={styles.crudActions}>
        <button className={styles.newBtn} disabled={busy} onClick={() => void exportAll()}>
          {t('Export everything (.json)')}
        </button>
        <label className={styles.newBtn} aria-disabled={busy}>
          {t('Import from file…')}
          <input
            type="file"
            hidden
            accept="application/json,.json"
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) void importAll(f)
              e.target.value = ''
            }}
          />
        </label>
      </div>
      {note && <p className={panels.hint}>{note}</p>}
      {reenter.length > 0 && (
        <p className={panels.hint}>
          <b>{t('Re-enter these credential values')}</b>{' '}
          {t('under Resources → Credentials: {{list}}.', { list: reenter.join(', ') })}
        </p>
      )}
    </div>
  )
}
