import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { DatabaseDef, SqlNodeData, SqlPreview } from '../api/types.ts'
import { Field, SelectField, TextArea } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: SqlNodeData
  set: (patch: Record<string, unknown>) => void
}

export function SqlInspector({ data, set }: Props) {
  const [preview, setPreview] = useState<SqlPreview | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [databases, setDatabases] = useState<DatabaseDef[]>([])

  useEffect(() => {
    api
      .listDatabases()
      .then(setDatabases)
      .catch(() => setDatabases([]))
  }, [])

  const useDatabase = (id: string) => {
    const db = databases.find((d) => d.id === id)
    if (!db) return
    set({ jdbcUrl: db.jdbcUrl, username: db.username, passwordEnv: db.passwordEnv })
  }

  const runPreview = async () => {
    setLoading(true)
    setErr(null)
    setPreview(null)
    try {
      const r = await api.ragPreview({
        label: data.label,
        jdbcUrl: data.jdbcUrl,
        username: data.username,
        passwordEnv: data.passwordEnv,
        query: data.query,
        maxRows: data.maxRows,
      })
      setPreview(r)
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      {databases.length > 0 && (
        <SelectField
          label="Use database (from Resources)"
          value=""
          onChange={useDatabase}
          className={styles.libraryField}
        >
          <option value="">— choose a connection —</option>
          {databases.map((d) => (
            <option key={d.id} value={d.id}>
              {d.label}
            </option>
          ))}
        </SelectField>
      )}

      <Field label="Label" value={data.label} onChange={(v) => set({ label: v })} />
      <Field
        label="JDBC URL"
        value={data.jdbcUrl}
        placeholder="jdbc:postgresql://host:5432/db"
        onChange={(v) => set({ jdbcUrl: v })}
      />
      <Field label="Username" value={data.username} onChange={(v) => set({ username: v })} />
      <Field
        label="Password env var"
        value={data.passwordEnv}
        placeholder="PGPASSWORD"
        onChange={(v) => set({ passwordEnv: v })}
      />
      <TextArea label="SQL query" rows={5} value={data.query} onChange={(v) => set({ query: v })} />
      <Field
        label="Max rows"
        type="number"
        value={data.maxRows}
        onChange={(v) => set({ maxRows: Number(v) })}
      />

      <button className={styles.previewBtn} onClick={() => void runPreview()} disabled={loading}>
        {loading ? 'Running…' : '▷ Preview query'}
      </button>

      {err && <div className={styles.previewErr}>{err}</div>}

      {preview && (
        <>
          <div className={styles.previewMeta}>
            {preview.rowCount} row(s){preview.truncated ? ' (truncated)' : ''}
          </div>
          <div className={styles.previewTable}>
            <table>
              <thead>
                <tr>
                  {preview.columns.map((c, i) => (
                    <th key={i}>{c}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {preview.rows.map((row, ri) => (
                  <tr key={ri}>
                    {row.map((cell, ci) => (
                      <td key={ci}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  )
}
