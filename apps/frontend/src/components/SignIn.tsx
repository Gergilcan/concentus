import { useState, type FormEvent } from 'react'
import { api } from '../api/client.ts'
import type { SignedInUser, SignInProvider } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import styles from './signin.module.scss'

interface Props {
  onSignedIn: (user: SignedInUser) => void
  /** True when the backend reported that its account store is unreachable. */
  storeUnavailable?: boolean
  /**
   * The identity providers this deployment has registrations for. Empty shows no buttons, which
   * is the honest state for a deployment that has none: a sign-in path that fails at the redirect
   * is worse than one that is absent.
   */
  providers?: SignInProvider[]
}

/**
 * The sign-in gate.
 *
 * Shown whenever the backend says authentication is on and this browser has no session. There is
 * deliberately no "create an account" link: on a self-hosted install that would let whoever
 * reaches the server first claim the organization. The first administrator comes from the
 * deployment's configuration, and further members are invited by an existing one.
 */
export function SignIn({ onSignedIn, storeUnavailable, providers = [] }: Props) {
  // A refusal from the directory comes back as a top-level navigation, so it arrives in the URL
  // rather than in a response this screen awaited. Usually it is a domain this deployment does not
  // admit, which is a sentence somebody needs to read — not a silent bounce back to the form.
  const redirected = new URLSearchParams(window.location.search).get('signin_error')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onSignedIn(await api.signIn(email, password))
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
        <p className={styles.lead}>Sign in to continue.</p>

        {storeUnavailable && (
          <p className={styles.warning} role="alert">
            The backend cannot reach its database, so sign-in will fail until it is available.
          </p>
        )}

        <label className={styles.field}>
          <span>Email</span>
          <input
            type="email"
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoFocus
          />
        </label>

        <label className={styles.field}>
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>

        {(error ?? redirected) && (
          <p className={styles.error} role="alert">
            {error ?? redirected}
          </p>
        )}

        <button type="submit" className={styles.submit} disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>

        {providers.length > 0 && (
          <>
            <p className={styles.or}>or</p>
            {/* Links, not fetches: the destination is another origin, and the browser has to go
                there itself. */}
            {providers.map((p) => (
              <a
                key={p.id}
                className={styles.provider}
                href={`/api/account/oidc/start?provider=${encodeURIComponent(p.id)}`}
              >
                Continue with {p.name}
              </a>
            ))}
          </>
        )}

        <p className={styles.hint}>
          No account? There is no sign-up here — on a self-hosted install that would let whoever
          reaches the server first claim the organization. An administrator adds you under
          Resources → Members.
        </p>
      </form>
    </div>
  )
}
