import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { Member } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { SignInProvidersPanel } from './SignInProvidersPanel.tsx'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/** The ladder, least privileged first, with what each rung actually means on screen. */
const ROLES: Array<{ id: string; label: string; means: string }> = [
  {
    id: 'VIEWER',
    label: 'Viewer',
    means: 'Reads flows, runs and transcripts. Changes nothing, starts nothing.',
  },
  {
    id: 'OPERATOR',
    label: 'Operator',
    means: 'Also runs flows: start, stop, approve, retry, re-run a block. Cannot change what a flow is.',
  },
  { id: 'MEMBER', label: 'Member', means: 'Also edits flows, agents, servers and credentials.' },
  {
    id: 'ADMIN',
    label: 'Admin',
    means: 'Also manages this organization: who is in it, and as what.',
  },
]

/** How high a role sits, or -1 for one this build does not know. */
function rankOf(role: string): number {
  return ROLES.findIndex((r) => r.id === role.toUpperCase())
}

/**
 * The rung, drawn.
 *
 * Four words read as four alternatives; four filled steps read as a ladder, which is what the
 * roles are — every rung keeps what the one below it has and adds to it. Worth drawing because
 * "is Operator above or below Member" is the actual question an admin has when granting access,
 * and prose in a tooltip answers it one account at a time.
 */
function Rungs({ role }: { role: string }) {
  const rank = rankOf(role)
  return (
    <span className={styles.rungs} aria-hidden="true">
      {ROLES.map((r, i) => (
        <i key={r.id} className={i <= rank ? styles.rungOn : styles.rungOff} />
      ))}
    </span>
  )
}

/**
 * Who is in this organization and what each of them may do.
 *
 * <p>Roles were settable only through the API, which meant the feature existed and nobody could
 * use it. The rungs are described here rather than in documentation because the choice is made
 * here: "Operator" tells an admin nothing, and "runs flows but cannot change them" tells them
 * everything they needed to decide.
 *
 * <p>Laid out as one full-width roster rather than the two-column CRUD shell the other tabs use.
 * That shell puts a 280px list beside a form, which is right when the list is a menu of things to
 * open — and wrong here, where the list <em>is</em> the page: it squeezed addresses into an
 * ellipsis while a three-field form owned the other thousand pixels.
 */
export function MembersPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const [members, setMembers] = useState<Member[] | null>(null)
  const [me, setMe] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('VIEWER')

  const load = () => {
    api
      .listMembers()
      .then(setMembers)
      .catch((e) => {
        setMembers([])
        pushError(errMessage(e))
      })
  }

  useEffect(load, [])

  // Which row is the person reading it. Marking it is not decoration: demoting yourself out of
  // this very page is the one change on it you cannot undo from here.
  useEffect(() => {
    api
      .session()
      .then((s) => setMe(s.email ?? null))
      .catch(() => setMe(null))
  }, [])

  const changeRole = async (member: Member, next: string) => {
    setBusy(member.id)
    try {
      const updated = await api.changeMemberRole(member.id, next)
      setMembers((prev) => (prev ?? []).map((m) => (m.id === updated.id ? updated : m)))
    } catch (e) {
      // The backend refuses the last admin's demotion, and that refusal is the whole message.
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const add = async () => {
    if (!email.trim() || !password) return
    setBusy('new')
    try {
      await api.addMember(email.trim(), password, role)
      setEmail('')
      setPassword('')
      setAdding(false)
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  if (!members) return <Spinner />

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Members')}</h3>
          <p className={panels.hint}>
            {members.length === 0
              ? t('Nobody to list yet.')
              : members.length === 1
                ? t('{{count}} account.', { count: members.length })
                : t('{{count}} accounts.', { count: members.length })}{' '}
            {t('Roles are enforced on every request, not only on screen.')}{' '}
            {t(
              'Seats are counted for the whole deployment, as distinct accounts: a person in two organizations uses one.',
            )}
          </p>
        </div>
        <button className={styles.newBtn} onClick={() => setAdding((open) => !open)}>
          {adding ? t('Cancel') : t('Add member')}
        </button>
      </div>

      {adding && (
        <div className={styles.addMember}>
          <div className={styles.addMemberFields}>
            <label className={styles.field}>
              <span>{t('Email')}</span>
              <input
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@company.com"
              />
            </label>
            <label className={styles.field}>
              <span>{t('Temporary password')}</span>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('At least 12 characters')}
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
            <span className={panels.hint}>
              {(() => {
                const means = ROLES.find((r) => r.id === role)?.means
                return means ? t(means) : undefined
              })()}
            </span>
            <button
              className={styles.saveBtn}
              disabled={busy === 'new' || !email.trim() || !password}
              onClick={() => void add()}
            >
              {busy === 'new' ? t('Adding…') : t('Add member')}
            </button>
          </div>
        </div>
      )}

      {members.length === 0 ? (
        <p className={styles.emptyRoster}>
          {t(
            'No accounts yet. If this deployment runs without sign-in there is nobody to list and nothing to restrict — turn accounts on to divide read, run and edit between people.',
          )}
        </p>
      ) : (
        <ul className={styles.memberList}>
          {members.map((m) => {
            const known = rankOf(m.role) >= 0
            return (
              <li key={m.id} className={styles.memberRow}>
                <span className={styles.memberWho}>
                  <span className={styles.memberEmail}>{m.email}</span>
                  {m.email === me && <span className={styles.youTag}>{t('you')}</span>}
                </span>
                <span className={styles.memberMeta}>{t('joined {{when}}', { when: timeAgo(m.createdAt) })}</span>
                <span className={styles.memberRole}>
                  <Rungs role={m.role} />
                  <select
                    aria-label={t('Role for {{email}}', { email: m.email })}
                    value={known ? m.role.toUpperCase() : ''}
                    disabled={busy === m.id}
                    onChange={(e) => void changeRole(m, e.target.value)}
                    title={(() => {
                      const means = ROLES.find((r) => r.id === m.role.toUpperCase())?.means
                      return means ? t(means) : undefined
                    })()}
                  >
                    {/* A role the backend has and this list does not still shows, unselectable,
                        rather than silently reading as the first option — which would be a lie
                        about what that account can do. */}
                    {!known && <option value="">{t('{{role}} (unknown)', { role: m.role })}</option>}
                    {ROLES.map((r) => (
                      <option key={r.id} value={r.id}>
                        {t(r.label)}
                      </option>
                    ))}
                  </select>
                </span>
              </li>
            )
          })}
        </ul>
      )}

      <SignInProvidersPanel pushError={pushError} />

      <div className={styles.roleLegend}>
        <h4 className={styles.h4}>{t('What each role may do')}</h4>
        <dl>
          {ROLES.map((r) => (
            <div key={r.id}>
              <dt>
                <Rungs role={r.id} />
                {t(r.label)}
              </dt>
              <dd>{t(r.means)}</dd>
            </div>
          ))}
        </dl>
        <p className={panels.hint}>
          {t('Someone signing in with a company account for the first time arrives as a')}{' '}
          <b>{t('Viewer')}</b>.{' '}
          {t(
            'They can read what the automation did; promoting them is a decision somebody makes, not a default.',
          )}
        </p>
      </div>
    </div>
  )
}
