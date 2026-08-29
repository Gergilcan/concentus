import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { CreatedServiceAccount, ServiceAccount } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import { usePanelLoad } from './usePanelLoad.ts'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * The ladder a machine may stand on, least privileged first. It stops at MEMBER: a token that
 * could administer could mint more tokens, and one leak would become any number of them.
 */
const ROLES: Array<{ id: string; label: string; means: string }> = [
  {
    id: 'VIEWER',
    label: 'Viewer',
    means: 'Reads flows, runs and transcripts. For a dashboard or an export.',
  },
  {
    id: 'OPERATOR',
    label: 'Operator',
    means: 'Also runs flows: start, stop, approve, retry. What a CI job or a cron entry needs.',
  },
  {
    id: 'MEMBER',
    label: 'Member',
    means: 'Also edits flows, agents, servers and credentials. For a pipeline that ships flows.',
  },
]

function labelOf(role: string): string {
  return ROLES.find((r) => r.id === role.toUpperCase())?.label ?? role
}

function meansOf(role: string): string | undefined {
  return ROLES.find((r) => r.id === role.toUpperCase())?.means
}

/**
 * Tokens for machines.
 *
 * <p>A CI job or a cron entry used to run flows with a person's email and password in its
 * environment — that person's account, acting from a place they are not, with every power they
 * have. A service account is the honest shape: a name, a role no higher than Member, and a token
 * shown exactly once — it is not stored, only its hash, so there is no "show it again".
 *
 * <p>Admin only, like the Members roster it sits beside; unlike members, these take no seat. On a
 * Team license the backend caps working tokens at two and the listing says so, which is what
 * lets the create button be disabled honestly rather than fail after the form is filled in.
 */
export function ServiceAccountsPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const {
    value: listing,
    setValue: setListing,
    reload: load,
  } = usePanelLoad(() => api.listServiceAccounts(), pushError, { accounts: [], active: 0, limit: null, refusal: null })
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [role, setRole] = useState('OPERATOR')
  const [busy, setBusy] = useState<string | null>(null)
  // The one moment the token exists on a screen. Cleared by "Done", never re-fetchable.
  const [minted, setMinted] = useState<CreatedServiceAccount | null>(null)
  const [copied, setCopied] = useState(false)
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)

  /** What a role lets a token do, in the user's language — the picker's hint and each row's tooltip. */
  const meansLabel = (role: string) => {
    const means = meansOf(role)
    return means ? t(means) : undefined
  }

  const create = async () => {
    if (!name.trim()) return
    setBusy('new')
    try {
      const created = await api.createServiceAccount(name.trim(), role)
      setMinted(created)
      setCopied(false)
      setName('')
      setCreating(false)
      load()
    } catch (e) {
      // The Team cap and the role ceiling are refused by the backend, and its sentence is the message.
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const copy = async () => {
    if (!minted) return
    try {
      await navigator.clipboard.writeText(minted.token)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch (e) {
      pushError(errMessage(e))
    }
  }

  const revoke = async (a: ServiceAccount) => {
    if (
      !confirm(
        t('Revoke "{{name}}"? Every request presenting its token is refused from now on. This cannot be undone.', {
          name: a.name,
        }),
      )
    )
      return
    setBusy(a.id)
    try {
      await api.revokeServiceAccount(a.id)
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const rename = async () => {
    if (!renaming || !renaming.name.trim()) return
    setBusy(renaming.id)
    try {
      const updated = await api.renameServiceAccount(renaming.id, renaming.name.trim())
      setListing((prev) =>
        prev ? { ...prev, accounts: prev.accounts.map((a) => (a.id === updated.id ? updated : a)) } : prev,
      )
      setRenaming(null)
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  if (!listing) return <Spinner />

  const { accounts, active, limit, refusal } = listing

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Service accounts')}</h3>
          <p className={panels.hint}>
            {t(
              'Tokens for machines — a CI job, a cron entry, another system. Each acts as its role on every request, never as a person, and takes no seat.',
            )}{' '}
            {limit != null
              ? t('{{active}} of {{limit}} in use on this Team license.', { active, limit })
              : active === 1
                ? t('{{count}} token in use.', { count: active })
                : t('{{count}} tokens in use.', { count: active })}
          </p>
        </div>
        <button
          className={styles.newBtn}
          disabled={refusal != null && !creating}
          title={refusal ?? undefined}
          onClick={() => setCreating((open) => !open)}
        >
          {creating ? t('Cancel') : t('New service account')}
        </button>
      </div>

      {refusal && <p className={styles.capNote}>{refusal}</p>}

      {minted && (
        <div className={styles.tokenReveal} role="status">
          <h4 className={styles.h4}>{t('Token for "{{name}}"', { name: minted.account.name })}</h4>
          <code className={styles.tokenValue}>{minted.token}</code>
          <div className={styles.addMemberFoot}>
            <span className={panels.hint}>
              {t(
                'This is the only time it is shown: the token is not stored, only its hash. Put it where the machine keeps its secrets, and revoke it here if it ever leaks.',
              )}
            </span>
            <span className={styles.tokenActs}>
              <button className={styles.rowBtn} onClick={() => void copy()}>
                {copied ? t('Copied ✓') : t('Copy token')}
              </button>
              <button className={styles.saveBtn} onClick={() => setMinted(null)}>
                {t('Done')}
              </button>
            </span>
          </div>
        </div>
      )}

      {creating && (
        <div className={styles.addMember}>
          <div className={styles.addMemberFields}>
            <label className={styles.field}>
              <span>{t('Name')}</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="nightly-report"
                maxLength={80}
                autoFocus
              />
            </label>
            <label className={styles.field}>
              <span>{t('Role')}</span>
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                {ROLES.map((r) => (
                  <option key={r.id} value={r.id}>
                    {t(r.label)}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className={styles.addMemberFoot}>
            <span className={panels.hint}>{meansLabel(role)}</span>
            <button
              className={styles.saveBtn}
              disabled={busy === 'new' || !name.trim()}
              onClick={() => void create()}
            >
              {busy === 'new' ? t('Minting…') : t('Create token')}
            </button>
          </div>
        </div>
      )}

      {accounts.length === 0 ? (
        <p className={styles.emptyRoster}>
          {t(
            'No service accounts yet. Create one for each machine that runs flows — one token per job is what makes revoking it a small event.',
          )}
        </p>
      ) : (
        <ul className={styles.memberList}>
          {accounts.map((a) => {
            const revoked = a.revokedAt != null
            const editing = renaming?.id === a.id
            return (
              <li key={a.id} className={revoked ? `${styles.memberRow} ${styles.revokedRow}` : styles.memberRow}>
                <span className={styles.memberWho}>
                  {editing ? (
                    <input
                      className={styles.renameInput}
                      aria-label={t('New name for {{name}}', { name: a.name })}
                      value={renaming.name}
                      autoFocus
                      onChange={(e) => setRenaming({ id: a.id, name: e.target.value })}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') void rename()
                        if (e.key === 'Escape') setRenaming(null)
                      }}
                      onBlur={() => void rename()}
                    />
                  ) : (
                    <span className={styles.memberEmail}>{a.name}</span>
                  )}
                  {revoked && <span className={styles.revokedTag}>{t('revoked')}</span>}
                </span>
                <span className={styles.memberMeta}>
                  {revoked
                    ? t('revoked {{when}}', { when: timeAgo(a.revokedAt as number) })
                    : a.lastUsedAt != null
                      ? t('last used {{when}}', { when: timeAgo(a.lastUsedAt) })
                      : t('never used')}
                  {' · '}
                  {a.createdBy
                    ? t('created {{when}} by {{who}}', { when: timeAgo(a.createdAt), who: a.createdBy })
                    : t('created {{when}}', { when: timeAgo(a.createdAt) })}
                </span>
                <span className={styles.memberRole}>
                  <span className={styles.roleChip} title={meansLabel(a.role)}>
                    {t(labelOf(a.role))}
                  </span>
                  {!revoked && (
                    <>
                      <button
                        className={styles.rowBtn}
                        disabled={busy === a.id}
                        onClick={() => setRenaming({ id: a.id, name: a.name })}
                      >
                        {t('Rename')}
                      </button>
                      <button
                        className={`${styles.rowBtn} ${styles.rowBtnDanger}`}
                        disabled={busy === a.id}
                        onClick={() => void revoke(a)}
                      >
                        {t('Revoke')}
                      </button>
                    </>
                  )}
                </span>
              </li>
            )
          })}
        </ul>
      )}

      <div className={styles.roleLegend}>
        <h4 className={styles.h4}>{t('Using a token')}</h4>
        <p className={panels.hint}>
          {t('Send it as a bearer on any API call, and the request runs as the account with its role:')}
        </p>
        <pre className={styles.howTo}>
          {'curl -H "Authorization: Bearer csa_…" https://concentus.example.com/api/flows'}
        </pre>
        <p className={panels.hint}>
          {t('The headless CLI reads it from CONCENTUS_TOKEN instead of an email and password:')}
        </p>
        <pre className={styles.howTo}>
          {'CONCENTUS_TOKEN=csa_… node scripts/concentus-run.mjs flow.json --url https://concentus.example.com'}
        </pre>
        <dl>
          {ROLES.map((r) => (
            <div key={r.id}>
              <dt>{t(r.label)}</dt>
              <dd>{t(r.means)}</dd>
            </div>
          ))}
        </dl>
        <p className={panels.hint}>
          {t('No token can be an Admin: a token that could administer could mint more tokens, so the ladder stops one rung short.')}
        </p>
      </div>
    </div>
  )
}
