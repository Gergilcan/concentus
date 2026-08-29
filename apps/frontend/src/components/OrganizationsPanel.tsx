import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { Member, Organization } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import { usePanelLoad } from './usePanelLoad.ts'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/** The ladder, least privileged first — the same one the Members tab draws. */
const ROLES = ['VIEWER', 'OPERATOR', 'MEMBER', 'ADMIN'] as const

/** Title case for a role that arrives shouting from the API. */
function roleLabel(role: string | null | undefined): string {
  if (!role) return ''
  return role.charAt(0).toUpperCase() + role.slice(1).toLowerCase()
}

/**
 * Several organizations on one deployment.
 *
 * <p>An organization is the isolation boundary: its flows, credentials, runs and settings are its
 * own, invisible from every other one on the same server. This is where an administrator makes a
 * second one, names it, and decides who is in each — a person can be in several, with a different
 * role in each, and switches between them from the account menu.
 *
 * <p>Creating a second organization is the Enterprise gate. The button stays: the refusal the
 * backend answers with names the feature and the tier that has it, and showing that sentence is a
 * better answer than a button that is not there.
 */
export function OrganizationsPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const { value: organizations, reload: load } = usePanelLoad(() => api.listOrganizations(), pushError, [])
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [refusal, setRefusal] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<string | null>(null)
  const [renameTo, setRenameTo] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const [members, setMembers] = useState<Record<string, Member[]>>({})
  const [invite, setInvite] = useState({ email: '', password: '', role: 'VIEWER' })
  const [busy, setBusy] = useState<string | null>(null)

  const loadMembers = (id: string) => {
    api
      .listOrganizationMembers(id)
      .then((list) => setMembers((prev) => ({ ...prev, [id]: list })))
      .catch((e) => pushError(errMessage(e)))
  }

  const toggle = (org: Organization) => {
    const next = open === org.id ? null : org.id
    setOpen(next)
    setInvite({ email: '', password: '', role: 'VIEWER' })
    if (next && !members[next]) loadMembers(next)
  }

  const create = async () => {
    if (!newName.trim()) return
    setBusy('new')
    setRefusal(null)
    try {
      await api.createOrganization(newName.trim())
      setNewName('')
      setCreating(false)
      load()
    } catch (e) {
      // The refusal is the feature's own sentence — "… is an Enterprise feature …" — and it
      // belongs next to the form, where the person deciding whether to upgrade is looking.
      setRefusal(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const rename = async (org: Organization) => {
    if (!renameTo.trim() || renameTo.trim() === org.name) return setRenaming(null)
    setBusy(org.id)
    try {
      await api.renameOrganization(org.id, renameTo.trim())
      setRenaming(null)
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const sendInvite = async (org: Organization) => {
    if (!invite.email.trim()) return
    setBusy(`invite-${org.id}`)
    try {
      await api.inviteToOrganization(org.id, invite.email.trim(), invite.password, invite.role)
      setInvite({ email: '', password: '', role: 'VIEWER' })
      loadMembers(org.id)
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  if (!organizations) return <Spinner />

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Organizations')}</h3>
          <p className={panels.hint}>
            {t(
              'Each organization has its own flows, agents, servers, credentials, runs and settings; nothing is shared between them. People can be in several, with a role in each, and switch from the account menu.',
            )}{' '}
            {t(
              'Seats are counted for the whole deployment, as distinct accounts: a person in two organizations uses one.',
            )}
          </p>
        </div>
        <button
          className={styles.newBtn}
          onClick={() => {
            setCreating((o) => !o)
            setRefusal(null)
          }}
        >
          {creating ? t('Cancel') : t('New organization')}
        </button>
      </div>

      {creating && (
        <div className={styles.addMember}>
          <div className={styles.addMemberFields}>
            <label className={styles.field}>
              <span>{t('Name')}</span>
              <input
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder={t('The team or company it is for')}
              />
            </label>
          </div>
          <div className={styles.addMemberFoot}>
            <span className={panels.hint}>
              {t('You will administer it. Creating a second organization is an Enterprise feature.')}
            </span>
            <button
              className={styles.saveBtn}
              disabled={busy === 'new' || !newName.trim()}
              onClick={() => void create()}
            >
              {busy === 'new' ? t('Creating…') : t('Create')}
            </button>
          </div>
          {refusal && (
            <p className={styles.errText} role="alert">
              {refusal}
            </p>
          )}
        </div>
      )}

      <ul className={styles.memberList}>
        {organizations.map((org) => {
          const admin = org.role === 'ADMIN'
          const list = members[org.id]
          return (
            <li key={org.id} className={styles.orgRow}>
              <div className={styles.memberRow}>
                <span className={styles.memberWho}>
                  {renaming === org.id ? (
                    <input
                      aria-label={t('New name for {{name}}', { name: org.name })}
                      value={renameTo}
                      autoFocus
                      onChange={(e) => setRenameTo(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') void rename(org)
                        if (e.key === 'Escape') setRenaming(null)
                      }}
                      onBlur={() => void rename(org)}
                    />
                  ) : (
                    <span className={styles.memberEmail}>{org.name}</span>
                  )}
                  {org.current && <span className={styles.youTag}>{t('current')}</span>}
                </span>
                <span className={styles.memberMeta}>
                  {t('created {{when}}', { when: timeAgo(org.createdAt) })}
                  {org.role && <> · {t(roleLabel(org.role))}</>}
                </span>
                <span className={styles.memberRole}>
                  {admin && (
                    <button
                      className={styles.rowBtn}
                      disabled={busy === org.id}
                      onClick={() => {
                        setRenaming(org.id)
                        setRenameTo(org.name)
                      }}
                    >
                      {t('Rename')}
                    </button>
                  )}
                  {admin && (
                    <button className={styles.rowBtn} onClick={() => toggle(org)}>
                      {open === org.id ? t('Hide members') : t('Members')}
                    </button>
                  )}
                </span>
              </div>

              {admin && open === org.id && (
                <div className={styles.orgMembers}>
                  {!list ? (
                    <Spinner />
                  ) : list.length === 0 ? (
                    <p className={panels.hint}>{t('Nobody to list yet.')}</p>
                  ) : (
                    <ul className={styles.memberList}>
                      {list.map((m) => (
                        <li key={m.id} className={styles.memberRow}>
                          <span className={styles.memberWho}>
                            <span className={styles.memberEmail}>{m.email}</span>
                          </span>
                          <span className={styles.memberMeta}>
                            {t('joined {{when}}', { when: timeAgo(m.createdAt) })}
                          </span>
                          <span className={styles.memberRole}>{t(roleLabel(m.role))}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                  <div className={styles.addMember}>
                    <div className={styles.addMemberFields}>
                      <label className={styles.field}>
                        <span>{t('Email')}</span>
                        <input
                          value={invite.email}
                          onChange={(e) => setInvite({ ...invite, email: e.target.value })}
                          placeholder="name@company.com"
                        />
                      </label>
                      <label className={styles.field}>
                        <span>{t('Temporary password')}</span>
                        <input
                          type="password"
                          value={invite.password}
                          onChange={(e) => setInvite({ ...invite, password: e.target.value })}
                          placeholder={t('Only for an address with no account yet')}
                        />
                      </label>
                      <label className={styles.field}>
                        <span>{t('Role')}</span>
                        <select
                          value={invite.role}
                          onChange={(e) => setInvite({ ...invite, role: e.target.value })}
                        >
                          {ROLES.map((r) => (
                            <option key={r} value={r}>
                              {t(roleLabel(r))}
                            </option>
                          ))}
                        </select>
                      </label>
                    </div>
                    <div className={styles.addMemberFoot}>
                      <span className={panels.hint}>
                        {t(
                          'An existing account joins as it is and takes no extra seat; a new address needs a temporary password.',
                        )}
                      </span>
                      <button
                        className={styles.saveBtn}
                        disabled={busy === `invite-${org.id}` || !invite.email.trim()}
                        onClick={() => void sendInvite(org)}
                      >
                        {busy === `invite-${org.id}` ? t('Adding…') : t('Add to organization')}
                      </button>
                    </div>
                  </div>
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
