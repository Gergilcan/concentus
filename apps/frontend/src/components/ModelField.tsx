import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.ts'
import type { ModelCatalog, ModelRate } from '../api/types.ts'
import { MODEL_GROUPS } from '../constants.ts'
import { Field } from './fields.tsx'
import styles from './panels.module.scss'

const CUSTOM = '__custom__'

/** "$5 / $25" — input then output, per million tokens. */
function rateLabel(rate: ModelRate): string {
  const fmt = (n: number) => (n < 1 ? `$${n}` : `$${Number.isInteger(n) ? n : n.toFixed(2)}`)
  return `${fmt(rate.input)} / ${fmt(rate.output)}`
}

/**
 * Model picker for an agent node.
 *
 * Claude only, so there is no provider to choose: a flow names a model, and which credential the
 * backend has decides where it runs — the local `claude` CLI on your subscription, or the API on
 * `ANTHROPIC_API_KEY`.
 *
 * Rates come from the backend's own `pricing.models` config rather than a copy here, so the figure
 * shown while picking is the same one the run's cost estimate will use.
 *
 * The list is a shortcut, not a whitelist — a model not in it is still typeable, which is why
 * "Custom…" exists and why an unrecognised saved value opens in custom mode rather than being
 * silently reset.
 */
export function ModelField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [catalog, setCatalog] = useState<ModelCatalog | null>(null)

  useEffect(() => {
    let alive = true
    // Wrapped rather than just .catch()'d: this is a nice-to-have hint, and it must not be able
    // to take the whole inspector down if the endpoint is missing or the call throws outright.
    try {
      void api
        .listModels()
        .then((r) => alive && setCatalog(r))
        .catch(() => alive && setCatalog(null))
    } catch {
      setCatalog(null)
    }
    return () => {
      alive = false
    }
  }, [])

  const known = useMemo(() => MODEL_GROUPS.flatMap((g) => g.models), [])
  const [custom, setCustom] = useState(!known.includes(value) && value !== '')

  const group = MODEL_GROUPS.find((g) => g.models.includes(value))
  const rateFor = (model: string): ModelRate | undefined => catalog?.pricing[model]
  const currentRate = rateFor(value)

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
          <option value={CUSTOM}>Custom…</option>
        </select>
      </label>

      {custom && (
        <Field
          label="Model id"
          value={value}
          placeholder="e.g. claude-opus-4-8"
          onChange={onChange}
        />
      )}

      {group && <p className={styles.hint}>{group.hint}</p>}

      {currentRate && (
        <p className={styles.hint}>
          {/* A subscription run has no per-token bill at all, and the same flow may run either
              way depending on the credential present — so the figure is framed as a comparison
              aid rather than a charge. */}
          Costs shown against a run are an estimate at <b>{rateLabel(currentRate)}</b> per 1M tokens
          (in / out). On a Claude <b>subscription</b> there is no per-token bill, so treat it as
          equivalent usage; on the <b>API</b> it approximates the real charge. Cached context
          re-read on later turns bills at roughly a tenth of the input rate.
        </p>
      )}

      {!currentRate && catalog && (
        <p className={styles.hint}>
          No rate configured for this model, so cost is estimated at the fallback{' '}
          {rateLabel(catalog.fallback)} per 1M tokens. Add it to <code>pricing.models</code> for an
          accurate figure.
        </p>
      )}
    </>
  )
}
