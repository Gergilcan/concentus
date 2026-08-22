import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { LicenseStatus } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Spinner } from './Spinner.tsx'
import panels from './panels.module.scss'
import styles from './resources.module.scss'

const REQUEST_URL = 'https://www.concentus-ai.com/#license'

/**
 * "Licensed to X · enterprise · 5 seats · expires 2099-01-01" — each part is there only when the
 * license actually carries it, so an individual (seatless, perpetual) license reads as a short
 * sentence instead of a row of blanks.
 */
function statusLine(s: LicenseStatus): string {
  return [
    s.licensee ? `Licensed to ${s.licensee}` : 'Licensed',
    s.tier,
    s.seats != null ? `${s.seats} seat${s.seats === 1 ? '' : 's'}` : null,
    s.expires ? `expires ${s.expires}` : null,
  ]
    .filter((part): part is string => Boolean(part))
    .join(' · ')
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
