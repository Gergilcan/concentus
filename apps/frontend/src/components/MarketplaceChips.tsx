import { useTranslation } from 'react-i18next'
import type { MarketplaceItem } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { groupName, useGroups } from './groups.ts'
import { KIND_LABEL, SCOPE_LABEL, STATE_LABEL, stateOf } from './marketplace.ts'
import styles from './marketplace.module.scss'

/** The kind, in the kind's own colour — the card sets the tone, the chip wears it. */
export function KindChip({ item }: { item: MarketplaceItem }) {
  const { t } = useTranslation()
  return (
    <span
      className={cx(styles.chip, styles.kindChip)}
      title={t('What an install creates: an MCP server, an agent, a facade profile, a skill, a plugin, an API definition or a flow.')}
    >
      {t(KIND_LABEL[item.kind])}
    </span>
  )
}

export function ScopeChip({ item }: { item: MarketplaceItem }) {
  const { t } = useTranslation()
  if (item.scope === 'group') return <GroupScopeChip groupId={item.groupId} />
  return (
    <span
      className={styles.chip}
      title={
        item.scope === 'global'
          ? t('Visible to every organization on this deployment.')
          : t('Visible to the members of the organization that published it.')
      }
    >
      {t(SCOPE_LABEL[item.scope])}
    </span>
  )
}

/** A group-scoped item wears the group's name; "Group" only when the caller cannot see which. */
function GroupScopeChip({ groupId }: { groupId: string | null }) {
  const { t } = useTranslation()
  const groups = useGroups({ all: true })
  return (
    <span className={styles.chip} title={t('Visible to the members of this group and administrators')}>
      {groupName(groups, groupId) ?? t(SCOPE_LABEL.group)}
    </span>
  )
}

/**
 * Installed, update available, pending, rejected — or nothing. The tooltip carries the number
 * or the sentence the chip is too small for.
 */
export function StateChip({ item }: { item: MarketplaceItem }) {
  const { t } = useTranslation()
  const state = stateOf(item)
  if (!state) return null
  const title =
    state === 'installed'
      ? t('Installed in this organization as v{{n}}.', { n: item.installed?.version ?? item.version })
      : state === 'update'
        ? t('v{{from}} is installed and v{{to}} is published. Open the item to update.', {
            from: item.installed?.version ?? 1,
            to: item.version,
          })
        : state === 'pending'
          ? t('Waiting for a curator to approve it for every organization.')
          : item.rejection || t('Rejected by a curator.')
  return (
    <span className={cx(styles.stateChip, styles['s_' + state])} title={title}>
      {t(STATE_LABEL[state])}
    </span>
  )
}
