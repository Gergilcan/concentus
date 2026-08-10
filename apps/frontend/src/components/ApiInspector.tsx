import { useState } from 'react'
import { api } from '../api/client.ts'
import type { ApiNodeData, ApiOperationView } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Field, TextArea } from './fields.tsx'
import styles from './panels.module.scss'

interface Props {
  data: ApiNodeData
  set: (patch: Record<string, unknown>) => void
}

/**
 * The API node: an OpenAPI spec in, an explicit per-operation allowlist out.
 *
 * Reads can be allowed in one click; anything that writes is a deliberate individual tick. The
 * distinction is drawn from the spec's own methods, and the allowlist is what the run's tool
 * server enforces — an operation never ticked simply does not exist as far as the agent knows.
 */
export function ApiInspector({ data, set }: Props) {
  const [ops, setOps] = useState<ApiOperationView[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      const preview = await api.previewApiSpec(data.specUrl, data.specInline)
      setOps(preview.operations)
      // The spec's own base URL is a sensible default the user can still override.
      if (!data.baseUrl && preview.baseUrl) set({ baseUrl: preview.baseUrl })
    } catch (e) {
      setError(errMessage(e))
      setOps(null)
    } finally {
      setLoading(false)
    }
  }

  const toggle = (key: string) => {
    const has = data.ops.includes(key)
    set({ ops: has ? data.ops.filter((k) => k !== key) : [...data.ops, key] })
  }

  const allowAllReads = () => {
    if (!ops) return
    const reads = ops.filter((o) => !o.write).map((o) => o.key)
    set({ ops: [...new Set([...data.ops, ...reads])] })
  }

  return (
    <>
      <Field label="Label" value={data.label} onChange={(v) => set({ label: v })} />
      <Field
        label={
          <span title="URL of the API's OpenAPI 3.x document (JSON or YAML). If it is not fetchable, paste the document below instead.">
            OpenAPI spec URL ⓘ
          </span>
        }
        placeholder="https://api.example.com/openapi.json"
        value={data.specUrl}
        onChange={(v) => set({ specUrl: v })}
      />
      <TextArea
        label="…or paste the spec"
        rows={3}
        placeholder='{"openapi": "3.0.0", …}'
        value={data.specInline ?? ''}
        onChange={(v) => set({ specInline: v })}
      />
      <Field
        label={
          <span title="Overrides the spec's own servers[0].url — for sandboxes or self-hosted instances.">
            Base URL (optional) ⓘ
          </span>
        }
        placeholder="filled from the spec after loading"
        value={data.baseUrl ?? ''}
        onChange={(v) => set({ baseUrl: v })}
      />
      <Field
        label={
          <span title="Credential id from Resources → Credentials. Sent as Authorization: Bearer unless a different header is named below. The agent never sees the token.">
            Credential id ⓘ
          </span>
        }
        placeholder="from Resources → Credentials"
        value={data.credentialId ?? ''}
        onChange={(v) => set({ credentialId: v })}
      />
      <Field
        label="Send token in (blank = Authorization: Bearer)"
        placeholder="X-Api-Key"
        value={data.authHeader ?? ''}
        onChange={(v) => set({ authHeader: v })}
      />

      <div className={styles.mcpBtns}>
        <button className={styles.previewBtn} onClick={() => void load()} disabled={loading}>
          {loading ? 'Loading…' : 'Load operations'}
        </button>
        {ops && (
          <button className={styles.linkBtn} onClick={allowAllReads}>
            Allow all reads
          </button>
        )}
      </div>
      {error && <p className={styles.hint}>{error}</p>}

      {ops && (
        <div className={styles.opList}>
          {ops.map((o) => (
            <label key={o.key} className={styles.opRow} title={o.description || o.key}>
              <input
                type="checkbox"
                checked={data.ops.includes(o.key)}
                onChange={() => toggle(o.key)}
              />
              <code className={o.write ? styles.opWrite : styles.opRead}>{o.method}</code>
              <span className={styles.opPath}>{o.path}</span>
            </label>
          ))}
        </div>
      )}
      {!ops && data.ops.length > 0 && (
        <p className={styles.hint}>
          {data.ops.length} operation(s) currently allowed. Load the spec to review them.
        </p>
      )}
    </>
  )
}
