import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { LicenseFeature, LicenseStatus } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Spinner } from './Spinner.tsx'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

const REQUEST_URL = 'https://www.concentus-ai.com/#license'

/**
 * The site's "Write in" address, the one fix for every Enterprise refusal. Every panel that prints
 * one links here — the providers panel, the settings rows, the list below — so "write in" always
 * means the same mailbox and the same subject line as the pricing page.
 */
export const WRITE_IN_URL = 'mailto:gila791@hotmail.com?subject=Concentus%20enterprise'

/**
 * Whole days from today to the expiry date, both taken at UTC midnight so the answer does not
 * change with the hour; never negative — "0 days left" is the last day, and past that the
 * backend has already switched to its grace countdown.
 */
function daysLeft(expires: string): number {
  const end = Date.parse(`${expires}T00:00:00Z`)
  const today = Date.parse(`${new Date().toISOString().slice(0, 10)}T00:00:00Z`)
  return Math.max(0, Math.round((end - today) / 86_400_000))
}

/**
 * "Licensed to X · enterprise · 5 seats · expires 2099-01-01" — each part is there only when the
 * license actually carries it, so an individual (seatless, perpetual) license reads as a short
 * sentence instead of a row of blanks. A trial leads with its countdown: the days are the fact
 * that matters about a trial, the rest is the same team license underneath.
 */
function statusLine(s: LicenseStatus): string {
  const trialDays = s.trial && s.expires ? daysLeft(s.expires) : null
  return [
    trialDays != null ? `Trial — ${trialDays} day${trialDays === 1 ? '' : 's'} left` : null,
    s.licensee ? `Licensed to ${s.licensee}` : 'Licensed',
    s.tier,
    s.seats != null ? `${s.seats} seat${s.seats === 1 ? '' : 's'}` : null,
    s.expires ? `expires ${s.expires}` : null,
  ]
    .filter((part): part is string => Boolean(part))
    .join(' · ')
}

/**
 * Every Enterprise feature, a tick or a lock on each — the same list whatever is installed.
 *
 * <p>The locked lines are the reason the list exists: a Team admin reading "Send traces is
 * disabled" somewhere else in Settings finds here, in one place, what the next tier has and how
 * to ask for it. The labels are the backend enum's own words, so a refusal elsewhere and a line
 * here name the feature identically.
 */
function FeatureList({ features }: { features: LicenseFeature[] }) {
  const { t } = useTranslation()
  if (features.length === 0) return null
  const locked = features.some((f) => !f.allowed)
  return (
    <>
      <h5 className={styles.featureHead}>{t('What this license unlocks')}</h5>
      <ul className={styles.featureList}>
        {features.map((f) => (
          <li key={f.key} className={f.allowed ? styles.featureOn : styles.featureOff}>
            <span aria-hidden="true">{f.allowed ? '✓' : '🔒'}</span>
            <span>{t(f.label)}</span>
          </li>
        ))}
      </ul>
      {locked && (
        <p className={styles.refusal}>
          {t('The locked ones are Enterprise features.')}{' '}
          <a className={styles.textLink} href={WRITE_IN_URL}>
            {t('Write in')}
          </a>
        </p>
      )}
    </>
  )
}

/**
 * What this installation is running under, and the one field an owner ever changes: the token.
 *
 * <p>A missing or expired license says what's wrong in {@code problem} — written by the backend to
 * be read by whoever pastes the token in, so it is shown verbatim rather than summarized into
 * something vaguer. The request link is the fix for every reason a license can be wrong, which is
 * why it only needs to appear once, next to the problem.
 */
export function LicensePanel() {
  // Loading is its own flag rather than `status === null`: a status that never arrived (the GET
  // failed) is a different fact from a status that hasn't arrived YET, and only the second one
  // should hold the screen on a spinner. The first has to render — with the error visible and the
  // token box still usable, because pasting a fresh token is exactly the way out of that failure.
  const [loading, setLoading] = useState(true)
  const [status, setStatus] = useState<LicenseStatus | null>(null)
  const [token, setToken] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .getLicense()
      .then(setStatus)
      .catch((e) => setError(errMessage(e)))
      .finally(() => setLoading(false))
  }, [])

  const apply = async () => {
    setBusy(true)
    setError(null)
    try {
      const next = await api.installLicense(token)
      setStatus(next)
      setToken('')
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Spinner />

  return (
    <section className={styles.settingGroup}>
      <h4 className={styles.h4}>License</h4>
      {status &&
        (status.valid ? (
          <p>{statusLine(status)}</p>
        ) : (
          <>
            <p>No license</p>
            {status.problem && <p className={panels.hint}>{status.problem}</p>}
            <a className={styles.textLink} href={REQUEST_URL} target="_blank" rel="noreferrer">
              Request a license
            </a>
          </>
        ))}
      {status && <FeatureList features={status.features} />}
      <label className={panels.field}>
        <span>License token</span>
        <textarea rows={4} value={token} onChange={(e) => setToken(e.target.value)} />
      </label>
      <button className={styles.saveBtn} disabled={busy || !token.trim()} onClick={() => void apply()}>
        {busy ? 'Applying…' : 'Apply'}
      </button>
      {error && <p className={panels.previewErr}>{error}</p>}
    </section>
  )
}
