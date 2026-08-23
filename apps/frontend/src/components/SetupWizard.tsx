import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { SignedInUser, SignInProvider } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { ProviderButtons } from './ProviderButtons.tsx'
import styles from './signin.module.scss'

interface Props {
  onSignedIn: (user: SignedInUser) => void
  /** True when the backend reported that its account store is unreachable. */
  storeUnavailable?: boolean
  /** Identity providers this deployment has registrations for. */
  providers?: SignInProvider[]
}

/**
 * The first launch, when there are no accounts yet.
 *
 * <p>Not a sign-in screen: there is nobody to sign in as. And not an account the backend invents
 * either — that version printed a generated password into a log nobody reads, which is how a live
 * administrator credential ends up sitting in plain text on disk. The person in front of the
 * machine chooses the address and the password, and is signed in on the spot, because somebody who
 * picked a password seconds ago has already proved what a login form would ask them to prove.
 *
 * <p>Or they use a work account. The first identity to arrive through a provider administers the
 * installation for the same reason this form's does: it is the account that claimed an empty one.
 * That also means there is nothing to type and nothing to remember — the password that would
 * otherwise be invented here simply never exists.
 */
export function SetupWizard({ onSignedIn, storeUnavailable, providers = [] }: Props) {
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Checked here as well as by the backend, because the two answers are different questions: the
  // backend refuses a weak password, and this says so while there is still a cursor in the field.
  const tooShort = password.length > 0 && password.length < 12
  const mismatch = confirm.length > 0 && confirm !== password
  const ready = email.includes('@') && password.length >= 12 && confirm === password

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!ready) return
    setBusy(true)
    setError(null)
    try {
      onSignedIn(await api.createFirstAccount(email.trim(), password))
    } catch (err) {
      setError(errMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.wrap}>
      <form className={styles.card} onSubmit={submit}>
        <div className={styles.brand}>
          <span className={styles.logo}>⬡</span> Concentus
        </div>
        <h1 className={styles.setupTitle}>{t('Set up this installation')}</h1>
        <p className={styles.lead}>
          {t(
            'There are no accounts here yet. The one you create now administers it: it can add people and decide what each of them may do.',
          )}
        </p>

        {storeUnavailable && (
          <p className={styles.warning} role="alert">
            {t(
              'The backend cannot reach its database, so no account can be created until it is available.',
            )}
          </p>
        )}

        <div className={styles.field}>
          <label htmlFor="setup-email">{t('Email')}</label>
          <input
            id="setup-email"
            type="email"
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t('name@company.com')}
            required
            autoFocus
          />
        </div>

        <div className={styles.field}>
          <label htmlFor="setup-password">{t('Password')}</label>
          <input
            id="setup-password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-describedby="setup-password-hint"
            required
          />
          <small
            id="setup-password-hint"
            className={tooShort ? styles.fieldWarn : styles.fieldNote}
          >
            {tooShort
              ? t('{{n}} more characters', { n: 12 - password.length })
              : t('At least 12 characters.')}
          </small>
        </div>

        <div className={styles.field}>
          <label htmlFor="setup-confirm">{t('Password again')}</label>
          <input
            id="setup-confirm"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            aria-describedby={mismatch ? 'setup-confirm-hint' : undefined}
            required
          />
          {mismatch && (
            <small id="setup-confirm-hint" className={styles.fieldWarn}>
              {t('These do not match.')}
            </small>
          )}
        </div>

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}

        <button type="submit" className={styles.submit} disabled={busy || !ready}>
          {busy ? t('Creating…') : t('Create account and continue')}
        </button>

        {providers.length > 0 && (
          <>
            <p className={styles.or}>{t('or')}</p>
            {/* The first account to arrive through one of these administers the installation,
                exactly as the form above does. */}
            <ProviderButtons providers={providers} verb="Set up" />
          </>
        )}

        <p className={styles.hint}>
          {t(
            'Everyone else signs in afterwards and starts as a Viewer — able to read what the automation did, and nothing more — until you promote them under Resources → Members.',
          )}
        </p>
      </form>
    </div>
  )
}
