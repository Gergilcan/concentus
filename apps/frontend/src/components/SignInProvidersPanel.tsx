import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { SignInProviderConfig, SignInProvidersList } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { WRITE_IN_URL } from './LicensePanel.tsx'
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
 *
 * <p>What the license withholds is said here too, in the backend's own words, next to the thing it
 * withholds: a custom issuer on a Team license is listed and marked inactive rather than hidden,
 * and the domain allowlist says why it does nothing. The alternative — a form that saves and a
 * sign-in screen that quietly declines to show the result — is the failure this panel exists to
 * avoid.
 */
export function SignInProvidersPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const [list, setList] = useState<SignInProvidersList | null>(null)
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)

  const load = () => {
    api
      .listSignInProviders()
      .then(setList)
      .catch((e) => {
        setList({ providers: [], redirectUri: '', live: [], allowedDomains: '', domainJitRefusal: null })
        pushError(errMessage(e))
      })
  }

  useEffect(load, [])

  if (!list) return <Spinner />

  const copyRedirect = async () => {
    await navigator.clipboard.writeText(list.redirectUri)
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
        <code>{list.redirectUri}</code>
        <button className={styles.newBtn} onClick={() => void copyRedirect()}>
          {copied ? t('Copied') : t('Copy')}
        </button>
      </div>

      <DomainAllowlist domains={list.allowedDomains} refusal={list.domainJitRefusal} />

      {list.providers.map((provider) => (
        <ProviderCard
          key={provider.id}
          provider={provider}
          busy={busy === provider.id}
          onSave={async (update) => {
            setBusy(provider.id)
            try {
              setList(await api.saveSignInProvider(update))
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

/**
 * Who a provider admits without an invitation.
 *
 * <p>Read-only in every case: the list is startup configuration (AUTH_ALLOWED_DOMAINS), shown here
 * because the screen that offers the providers is where somebody asks "and who gets in". On a
 * Team license the answer is nobody uninvited — automatic accounts are Enterprise — so the field
 * is disabled with that sentence rather than showing a list nothing acts on.
 */
function DomainAllowlist({ domains, refusal }: { domains: string; refusal: string | null }) {
  const { t } = useTranslation()
  return (
    <div>
      <label className={styles.field}>
        <span>{t('Domains whose people get an account on first sign-in')}</span>
        <input
          value={domains}
          disabled
          placeholder={refusal ? '' : t('any address a provider vouches for')}
          aria-describedby="domain-allowlist-note"
        />
      </label>
      {refusal ? (
        <p id="domain-allowlist-note" className={styles.refusal}>
          {refusal} {t('Add people under Members instead; they sign in with the providers below.')}{' '}
          <a className={styles.textLink} href={WRITE_IN_URL}>
            {t('Write in')}
          </a>
        </p>
      ) : (
        <p id="domain-allowlist-note" className={panels.hint}>
          {t(
            'Set at startup with AUTH_ALLOWED_DOMAINS. Blank admits any address a provider vouches for; people you add under Members sign in either way.',
          )}
        </p>
      )}
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

  // Withheld by the license: the fields stay readable, so a registration made under another
  // license is visibly still there, but nothing here can be saved or offered.
  const inactive = provider.refusal != null
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
    <section className={inactive ? `${styles.providerCard} ${styles.providerInactive}` : styles.providerCard}>
      <header>
        <h4 className={styles.h4}>{provider.name}</h4>
        <span className={provider.enabled && !inactive ? styles.providerOn : styles.providerOff}>
          {inactive
            ? t('Enterprise — inactive')
            : provider.enabled
              ? t('on the sign-in screen')
              : t('not offered')}
        </span>
      </header>

      {inactive && (
        <p className={styles.refusal}>
          {provider.refusal}{' '}
          <a className={styles.textLink} href={WRITE_IN_URL}>
            {t('Write in')}
          </a>
        </p>
      )}

      <div className={styles.providerFields}>
        <label className={styles.field}>
          <span>{t('Client id')}</span>
          <input value={clientId} disabled={inactive} onChange={(e) => setClientId(e.target.value)} />
        </label>
        <label className={styles.field}>
          <span>{t('Client secret')}</span>
          <input
            type="password"
            value={clientSecret}
            disabled={inactive}
            placeholder={provider.hasSecret ? t('•••••••• (unchanged)') : ''}
            onChange={(e) => setClientSecret(e.target.value)}
          />
        </label>
        {provider.wantsTenant && (
          <label className={styles.field}>
            <span>{t('Directory (tenant)')}</span>
            <input
              value={tenant}
              disabled={inactive}
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
              disabled={inactive}
              placeholder="https://id.company.com"
              onChange={(e) => setIssuer(e.target.value)}
            />
          </label>
        )}
      </div>

      <div className={styles.crudActions}>
        <button
          className={styles.saveBtn}
          disabled={busy || !ready || inactive}
          onClick={() => void save(true)}
          title={
            inactive
              ? (provider.refusal ?? undefined)
              : ready
                ? undefined
                : t('A client id and a secret are what make the button work.')
          }
        >
          {busy ? t('Saving…') : provider.enabled ? t('Save') : t('Save and offer it')}
        </button>
        {provider.enabled && !inactive && (
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
