import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { errMessage } from '../utils/errMessage.ts'
import { api } from '../api/client.ts'
import type { DatabaseDef, SqlNodeData, SqlPreview } from '../api/types.ts'
import { CredentialField } from './CredentialField.tsx'
import { Field, FineTuning, SelectField, TextArea } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: SqlNodeData
  set: (patch: Record<string, unknown>) => void
}

export function SqlInspector({ data, set }: Props) {
  const { t } = useTranslation()
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
    set({ jdbcUrl: db.jdbcUrl, username: db.username, credentialId: db.credentialId })
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
        credentialId: data.credentialId,
        query: data.query,
        maxRows: data.maxRows,
      })
      setPreview(r)
    } catch (e) {
      setErr(errMessage(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      {databases.length > 0 && (
        <SelectField
          label={t('Use database (from Resources)')}
          value=""
          onChange={useDatabase}
          className={styles.libraryField}
        >
          <option value="">{t('— choose a connection —')}</option>
          {databases.map((d) => (
            <option key={d.id} value={d.id}>
              {d.label}
            </option>
          ))}
        </SelectField>
      )}

      <Field label={t('Label')} value={data.label} onChange={(v) => set({ label: v })} />
      <Field
        label={t('JDBC URL')}
        value={data.jdbcUrl}
        placeholder={t('jdbc:postgresql://host:5432/db')}
        onChange={(v) => set({ jdbcUrl: v })}
      />
      <Field label={t('Username')} value={data.username} onChange={(v) => set({ username: v })} />
      <CredentialField
        label={t('Password')}
        value={data.credentialId}
        onChange={(v) => set({ credentialId: v })}
        what={t('this database')}
      />
      <TextArea label={t('SQL query')} rows={5} value={data.query} onChange={(v) => set({ query: v })} />
      <FineTuning>
        <Field
          label={t('Max rows')}
          type="number"
          value={data.maxRows}
          onChange={(v) => set({ maxRows: Number(v) })}
        />
      </FineTuning>

      <button className={styles.previewBtn} onClick={() => void runPreview()} disabled={loading}>
        {loading ? t('Running…') : t('▷ Preview query')}
      </button>

      {err && <div className={styles.previewErr}>{err}</div>}

      {preview && (
        <>
          <div className={styles.previewMeta}>
            {t('{{n}} row(s)', { n: preview.rowCount })}{preview.truncated ? ` ${t('(truncated)')}` : ''}
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
