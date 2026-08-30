import { useTranslation } from 'react-i18next'
import type { MarketplaceItem } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import fx from './flows.module.scss'
import styles from './marketplace.module.scss'

/**
 * The Marketplace's two marks on a resource panel: where a record came from, and the way to
 * send one there. Both optional, so a panel that knows only one of them shows only that.
 */
export function MarketplaceOrigin({
  item,
  onPublish,
  inline = false,
}: {
  /** The marketplace item this resource was installed from, when it was. */
  item?: MarketplaceItem
  /** Opens the publish form for this resource. */
  onPublish?: () => void
  /** Inside a list row rather than under a form: no margin of its own. */
  inline?: boolean
}) {
  const { t } = useTranslation()
  if (!item && !onPublish) return null
  const newer = !!item?.installed && item.installed.version < item.version
  return (
    <div className={inline ? styles.originInline : styles.originRow}>
      {item && (
        <span
          className={cx(styles.origin, newer && styles.originUpdate)}
          title={
            newer
              ? t('Installed from the Marketplace as v{{from}}; v{{to}} is published — its card offers Update.', {
                  from: item.installed?.version ?? 1,
                  to: item.version,
                })
              : t('Installed from the Marketplace; a newer version shows Update on its card.')
          }
        >
          {t('Marketplace · v{{n}}', { n: item.installed?.version ?? item.version })}
          {newer ? ' ↑' : ''}
        </span>
      )}
      {onPublish && (
        <button
          type="button"
          className={fx.ghost}
          onClick={onPublish}
          title={t('Shares this definition on the Marketplace. Credentials are stripped and named, never copied.')}
        >
          ⇪ {t('Publish to Marketplace')}
        </button>
      )}
    </div>
  )
}
