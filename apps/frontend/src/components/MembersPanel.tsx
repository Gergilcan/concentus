import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { Member, SignInPolicy } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * Whether this installation asks people to sign in.
 *
 * Off is right for a fresh desktop install and says so: one person, a socket bound to loopback,
 * and a password to reach a port only they can open buys nothing. It stops being right the moment
 * the data is on a database a team shares — then who may change a flow is a real question, and
 * every screen below this one is describing a policy nothing is enforcing.
 *
 * Turning it on asks for the account that will administer it, and will not proceed without one.
 * With sign-in off there is nobody signed in, so a switch that simply flipped would produce, at
 * the next launch, a login screen with no account behind it and no way in from the interface.
 */
function SignInSwitch({ pushError }: { pushError: (m: string) => void }) {
  const [policy, setPolicy] = useState<SignInPolicy | null>(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api
      .signInRequired()
      .then(setPolicy)
      .catch(() => setPolicy(null))
  }, [])

  const set = async (required: boolean) => {
    setBusy(true)
    try {
      setPolicy(await api.setSignInRequired(required, email.trim(), password))
      setPassword('')
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  // A server build takes the answer from its configuration, where it belongs: a deployment anyone
  // can reach must not be able to switch its own front door off through its own front door.
  if (!policy || !policy.changeable) return null

  return (
    <div className={policy.active ? styles.signInOn : styles.signInOff}>
      {policy.active ? (
        <>
          <b>Sign-in is on.</b> Everyone reaching this installation needs an account, and every
          request is checked against the role it carries.
          {policy.next ? (
            <button className={styles.linkBtn} disabled={busy} onClick={() => void set(false)}>
              Switch it off
            </button>
          ) : (
            <span className={styles.pending}> Switched off on the next start.</span>
          )}
        </>
      ) : (
        <>
          <b>Sign-in is off on this machine.</b> There are no accounts to check, so the roles below
          describe a policy nothing is enforcing — which is fine while the data is local and only
          you can reach the port, and is not once it lives on a database a team shares.
          {policy.next ? (
            <span className={styles.pending}> Restart to start asking for a sign-in.</span>
          ) : (
            <div className={styles.signInFields}>
              <label className={styles.field}>
                <span>Your address — this account will administer it</span>
                <input
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="name@company.com"
                />
              </label>
              <label className={styles.field}>
                <span>Password (only if this address has no account yet)</span>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="At least 12 characters"
                />
              </label>
              <button
                className={styles.saveBtn}
                disabled={busy || !email.trim()}
                onClick={() => void set(true)}
              >
                {busy ? 'Turning it on…' : 'Require sign-in'}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

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
      <SignInSwitch pushError={pushError} />

      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>Members</h3>
          <p className={panels.hint}>
            {members.length === 0
              ? 'Nobody to list yet.'
              : `${members.length} ${members.length === 1 ? 'account' : 'accounts'}.`}{' '}
            Roles are enforced on every request, not only on screen.
          </p>
        </div>
        <button className={styles.newBtn} onClick={() => setAdding((open) => !open)}>
          {adding ? 'Cancel' : 'Add member'}
        </button>
      </div>

      {adding && (
        <div className={styles.addMember}>
          <div className={styles.addMemberFields}>
            <label className={styles.field}>
              <span>Email</span>
              <input
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@company.com"
              />
            </label>
            <label className={styles.field}>
              <span>Temporary password</span>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 12 characters"
              />
            </label>
            <label className={styles.field}>
              <span>Role</span>
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                {ROLES.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className={styles.addMemberFoot}>
            <span className={panels.hint}>{ROLES.find((r) => r.id === role)?.means}</span>
            <button
              className={styles.saveBtn}
              disabled={busy === 'new' || !email.trim() || !password}
              onClick={() => void add()}
            >
              {busy === 'new' ? 'Adding…' : 'Add member'}
            </button>
          </div>
        </div>
      )}

      {members.length === 0 ? (
        <p className={styles.emptyRoster}>
          No accounts yet. If this deployment runs without sign-in there is nobody to list and
          nothing to restrict — turn accounts on to divide read, run and edit between people.
        </p>
      ) : (
        <ul className={styles.memberList}>
          {members.map((m) => {
            const known = rankOf(m.role) >= 0
            return (
              <li key={m.id} className={styles.memberRow}>
                <span className={styles.memberWho}>
                  <span className={styles.memberEmail}>{m.email}</span>
                  {m.email === me && <span className={styles.youTag}>you</span>}
                </span>
                <span className={styles.memberMeta}>joined {timeAgo(m.createdAt)}</span>
                <span className={styles.memberRole}>
                  <Rungs role={m.role} />
                  <select
                    aria-label={`Role for ${m.email}`}
                    value={known ? m.role.toUpperCase() : ''}
                    disabled={busy === m.id}
                    onChange={(e) => void changeRole(m, e.target.value)}
                    title={ROLES.find((r) => r.id === m.role.toUpperCase())?.means}
                  >
                    {/* A role the backend has and this list does not still shows, unselectable,
                        rather than silently reading as the first option — which would be a lie
                        about what that account can do. */}
                    {!known && <option value="">{m.role} (unknown)</option>}
                    {ROLES.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.label}
                      </option>
                    ))}
                  </select>
                </span>
              </li>
            )
          })}
        </ul>
      )}

      <div className={styles.roleLegend}>
        <h4 className={styles.h4}>What each role may do</h4>
        <dl>
          {ROLES.map((r) => (
            <div key={r.id}>
              <dt>
                <Rungs role={r.id} />
                {r.label}
              </dt>
              <dd>{r.means}</dd>
            </div>
          ))}
        </dl>
        <p className={panels.hint}>
          Someone signing in with a company account for the first time arrives as a <b>Viewer</b>.
          They can read what the automation did; promoting them is a decision somebody makes, not a
          default.
        </p>
      </div>
    </div>
  )
}
