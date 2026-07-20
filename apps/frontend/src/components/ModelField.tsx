import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.ts'
import type { ModelRate, ProvidersResponse } from '../api/types.ts'
import { MODEL_GROUPS } from '../constants.ts'
import { Field } from './fields.tsx'
import styles from './panels.module.scss'

const CUSTOM = '__custom__'

/** "$5 / $25" — input then output, per million tokens. */
function rateLabel(rate: ModelRate): string {
  const fmt = (n: number) => (n < 1 ? `$${n}` : `$${Number.isInteger(n) ? n : n.toFixed(2)}`)
  return `${fmt(rate.input)} / ${fmt(rate.output)}`
}

/** Roughly what a modest turn costs, to make the per-million rates concrete. */
function exampleCost(rate: ModelRate): string {
  // 50k in / 2k out — a mid-sized agent turn with some context.
  const usd = (50_000 / 1_000_000) * rate.input + (2_000 / 1_000_000) * rate.output
  return usd < 0.01 ? '<$0.01' : `$${usd.toFixed(2)}`
}

/**
 * Model picker for an agent node.
 *
 * <p>One field rather than a separate provider selector: the backend routes by model id, so a
 * provider dropdown would be a second source of truth that could contradict the model.
 *
 * <p>Rates come from the backend's own `pricing.models` config rather than a copy here, so the
 * figure shown while picking is the same one the run's cost estimate will use.
 *
 * <p>The list is a shortcut, not a whitelist — anything not in it (a new release, a local Ollama
 * model) is still typeable, which is why "Custom…" exists and why an unrecognised saved value
 * opens in custom mode rather than being silently reset.
 */
export function ModelField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [providers, setProviders] = useState<ProvidersResponse | null>(null)

  useEffect(() => {
    let alive = true
    // Wrapped rather than just .catch()'d: this is a nice-to-have hint, and it must not be able
    // to take the whole inspector down if the endpoint is missing or the call throws outright.
    try {
      void api
        .listProviders()
        .then((r) => alive && setProviders(r))
        .catch(() => alive && setProviders(null))
    } catch {
      setProviders(null)
    }
    return () => {
      alive = false
    }
  }, [])

  const known = useMemo(() => MODEL_GROUPS.flatMap((g) => g.models), [])
  const [custom, setCustom] = useState(!known.includes(value) && value !== '')

  const group = MODEL_GROUPS.find((g) => g.models.includes(value))
  // Claude has no provider entry — it runs on its own backends, not a provider credential.
  const needsKey =
    group?.providerId != null &&
    providers != null &&
    !providers.configured.includes(group.providerId)

  const rateFor = (model: string): ModelRate | undefined => providers?.pricing[model]
  const currentRate = rateFor(value)
  // A Claude subscription run has no per-token bill at all, so a price would be misleading.
  const subscription = group?.providerId == null

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
              {g.models.map((m) => {
                const rate = rateFor(m)
                return (
                  <option key={m} value={m}>
                    {rate ? `${m} — ${rateLabel(rate)} / 1M` : m}
                  </option>
                )
              })}
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

      {currentRate && (
        <p className={styles.hint}>
          {subscription ? (
            <>
              <b>No per-token bill on a Claude subscription.</b> Costs shown against a run are an
              equivalent-usage estimate at {rateLabel(currentRate)} per 1M tokens (in / out) — useful
              for comparing runs, not a charge.
            </>
          ) : (
            <>
              <b>{rateLabel(currentRate)}</b> per 1M tokens (in / out). A mid-sized turn — 50k in,
              2k out — is about <b>{exampleCost(currentRate)}</b>. Cached context re-read on later
              turns bills at roughly a tenth of the input rate.
            </>
          )}
        </p>
      )}

      {!currentRate && providers && !subscription && (
        <p className={styles.hint}>
          No rate configured for this model, so cost is estimated at the fallback{' '}
          {rateLabel(providers.fallback)} per 1M tokens. Add it to <code>pricing.models</code> for an
          accurate figure.
        </p>
      )}

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
          <code>LLM_MODEL_PREFIXES</code> — no API key needed for localhost, and no per-token cost.
        </p>
      )}
    </>
  )
}
