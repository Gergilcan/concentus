import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { ModelCatalog, ModelRate } from '../api/types.ts'
import { MODEL_GROUPS } from '../constants.ts'
import { Field } from './fields.tsx'
import styles from './panels.module.scss'

const CUSTOM = '__custom__'

const LOCAL_HINT =
  'Served by your own model server. The list is what it reports it has loaded, so pulling a new ' +
  'model makes it appear here within a minute.'

/** "$5 / $25" — input then output, per million tokens. */
function rateLabel(rate: ModelRate): string {
  const fmt = (n: number) => (n < 1 ? `$${n}` : `$${Number.isInteger(n) ? n : n.toFixed(2)}`)
  return `${fmt(rate.input)} / ${fmt(rate.output)}`
}

/**
 * Model picker for an agent node.
 *
 * There is no provider to choose: a flow names a model, and the model decides where it runs. A
 * Claude id goes to the `claude` CLI on your subscription or to the API on `ANTHROPIC_API_KEY`; an
 * id your own model server reports goes there instead, with no credential involved at all.
 *
 * Rates come from the backend's own `pricing.models` config rather than a copy here, so the figure
 * shown while picking is the same one the run's cost estimate will use.
 *
 * The list is a shortcut, not a whitelist — a model not in it is still typeable, which is why
 * "Custom…" exists and why an unrecognised saved value opens in custom mode rather than being
 * silently reset.
 */
export function ModelField({
  value,
  onChange,
  label,
  allowNone = false,
  readOnly = false,
}: {
  value: string
  onChange: (v: string) => void
  /** Overridden by the escalation picker, which is a model field but not "the" model. */
  label?: ReactNode
  /** Adds an explicit empty choice, for a model that is optional rather than required. */
  allowNone?: boolean
  /** Shown, not editable — a block linked to a library agent takes its model from the library. */
  readOnly?: boolean
}) {
  const { t } = useTranslation()
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

  // Models the self-hosted server is actually serving. Discovered rather than listed here: what
  // you can run is whatever you have pulled, and a hardcoded list would offer one that isn't there.
  // Memoised off `catalog` rather than rebuilt each render: `?? []` is a new array every time,
  // and everything derived from it would then recompute for no reason.
  const localModels = useMemo(() => catalog?.localModels ?? [], [catalog])
  const groups = useMemo(
    () =>
      localModels.length > 0
        ? [...MODEL_GROUPS, { label: 'On your hardware', models: localModels, hint: LOCAL_HINT }]
        : MODEL_GROUPS,
    [localModels],
  )

  const known = useMemo(() => groups.flatMap((g) => g.models), [groups])
  // Custom when the author asked for it, or when the list does not know the value. Derived rather
  // than decided once: a saved local model is unknown until the catalogue answers, and a field
  // that decided "Custom…" at first render stayed there with the id in a text box after the
  // list had learned it.
  const [customChosen, setCustomChosen] = useState(false)
  const custom = customChosen || (!known.includes(value) && value !== '')

  const group = groups.find((g) => g.models.includes(value))
  const rateFor = (model: string): ModelRate | undefined => catalog?.pricing[model]
  const currentRate = rateFor(value)
  const isLocal = localModels.includes(value)

  return (
    <>
      <label className={styles.field}>
        <span>{label ?? t('Model')}</span>
        <select
          value={custom ? CUSTOM : value}
          disabled={readOnly}
          onChange={(e) => {
            if (e.target.value === CUSTOM) {
              setCustomChosen(true)
              return
            }
            setCustomChosen(false)
            onChange(e.target.value)
          }}
        >
          {allowNone && <option value="">{t('— none —')}</option>}
          {groups.map((g) => (
            <optgroup key={g.label} label={t(g.label)}>
              {g.models.map((m) => {
                const rate = rateFor(m)
                // A self-hosted model has no per-token bill, so "$0 / $0" would be noise where
                // the useful fact is that it runs here.
                if (localModels.includes(m)) {
                  return (
                    <option key={m} value={m}>
                      {m} — {t('runs locally')}
                    </option>
                  )
                }
                return (
                  <option key={m} value={m}>
                    {rate ? `${m} — ${rateLabel(rate)} / 1M` : m}
                  </option>
                )
              })}
            </optgroup>
          ))}
          <option value={CUSTOM}>{t('Custom…')}</option>
        </select>
      </label>

      {custom && (
        <Field
          label={t('Model id')}
          value={value}
          placeholder={t('e.g. claude-opus-4-8')}
          readOnly={readOnly}
          onChange={(v) => {
            // Typing here is asking for it: the box must not vanish mid-edit when the id is
            // cleared or happens to pass through a listed one.
            setCustomChosen(true)
            onChange(v)
          }}
        />
      )}

      {group && <p className={styles.hint}>{t(group.hint)}</p>}

      {isLocal && (
        <p className={styles.hint}>
          {t('Runs on your own machine, so there is no per-token bill and nothing leaves it. Delegation, file tools, SQL context and MCP all work.')}{' '}
          <b>{t('Bash and repository nodes do not')}</b>{' '}
          {t('— a flow that has to clone a repo and open a pull request needs a Claude model.')}
        </p>
      )}

      {/* Only where somebody asked. This used to render for every visitor of every agent form,
          reporting that an address they never typed is not answering — a failure of a feature
          they had not opted into, phrased as if something were wrong. It belongs on the local
          option itself, which is where the question "can I run this on my own machine" is asked. */}
      {isLocal && catalog?.localError && localModels.length === 0 && (
        <p className={styles.hint}>{catalog.localError}</p>
      )}

      {currentRate && !isLocal && (
        <p className={styles.hint}>
          {/* A subscription run has no per-token bill at all, and the same flow may run either
              way depending on the credential present — so the figure is framed as a comparison
              aid rather than a charge. */}
          {t('Costs shown against a run are an estimate at')} <b>{rateLabel(currentRate)}</b>{' '}
          {t('per 1M tokens (in / out). On a Claude')} <b>{t('subscription')}</b>{' '}
          {t('there is no per-token bill, so treat it as equivalent usage; on the')} <b>API</b>{' '}
          {t('it approximates the real charge. Cached context re-read on later turns bills at roughly a tenth of the input rate.')}
        </p>
      )}

      {!currentRate && catalog && !isLocal && (
        <p className={styles.hint}>
          {t('No rate configured for this model, so cost is estimated at the fallback')}{' '}
          {rateLabel(catalog.fallback)} {t('per 1M tokens. Add it to')} <code>pricing.models</code>{' '}
          {t('for an accurate figure.')}
        </p>
      )}
    </>
  )
}
