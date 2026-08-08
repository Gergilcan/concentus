import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { StorageConfig } from '../api/types.ts'
import { Field, SelectField } from './fields.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

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

  const draft = { mode, url, username, password }

  const onTest = async () => {
    setTesting(true)
    setTest(null)
    try {
      setTest(await api.testStorage(draft))
    } catch (e) {
      setTest({ ok: false, detail: e instanceof Error ? e.message : String(e) })
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
      setStatus(
        saved.restartRequired
          ? 'Saved. Restart Concentus to start using it.'
          : 'Saved.',
      )
    } catch (e) {
      pushError(e instanceof Error ? e.message : String(e))
    }
  }

  if (!config) return <div className={styles.muted}>Loading…</div>

  const pendingRestart = config.activeMode !== mode || config.activeMode !== config.mode

  return (
    <div className={styles.crudForm} style={{ maxWidth: '46rem' }}>
      <SelectField
        label="Where Concentus stores its data"
        value={mode}
        onChange={(v) => setMode(v === 'external' ? 'external' : 'embedded')}
      >
        <option value="embedded">Embedded — ships with the app, nothing to install</option>
        <option value="external">External PostgreSQL — your own server</option>
      </SelectField>

      {mode === 'embedded' ? (
        <p className={panels.hint}>
          A real PostgreSQL that starts and stops with the app, in your app-data folder. Right for
          one person on one machine: no server to run, no credentials to manage, and it is backed
          up by whatever backs up that folder — which for most laptops is nothing, so this is the
          trade-off to be aware of.
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
          <p className={panels.hint}>
            For a team: shared between installs, backed up and audited like any other database.
            PostgreSQL only — the schema uses <code>jsonb</code> and partial indexes. Concentus
            creates its own tables on first connection, so an empty database is all it needs.
            <br />
            <b>Nothing is copied over.</b> Switching does not move your existing runs, credentials
            or flow history to the new database; it starts empty. Flows, agents and MCP definitions
            are files rather than rows, so those come with you either way.
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
        <p className={panels.hint} style={{ color: test.ok ? 'var(--ok, #4ade80)' : 'var(--danger, #f3b6b6)' }}>
          {test.ok ? '✓ ' : '✗ '}
          {test.detail}
        </p>
      )}

      {pendingRestart && (
        <p className={panels.hint}>
          <b>Restart required.</b> This app is running on the <b>{config.activeMode}</b> database.
          The setting is read when Concentus starts, because every part of the app opens its tables
          against one connection — swapping it underneath a running process would leave half of it
          talking to the old database.
        </p>
      )}
    </div>
  )
}
