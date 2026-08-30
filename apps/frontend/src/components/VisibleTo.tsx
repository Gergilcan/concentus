import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { AssignKind } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { knownGroups, useGroups } from './groups.ts'
import { Modal } from './Modal.tsx'
import fx from './flows.module.scss'
import styles from './resources.module.scss'

interface Props {
  kind: AssignKind
  /** The saved record's id. Absent on a draft, where there is nothing to scope yet. */
  resourceId: string | undefined
  groupId: string | null | undefined
  /** The server's answer, so the caller can put it back in its record. */
  onAssigned: (groupId: string | null) => void
  /** The refusal goes to the toast when there is one; inline under the control otherwise. */
  pushError?: (m: string) => void
  /** The bare select, for a row that has no room for a label. */
  compact?: boolean
}

/**
 * Who sees a resource: everybody in the organization, or one group and the administrators.
 *
 * One control on eight forms, and a dialog for the flow card, all calling the same endpoint —
 * changing a resource's group is its own request (`POST /groups/assign`, audited), never a field
 * on the save, so a form that re-posts a record cannot move it by accident.
 *
 * Hidden until the shared groups answer is in, and hidden when there is nothing to choose: an
 * organization with no groups, on a license that could make them, has no use for a select with
 * one option. On a license that withholds groups the select stays, disabled, with the refusal
 * as its tooltip — the same face every other Enterprise gate wears here.
 */
export function VisibleTo({ kind, resourceId, groupId, onAssigned, pushError, compact }: Props) {
  const { t } = useTranslation()
  const groups = useGroups({ all: true })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!groups.loaded) return null
  const options = knownGroups(groups)
  const current = groupId ?? ''
  if (groups.allowed && options.length === 0 && !current) return null

  const disabled = !groups.allowed || !resourceId || busy
  const title = !groups.allowed
    ? (groups.refusal ?? undefined)
    : !resourceId
      ? t('Save it first — who sees it is set on the saved record.')
      : t(
          "Everybody in the organization, or one group and the administrators. A flow in a group runs under the group's policy and settings.",
        )

  const change = async (value: string) => {
    if (!resourceId) return
    setBusy(true)
    setError(null)
    try {
      const assigned = await api.assignGroup(kind, resourceId, value || null)
      onAssigned(assigned.groupId)
    } catch (e) {
      const message = errMessage(e)
      if (pushError) pushError(message)
      else setError(message)
    } finally {
      setBusy(false)
    }
  }

  // A group the caller cannot see — assigned by an admin to a group they are not in — is still
  // the truth about the record, and the select must not read as "Organization" for it.
  const unknown = current !== '' && !options.some((g) => g.id === current)

  const select = (
    <select
      aria-label={t('Visible to')}
      title={compact ? title : undefined}
      value={current}
      disabled={disabled}
      onChange={(e) => void change(e.target.value)}
    >
      <option value="">{t('Organization')}</option>
      {options.map((g) => (
        <option key={g.id} value={g.id}>
          {g.name}
        </option>
      ))}
      {unknown && <option value={current}>{t('Group')}</option>}
    </select>
  )

  if (compact) return select

  return (
    <label className={cx(styles.field, styles.visibleTo)} title={title}>
      <span>{t('Visible to')} ⓘ</span>
      {select}
      {error && (
        <span className={styles.errText} role="alert">
          {error}
        </span>
      )}
    </label>
  )
}

/**
 * The same control in a dialog, for a flow card — its menu has room for an item, not a select.
 */
export function VisibleToDialog({
  kind,
  resourceId,
  name,
  groupId,
  onClose,
  onAssigned,
  pushError,
}: {
  kind: AssignKind
  resourceId: string
  name: string
  groupId: string | null | undefined
  onClose: () => void
  onAssigned: (groupId: string | null) => void
  pushError: (m: string) => void
}) {
  const { t } = useTranslation()
  return (
    <Modal title={t('Visible to — {{name}}', { name })} onClose={onClose}>
      <p className={fx.describeHint}>
        {t(
          "Everybody in the organization, or one group and the administrators. A flow in a group runs under the group's policy and settings.",
        )}
      </p>
      <VisibleTo kind={kind} resourceId={resourceId} groupId={groupId} onAssigned={onAssigned} pushError={pushError} />
      <div className={fx.modalActions}>
        <button className={fx.ghost} onClick={onClose}>
          {t('Close')}
        </button>
      </div>
    </Modal>
  )
}
