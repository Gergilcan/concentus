import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { Group } from '../api/types.ts'
import { usePermissions } from '../state/permissionRules.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { GroupDetail } from './GroupDetail.tsx'
import { refreshGroups } from './groups.ts'
import { Spinner } from './Spinner.tsx'
import { usePanelLoad } from './usePanelLoad.ts'
import styles from './resources.module.scss'
import panels from './panels.module.scss'

/**
 * The organization's groups: the roster, and each one opened onto its members, settings and policy.
 *
 * <p>A group is the part of an organization a resource can be shown to alone — the platform team,
 * a client's squad — and the thing whose policy and settings a flow runs under when it belongs to
 * one. An administrator sees every group here and is the only one who makes, renames or deletes
 * them; a manager sees the groups they manage and edits what is inside.
 *
 * <p>Enterprise. On a Team license the roster still shows — a downgrade never widens who sees
 * what — under the license's own sentence, and "+ New" is disabled with that sentence as its
 * tooltip rather than absent: the button that is not there teaches nobody what the next tier has.
 *
 * <p>Laid out as the Members roster it sits beside: a count and a "+ New" in the header, one row
 * per group with its numbers as chips, every explanation in a tooltip.
 */
export function GroupsPanel({ pushError }: { pushError: (m: string) => void }) {
  const { t } = useTranslation()
  const { canAdminister } = usePermissions()
  const {
    value: listing,
    reload: load,
  } = usePanelLoad(() => api.listGroups(), pushError, { groups: [], allowed: false, refusal: null })
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)
  const [open, setOpen] = useState<string | null>(null)

  /** The roster and the shared answer every select reads from, both told at once. */
  const changed = () => {
    load()
    refreshGroups()
  }

  const create = async () => {
    if (!name.trim()) return
    setBusy('new')
    try {
      await api.createGroup(name.trim(), description.trim())
      setName('')
      setDescription('')
      setCreating(false)
      changed()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const rename = async (g: Group) => {
    if (!renaming) return
    const next = renaming.name.trim()
    // Leaving the field with nothing changed is a cancel, not a request.
    if (!next || next === g.name) return setRenaming(null)
    setBusy(g.id)
    try {
      await api.updateGroup(g.id, next, g.description ?? '')
      setRenaming(null)
      changed()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const remove = async (g: Group) => {
    if (
      !window.confirm(
        t('Delete "{{name}}"? What it holds returns to the organization; nothing is deleted.', { name: g.name }),
      )
    )
      return
    setBusy(g.id)
    try {
      await api.deleteGroup(g.id)
      if (open === g.id) setOpen(null)
      changed()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(null)
    }
  }

  if (!listing) return <Spinner />

  const { groups, allowed, refusal } = listing

  return (
    <div className={styles.roster}>
      <div className={styles.rosterHead}>
        <div>
          <h3 className={styles.h4}>{t('Groups')}</h3>
          <p className={panels.hint}>
            <span
              className={panels.statusPill}
              title={t('Groups in this organization. An administrator sees all of them; anybody else the ones they are in.')}
            >
              {groups.length === 1 ? t('{{count}} group', { count: 1 }) : t('{{count}} groups', { count: groups.length })}
            </span>{' '}
            {t('A part of the organization a resource can be shown to alone, with its own policy and settings.')}
          </p>
          {!allowed && refusal && (
            <p className={panels.hint} role="note">
              <b>{t('Read-only.')}</b> {refusal}
            </p>
          )}
        </div>
        {canAdminister && (
          <button
            className={styles.newBtn}
            disabled={!allowed && !creating}
            title={
              creating
                ? undefined
                : allowed
                  ? t('A named part of the organization. Members and resources are added to it afterwards.')
                  : (refusal ?? undefined)
            }
            onClick={() => setCreating((o) => !o)}
          >
            {creating ? t('Cancel') : t('+ New')}
          </button>
        )}
      </div>

      {creating && (
        <div className={styles.addMember}>
          <div className={styles.addMemberFields}>
            <label className={styles.field}>
              <span>{t('Name')}</span>
              <input
                value={name}
                autoFocus
                maxLength={80}
                placeholder={t('platform')}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void create()
                }}
              />
            </label>
            <label className={styles.field}>
              <span>{t('Description (optional)')}</span>
              <input
                value={description}
                placeholder={t('What it is for')}
                onChange={(e) => setDescription(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void create()
                }}
              />
            </label>
          </div>
          <div className={styles.addMemberFoot}>
            <span className={panels.hint}>{t('Unique in the organization. You add members and scope resources afterwards.')}</span>
            <button className={styles.saveBtn} disabled={busy === 'new' || !name.trim()} onClick={() => void create()}>
              {busy === 'new' ? t('Creating…') : t('Create')}
            </button>
          </div>
        </div>
      )}

      {groups.length === 0 ? (
        <p
          className={styles.emptyRoster}
          title={t('Make one, add members, then pick it under "Visible to" on a flow or a resource.')}
        >
          {t('No groups yet.')}
        </p>
      ) : (
        <ul className={styles.memberList}>
          {groups.map((g) => {
            const mayEdit = canAdminister || g.manager
            const editing = renaming?.id === g.id
            const isOpen = open === g.id
            return (
              <li key={g.id} className={styles.orgRow}>
                <div className={styles.memberRow}>
                  <span className={styles.memberWho}>
                    {editing ? (
                      <input
                        aria-label={t('New name for {{name}}', { name: g.name })}
                        value={renaming.name}
                        autoFocus
                        onChange={(e) => setRenaming({ id: g.id, name: e.target.value })}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') void rename(g)
                          if (e.key === 'Escape') setRenaming(null)
                        }}
                        onBlur={() => void rename(g)}
                      />
                    ) : (
                      <span
                        className={styles.memberEmail}
                        title={mayEdit ? t('Double-click to rename.') : undefined}
                        onDoubleClick={mayEdit ? () => setRenaming({ id: g.id, name: g.name }) : undefined}
                      >
                        {g.name}
                      </span>
                    )}
                    {g.manager && (
                      <span className={styles.youTag} title={t('You manage this group: its members, its settings and its policy.')}>
                        {t('manager')}
                      </span>
                    )}
                  </span>
                  <span className={styles.memberMeta}>
                    {g.description && <span className={styles.groupDescription}>{g.description}</span>}
                    <span className={styles.roleChip} title={t('Accounts in the group.')}>
                      {g.members === 1 ? t('{{count}} member', { count: 1 }) : t('{{count}} members', { count: g.members })}
                    </span>
                    <span
                      className={styles.roleChip}
                      title={t('Flows, servers, agents and the rest that only this group and the administrators see.')}
                    >
                      {g.resources === 1
                        ? t('{{count}} resource', { count: 1 })
                        : t('{{count}} resources', { count: g.resources })}
                    </span>
                  </span>
                  <span className={styles.memberRole}>
                    {mayEdit && (
                      <button
                        className={styles.rowBtn}
                        aria-label={t('Rename {{name}}', { name: g.name })}
                        title={t('Rename')}
                        disabled={busy === g.id}
                        onClick={() => setRenaming({ id: g.id, name: g.name })}
                      >
                        ✎
                      </button>
                    )}
                    {mayEdit && (
                      <button
                        className={styles.rowBtn}
                        title={t('Its members, the settings it overrides and its policy.')}
                        onClick={() => setOpen(isOpen ? null : g.id)}
                      >
                        {isOpen ? t('Close') : t('Open')}
                      </button>
                    )}
                    {canAdminister && (
                      <button
                        className={cx(styles.rowBtn, styles.rowBtnDanger)}
                        title={t('Removes the group. Its resources return to the organization; nothing is deleted.')}
                        disabled={busy === g.id}
                        onClick={() => void remove(g)}
                      >
                        {t('Delete')}
                      </button>
                    )}
                  </span>
                </div>

                {mayEdit && isOpen && (
                  <div className={styles.orgMembers}>
                    <GroupDetail group={g} allowed={allowed} refusal={refusal} pushError={pushError} onChanged={changed} />
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
