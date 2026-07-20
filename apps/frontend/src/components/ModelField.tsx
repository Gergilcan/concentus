import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.ts'
import { MODEL_GROUPS } from '../constants.ts'
import { Field } from './fields.tsx'
import styles from './panels.module.scss'

const CUSTOM = '__custom__'

/**
 * Model picker for an agent node.
 *
 * <p>One field rather than a separate provider selector: the backend routes by model id, so a
 * provider dropdown would be a second source of truth that could contradict the model.
 *
 * <p>The list is a shortcut, not a whitelist — anything not in it (a new release, a local Ollama
 * model) is still typeable, which is why "Custom…" exists and why an unrecognised saved value
 * opens in custom mode rather than being silently reset.
 */
export function ModelField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [configured, setConfigured] = useState<string[] | null>(null)

  useEffect(() => {
    let alive = true
    // Wrapped rather than just .catch()'d: this is a nice-to-have hint, and it must not be able
    // to take the whole inspector down if the endpoint is missing or the call throws outright.
    try {
      void api
        .listProviders()
        .then((r) => alive && setConfigured(r.configured))
        .catch(() => alive && setConfigured([]))
    } catch {
      setConfigured([])
    }
    return () => {
      alive = false
    }
  }, [])

  const known = useMemo(() => MODEL_GROUPS.flatMap((g) => g.models), [])
  const isKnown = known.includes(value)
  const [custom, setCustom] = useState(!isKnown && value !== '')

  const group = MODEL_GROUPS.find((g) => g.models.includes(value))
  // Claude has no provider entry — it runs on its own backends, not a provider credential.
  const needsKey =
    group?.providerId != null && configured != null && !configured.includes(group.providerId)

  return (
    <>
      <label className={styles.field}>
        <span>Model</span>
        <select
          value={custom ? CUSTOM : value}
          onChange={(e) => {
            if (e.target.value === CUSTOM) {
              setCustom(true)
              return
            }
            setCustom(false)
            onChange(e.target.value)
          }}
        >
          {MODEL_GROUPS.map((g) => (
            <optgroup key={g.label} label={g.label}>
              {g.models.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </optgroup>
          ))}
          <option value={CUSTOM}>Custom / local model…</option>
        </select>
      </label>

      {custom && (
        <Field
          label="Model id"
          value={value}
          placeholder="e.g. gpt-oss:20b, llama-3.3-70b, qwen2.5"
          onChange={onChange}
        />
      )}

      {group && <p className={styles.hint}>{group.hint}</p>}

      {needsKey && (
        <p className={styles.hint}>
          <b>No credential configured for this provider</b>, so a run using it will fail at launch.
          Set its API key and restart the backend.
        </p>
      )}

      {custom && (
        <p className={styles.hint}>
          Unlisted models route by id prefix. For a local server (Ollama, vLLM) point{' '}
          <code>LLM_OPENAI_COMPATIBLE</code> at it and map the prefix with{' '}
          <code>LLM_MODEL_PREFIXES</code> — no API key needed for localhost.
        </p>
      )}
    </>
  )
}
