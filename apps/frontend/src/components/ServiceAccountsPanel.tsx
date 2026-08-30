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
 * <p>Laid out as the Members roster it sits beside: a count and a "+ New" in the header, one row
 * per account, and every explanation in a tooltip rather than on the page. The first version said
 * it all out loud — three paragraphs, a legend, two shell commands — and read as a manual with a
 * list attached, when the list is what an admin comes here for.
 *
 * <p>Admin only, like Members; unlike members, these take no seat. On a Team license the backend
 * caps working tokens and the listing says so, which is what lets the create button be disabled
 * honestly rather than fail after the form is filled in.
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

  /** What a role lets a token do, in the user's language — the picker's hint and each chip's tooltip. */
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
    if (!confirm(t('Revoke "{{name}}"? This cannot be undone.', { name: a.name }))) return
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

  const rename = async (a: ServiceAccount) => {
    if (!renaming) return
    const next = renaming.name.trim()
    // Leaving the field with nothing changed is a cancel, not a request.
    if (!next || next === a.name) return setRenaming(null)
    setBusy(a.id)
    try {
      const updated = await api.renameServiceAccount(a.id, next)
      setListing((prev) =>
        prev ? { ...prev, accounts: prev.accounts.map((x) => (x.id === updated.id ? updated : x)) } : prev,
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

  // The count as a chip; why it is what it is — the Team cap, or the refusal once it is reached —
  // on hover, the same way the disabled "+ New" carries it.
  const inUse =
    limit != null ? t('{{active}} of {{limit}} in use', { active, limit }) : t('{{count}} in use', { count: active })
  const inUseWhy =
    refusal ??
    (limit != null
      ? t('Working tokens on this Team license; revoked ones do not count. Enterprise has no cap.')
      : t('Working tokens; revoked ones do not count.'))

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Service accounts')}</h3>
          <p className={panels.hint}>
            <span className={panels.statusPill} title={inUseWhy}>
              {inUse}
            </span>{' '}
            {t('Tokens for machines: each acts as its role, never as a person, and takes no seat.')}
          </p>
        </div>
        <button
          className={styles.newBtn}
          disabled={refusal != null && !creating}
          title={creating ? undefined : (refusal ?? t('Mint a token for a machine. It is shown once.'))}
          onClick={() => setCreating((open) => !open)}
        >
          {creating ? t('Cancel') : t('+ New')}
        </button>
      </div>

      {minted && (
        <div className={styles.redirectBox} role="status">
          <span className={styles.redirectLabel}>
            {t('Token for "{{name}}"', { name: minted.account.name })} — {t('Shown once.')}
          </span>
          <code>{minted.token}</code>
          <button
            className={styles.saveBtn}
            title={t(
              'Copy it. Send it as "Authorization: Bearer …" on any API call, or set CONCENTUS_TOKEN for the headless CLI.',
            )}
            onClick={() => void copy()}
          >
            {copied ? t('Copied') : t('Copy')}
          </button>
          <button
            className={styles.newBtn}
            title={t('Hide it. Only its hash is stored, so it cannot be shown again.')}
            onClick={() => setMinted(null)}
          >
            {t('Done')}
          </button>
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
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void create()
                }}
                placeholder="nightly-report"
                maxLength={80}
                autoFocus
              />
            </label>
            <label className={styles.field}>
              <span>{t('Role')}</span>
              <select
                value={role}
                title={t('Never Admin: a token that could administer could mint more tokens.')}
                onChange={(e) => setRole(e.target.value)}
              >
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
              {busy === 'new' ? t('Creating…') : t('Create')}
            </button>
          </div>
        </div>
      )}

      {accounts.length === 0 ? (
        <p
          className={styles.emptyRoster}
          title={t('One token per machine that runs flows, so revoking one is a small event.')}
        >
          {t('No service accounts yet.')}
        </p>
      ) : (
        <ul className={styles.memberList}>
          {accounts.map((a) => {
            const revoked = a.revokedAt != null
            const editing = renaming?.id === a.id
            return (
              <li
                key={a.id}
                className={revoked ? `${styles.memberRow} ${styles.revokedRow}` : styles.memberRow}
                title={revoked ? t('Revoked. Kept as the record of what could act here.') : undefined}
              >
                <span className={styles.memberWho}>
                  {editing ? (
                    <input
                      aria-label={t('New name for {{name}}', { name: a.name })}
                      value={renaming.name}
                      autoFocus
                      onChange={(e) => setRenaming({ id: a.id, name: e.target.value })}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') void rename(a)
                        if (e.key === 'Escape') setRenaming(null)
                      }}
                      onBlur={() => void rename(a)}
                    />
                  ) : (
                    <span
                      className={styles.memberEmail}
                      title={revoked ? undefined : t('Double-click to rename.')}
                      onDoubleClick={revoked ? undefined : () => setRenaming({ id: a.id, name: a.name })}
                    >
                      {a.name}
                    </span>
                  )}
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
                        aria-label={t('Rename')}
                        title={t('Rename')}
                        disabled={busy === a.id}
                        onClick={() => setRenaming({ id: a.id, name: a.name })}
                      >
                        ✎
                      </button>
                      <button
                        className={`${styles.rowBtn} ${styles.rowBtnDanger}`}
                        title={t('Refuse every request with this token from now on. Cannot be undone.')}
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
    </div>
  )
}
