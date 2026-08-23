import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { SignInProviderConfig } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * Where "Continue with Microsoft" comes from.
 *
 * <p>The button on the sign-in screen only appears for a provider that has a registration, because
 * one that fails at the redirect is worse than one that is absent. Until this screen existed, the
 * only way to give it that registration was the process's environment — which on a desktop install
 * is computed by the shell and cannot be edited at all. The feature worked and nobody could turn
 * it on.
 *
 * <p>The redirect URI is the first thing here rather than the last. Every registration fails the
 * same way the first time — the address in the directory does not match the one the application
 * asks for — so it is computed from the request and offered to be copied, instead of described in
 * documentation somebody would have to go and find.
 */
export function SignInProvidersPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const [providers, setProviders] = useState<SignInProviderConfig[] | null>(null)
  const [redirectUri, setRedirectUri] = useState('')
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)

  const load = () => {
    api
      .listSignInProviders()
      .then((r) => {
        setProviders(r.providers)
        setRedirectUri(r.redirectUri)
      })
      .catch((e) => {
        setProviders([])
        pushError(errMessage(e))
      })
  }

  useEffect(load, [])

  if (!providers) return <Spinner />

  const copyRedirect = async () => {
    await navigator.clipboard.writeText(redirectUri)
    setCopied(true)
  }

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Sign-in providers')}</h3>
          <p className={panels.hint}>
            {t(
              'A provider appears on the sign-in screen once it has a client id and a secret. One without them would be a button that fails after somebody has already decided to use it.',
            )}
          </p>
        </div>
      </div>

      <div className={styles.redirectBox}>
        <span className={styles.redirectLabel}>{t('Redirect URI — register exactly this')}</span>
        <code>{redirectUri}</code>
        <button className={styles.newBtn} onClick={() => void copyRedirect()}>
          {copied ? t('Copied') : t('Copy')}
        </button>
      </div>

      {providers.map((provider) => (
        <ProviderCard
          key={provider.id}
          provider={provider}
          busy={busy === provider.id}
          onSave={async (update) => {
            setBusy(provider.id)
            try {
              const r = await api.saveSignInProvider(update)
              setProviders(r.providers)
            } catch (e) {
              pushError(errMessage(e))
            } finally {
              setBusy(null)
            }
          }}
        />
      ))}
    </div>
  )
}

function ProviderCard({
  provider,
  busy,
  onSave,
}: {
  provider: SignInProviderConfig
  busy: boolean
  onSave: (update: {
    id: string
    enabled: boolean
    clientId: string
    clientSecret: string
    tenant: string
    issuer: string
    displayName: string
  }) => Promise<void>
}) {
  const { t } = useTranslation()
  const [clientId, setClientId] = useState(provider.clientId)
  const [clientSecret, setClientSecret] = useState('')
  const [tenant, setTenant] = useState(provider.tenant)
  const [issuer, setIssuer] = useState(provider.issuer)

  const ready = clientId.trim() !== '' && (provider.hasSecret || clientSecret.trim() !== '')

  const save = (enabled: boolean) =>
    onSave({
      id: provider.id,
      enabled,
      clientId: clientId.trim(),
      // Blank leaves the stored one alone, so editing the tenant beside it does not wipe a working
      // registration.
      clientSecret,
      tenant: tenant.trim(),
      issuer: issuer.trim(),
      displayName: provider.displayName,
    }).then(() => setClientSecret(''))

  return (
    <section className={styles.providerCard}>
      <header>
        <h4 className={styles.h4}>{provider.name}</h4>
        <span className={provider.enabled ? styles.providerOn : styles.providerOff}>
          {provider.enabled ? t('on the sign-in screen') : t('not offered')}
        </span>
      </header>

      <div className={styles.providerFields}>
        <label className={styles.field}>
          <span>{t('Client id')}</span>
          <input value={clientId} onChange={(e) => setClientId(e.target.value)} />
        </label>
        <label className={styles.field}>
          <span>{t('Client secret')}</span>
          <input
            type="password"
            value={clientSecret}
            placeholder={provider.hasSecret ? t('•••••••• (unchanged)') : ''}
            onChange={(e) => setClientSecret(e.target.value)}
          />
        </label>
        {provider.wantsTenant && (
          <label className={styles.field}>
            <span>{t('Directory (tenant)')}</span>
            <input
              value={tenant}
              placeholder="organizations"
              onChange={(e) => setTenant(e.target.value)}
            />
          </label>
        )}
        {provider.wantsIssuer && (
          <label className={styles.field}>
            <span>{t('Issuer')}</span>
            <input
              value={issuer}
              placeholder="https://id.company.com"
              onChange={(e) => setIssuer(e.target.value)}
            />
          </label>
        )}
      </div>

      <div className={styles.crudActions}>
        <button
          className={styles.saveBtn}
          disabled={busy || !ready}
          onClick={() => void save(true)}
          title={ready ? undefined : t('A client id and a secret are what make the button work.')}
        >
          {busy ? t('Saving…') : provider.enabled ? t('Save') : t('Save and offer it')}
        </button>
        {provider.enabled && (
          <button className={styles.newBtn} disabled={busy} onClick={() => void save(false)}>
            {t('Stop offering it')}
          </button>
        )}
      </div>

      {provider.id === 'microsoft' && (
        <p className={panels.hint}>
          {t('Register an application in Entra ID, add the redirect URI above as a')} <b>Web</b>{' '}
          {t(
            'platform, and grant it openid, profile and email. A directory id restricts sign-in to your company; leaving it blank admits any work or school account.',
          )}
        </p>
      )}
      {provider.id === 'google' && (
        <p className={panels.hint}>
          {t('Create an OAuth client of type')} <b>Web application</b>{' '}
          {t('in Google Cloud, with the redirect URI above as an authorized redirect URI.')}
        </p>
      )}
    </section>
  )
}
