import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { SignInProvider } from '../api/types.ts'
import styles from './signin.module.scss'

/**
 * Every way in this application knows about, registered or not.
 *
 * <p>All of them on purpose. Showing only what is switched on answered "can I sign in with my work
 * account here?" with an absence, which reads as no — when the true answer is usually "yes, once
 * somebody spends two minutes registering it".
 *
 * <p>What makes that safe is that an unregistered one is not a link. It says what it needs, on the
 * spot, instead of sending somebody to a provider that will refuse them after they have already
 * typed their password. A button that fails at the redirect is the thing worth avoiding; a button
 * that explains itself is not.
 */
export function ProviderButtons({
  providers,
  verb,
}: {
  providers: SignInProvider[]
  /** "Continue" on the sign-in screen, "Set up" on the first launch. */
  verb: string
}) {
  const { t } = useTranslation()
  const [explaining, setExplaining] = useState<string | null>(null)

  // The verb is a key of its own ("Continue", "Set up"), so each screen's phrasing translates
  // whole; the provider's name is a proper noun and stays as the API sent it.
  const label = (name: string) => t('{{verb}} with {{name}}', { verb: t(verb), name })

  return (
    <>
      {providers.map((provider) =>
        provider.configured === false ? (
          <div key={provider.id}>
            <button
              type="button"
              className={`${styles.provider} ${styles.providerUnset}`}
              onClick={() => setExplaining(explaining === provider.id ? null : provider.id)}
            >
              {label(provider.name)}
            </button>
            {explaining === provider.id && (
              <p className={styles.providerNote}>
                {t(
                  '{{name}} is not registered on this installation yet. An administrator adds its client id and secret under Resources → Members → Sign-in providers; the screen there carries the redirect URI to register.',
                  { name: provider.name },
                )}
              </p>
            )}
          </div>
        ) : (
          // A link, not a fetch: the destination is another origin, and the browser has to go
          // there itself.
          <a
            key={provider.id}
            className={styles.provider}
            href={`/api/account/oidc/start?provider=${encodeURIComponent(provider.id)}`}
          >
            {label(provider.name)}
          </a>
        ),
      )}
    </>
  )
}
