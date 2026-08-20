import { useEffect, useState } from 'react'
import { errMessage } from '../utils/errMessage.ts'
import { api } from '../api/client.ts'
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
      setStatus(saved.restartRequired ? 'Saved. Restart Concentus to start using it.' : 'Saved.')
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  if (!config) return <Spinner />

  const pendingRestart = config.activeMode !== mode || config.activeMode !== config.mode

  return (
    <div className={`${styles.crudForm} ${styles.lone}`}>
      <h3 className={styles.h4}>Application storage</h3>
      <SelectField
        label="Where Concentus stores its data"
        value={mode}
        onChange={(v) => setMode(v === 'external' ? 'external' : 'embedded')}
      >
        <option value="embedded">Embedded — ships with the app, nothing to install</option>
        <option value="external">External PostgreSQL — your own server</option>
      </SelectField>

      {mode === 'embedded' ? (
        <p
          className={panels.hint}
          title="A real PostgreSQL in your app-data folder, started and stopped with the app. No server, no credentials — but only as backed up as that folder is."
        >
          Ships with the app; nothing to install. ⓘ
        </p>
      ) : (
        <>
          <Field
            label="JDBC URL"
            value={url}
            placeholder="jdbc:postgresql://db.internal:5432/concentus"
            onChange={setUrl}
          />
          <Field label="Username" value={username} onChange={setUsername} />
          <Field
            label="Password"
            type="password"
            value={password ?? ''}
            placeholder={config.hasPassword ? '•••••••• (unchanged)' : ''}
            onChange={setPassword}
          />
          <p
            className={panels.hint}
            title="PostgreSQL only (the schema uses jsonb). An empty database is all it needs — tables are created on first connection. Switching alone copies nothing over; use 'Move my data' below to bring across what you already have."
          >
            For teams: shared, backed up, audited. Switching alone starts empty — move your data
            below. ⓘ
          </p>
        </>
      )}

      <div className={styles.crudActions}>
        <button className={styles.saveBtn} onClick={() => void onSave()}>
          Save
        </button>
        {mode === 'external' && (
          <button className={styles.newBtn} onClick={() => void onTest()} disabled={testing}>
            {testing ? 'Testing…' : 'Test connection'}
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
          title="The setting is read at startup: every store opens its tables against one connection, so it cannot be swapped under a running app."
        >
          <b>Restart required</b> — currently running on the <b>{config.activeMode}</b> database. ⓘ
        </p>
      )}

      <MigrateSection draft={draft} />
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
 */
function MigrateSection({ draft }: { draft: StorageDraft }) {
  const [contents, setContents] = useState<TableCount[] | null>(null)
  const [skip, setSkip] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [report, setReport] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  const look = async () => {
    setReport(null)
    setFailed(false)
    try {
      const c = await api.storageContents()
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
      const r = await api.migrateStorage({ ...draft, mode: 'external', skip: [...skip] })
      const moved = r.copied.filter((t) => t.rows > 0)
      setReport(
        (r.totalRows === 0
          ? 'Nothing new to copy — the target already holds everything.'
          : `Copied ${r.totalRows} rows: ${moved.map((t) => `${t.rows} ${t.table}`).join(', ')}.`) +
          (r.warnings.length ? ` ${r.warnings.join(' ')}` : '') +
          ' Nothing here changed; switch above and restart when you are ready.',
      )
    } catch (e) {
      setFailed(true)
      // Half a copy is a normal outcome of a dropped connection, and repeating it is the fix: the
      // rows already across are not sent again.
      setReport(`${errMessage(e)} Nothing was deleted; press Move again to continue.`)
    } finally {
      setBusy(false)
    }
  }

  const ready = draft.mode === 'external' && draft.url.trim() !== ''
  const total = (contents ?? []).reduce((n, t) => (skip.has(t.table) ? n : n + t.rows), 0)

  return (
    <div className={styles.subSection}>
      <h3
        className={styles.h4}
        title="Copies every flow, agent, MCP server, credential, knowledge base and run into the PostgreSQL configured above, over the connection this app already has — no pg_dump, nothing to install. Rows already there are left exactly as they are: nothing is deleted or overwritten, so a copy that stops halfway is simply repeated. It does not switch — the app keeps running on its current database until you save the setting above and restart."
      >
        Move my data to that database ⓘ
      </h3>
      <div className={styles.crudActions}>
        <button className={styles.newBtn} disabled={busy} onClick={() => void look()}>
          {contents ? 'Refresh what is here' : 'See what would move'}
        </button>
        <button
          className={styles.saveBtn}
          disabled={busy || !ready}
          onClick={() => void move()}
          title={
            ready
              ? 'Copies into the database configured above. Adds only; never deletes.'
              : 'Fill in the external PostgreSQL above first — that is where the data goes.'
          }
        >
          {busy ? 'Moving…' : 'Move it now'}
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
          {total.toLocaleString()} rows selected. Unticking run history or knowledge chunks moves
          your configuration now and leaves the bulk for later — the copy can be repeated.
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
 * DOCUMENTS (huge, re-upload cleanly), run history, and above all SECRETS — credentials export
 * as metadata only and import as placeholders under the SAME ids, so every reference keeps
 * working and each value is re-entered once.
 */
function BackupSection() {
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [reenter, setReenter] = useState<string[]>([])

  const exportAll = async () => {
    setBusy(true)
    setNote(null)
    try {
      const blob = await api.exportBackup()
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = `concentus-export-${new Date().toISOString().slice(0, 10)}.json`
      a.click()
      URL.revokeObjectURL(a.href)
      setNote('Exported. Credentials travel as names only — their values never leave this machine.')
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
        (parts.length ? `Imported ${parts.join(', ')}.` : 'Nothing new to import.') +
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
        title="Everything as one .json: flows, agents, MCP servers, facades, databases, knowledge bases, skills and variables — importable on another machine with every cross-reference intact. Secrets never travel: credentials arrive as placeholders under their original ids, and you re-enter each value once. Knowledge documents and run history stay out."
      >
        Backup — export / import everything ⓘ
      </h3>
      <div className={styles.crudActions}>
        <button className={styles.newBtn} disabled={busy} onClick={() => void exportAll()}>
          Export everything (.json)
        </button>
        <label className={styles.newBtn} aria-disabled={busy}>
          Import from file…
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
          <b>Re-enter these credential values</b> under Resources → Credentials:{' '}
          {reenter.join(', ')}.
        </p>
      )}
    </div>
  )
}
