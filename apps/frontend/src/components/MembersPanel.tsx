import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { Member } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'

/** The ladder, least privileged first, with what each rung actually means on screen. */
const ROLES: Array<{ id: string; label: string; means: string }> = [
  { id: 'VIEWER', label: 'Viewer', means: 'Reads flows, runs and transcripts. Changes nothing, starts nothing.' },
  { id: 'OPERATOR', label: 'Operator', means: 'Also runs flows: start, stop, approve, retry, re-run a block. Cannot change what a flow is.' },
  { id: 'MEMBER', label: 'Member', means: 'Also edits flows, agents, servers and credentials.' },
  { id: 'ADMIN', label: 'Admin', means: 'Also manages this organization: who is in it, and as what.' },
]

/**
 * Who is in this organization and what each of them may do.
 *
 * <p>Roles were settable only through the API, which meant the feature existed and nobody could
 * use it. The rungs are described here rather than in documentation because the choice is made
 * here: "Operator" tells an admin nothing, and "runs flows but cannot change them" tells them
 * everything they needed to decide.
 */
export function MembersPanel({ pushError }: { pushError: (m: string) => void }) {
  const [members, setMembers] = useState<Member[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
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
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  if (!members) return <Spinner />

  return (
    <div className={styles.crud}>
      <div className={styles.crudList}>
        <h3 className={styles.h3}>Members</h3>
        {members.map((m) => (
          <div key={m.id} className={styles.memberRow}>
            <span className={styles.memberEmail}>{m.email}</span>
            <select
              value={ROLES.some((r) => r.id === m.role.toUpperCase()) ? m.role.toUpperCase() : ''}
              disabled={busy === m.id}
              onChange={(e) => void changeRole(m, e.target.value)}
              title={ROLES.find((r) => r.id === m.role.toUpperCase())?.means}
            >
              {/* A role the backend has and this list does not still shows, unselectable, rather
                  than silently reading as the first option — which would be a lie about what that
                  account can do. */}
              {!ROLES.some((r) => r.id === m.role.toUpperCase()) && (
                <option value="">{m.role} (unknown)</option>
              )}
              {ROLES.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.label}
                </option>
              ))}
            </select>
            <span className={styles.memberMeta}>joined {timeAgo(m.createdAt)}</span>
          </div>
        ))}
        {members.length === 0 && (
          <p className={styles.hint}>
            No members yet, or this deployment runs without sign-in — where accounts are switched
            off there is nobody to list and nothing to restrict.
          </p>
        )}
      </div>

      <div className={styles.crudForm}>
        <h3 className={styles.h3}>Add a member</h3>
        <label className={styles.field}>
          <span>Email</span>
          <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@company.com" />
        </label>
        <label className={styles.field}>
          <span>Temporary password (at least 12 characters)</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
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
        <p className={styles.hint}>{ROLES.find((r) => r.id === role)?.means}</p>
        <button className={styles.saveBtn} disabled={busy === 'new'} onClick={() => void add()}>
          {busy === 'new' ? 'Adding…' : 'Add member'}
        </button>
        <p className={styles.hint}>
          Someone signing in with a company account for the first time arrives as a <b>Viewer</b>.
          They can read what the automation did; promoting them is a decision somebody makes, not a
          default.
        </p>
      </div>
    </div>
  )
}
