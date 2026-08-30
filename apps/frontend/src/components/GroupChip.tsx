import { useTranslation } from 'react-i18next'
import { groupName, useGroups } from './groups.ts'
import styles from './resources.module.scss'

/**
 * The name of the group a resource is visible to, as a chip — on the card, the row, the flow's
 * header. Nothing when the resource belongs to the whole organization: a chip on every row would
 * make the scoped ones invisible.
 *
 * Split in two so a row with no group costs nothing: the outer component returns before any hook
 * runs, and only a chip that will actually show subscribes to the shared groups answer.
 */
export function GroupChip({ groupId, className }: { groupId: string | null | undefined; className?: string }) {
  if (!groupId) return null
  return <NamedChip groupId={groupId} className={className} />
}

function NamedChip({ groupId, className }: { groupId: string; className?: string }) {
  const { t } = useTranslation()
  const groups = useGroups({ all: true })
  return (
    <span
      className={className ?? styles.groupChip}
      title={t('Visible to the members of this group and administrators')}
      data-testid="group-chip"
    >
      {groupName(groups, groupId) ?? t('Group')}
    </span>
  )
}
