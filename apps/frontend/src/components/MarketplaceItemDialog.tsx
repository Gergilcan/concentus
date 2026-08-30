import { Fragment, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { MarketplaceInstallResult, MarketplaceItem } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { timeAgo } from './flowFormat.ts'
import { KIND_GLYPH, KIND_LABEL, RESOURCE_TAB, payloadRows, stateOf, type ResourceTab } from './marketplace.ts'
import { KindChip, ScopeChip, StateChip } from './MarketplaceChips.tsx'
import { MarketplaceMarkdown } from './MarketplaceMarkdown.tsx'
import { Modal } from './Modal.tsx'
import fx from './flows.module.scss'
import styles from './marketplace.module.scss'

/** The payload words the inspector writes itself, and therefore translates. */
const VALUE_WORDS = new Set(['yes', 'no', 'all', 'included'])

interface Props {
  item: MarketplaceItem
  onClose: () => void
  /** Something changed on the server — an install, an approval, a delete — so the list is stale. */
  onChanged: () => void
  onEdit: (item: MarketplaceItem) => void
  /** Opens Resources on the tab that holds what an install created. */
  onOpenResources: (tab: ResourceTab) => void
  /** Opens an installed flow. */
  onOpenFlow: (id: string) => void
  pushError: (m: string) => void
}

/**
 * One item, whole: the description, the payload as the resource's own panel would show it, and
 * the one action this person may take — Install, Update or Uninstall — plus Edit and Delete for
 * its author and Approve or Reject for a curator. The backend says which of those apply
 * (`canEdit`, `canCurate`); this only draws them.
 */
export function MarketplaceItemDialog({
  item,
  onClose,
  onChanged,
  onEdit,
  onOpenResources,
  onOpenFlow,
  pushError,
}: Props) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [installedNow, setInstalledNow] = useState<MarketplaceInstallResult | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const state = stateOf(item)

  const run = async (action: () => Promise<unknown>, after?: () => void) => {
    setBusy(true)
    try {
      await action()
      after?.()
      onChanged()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const install = () => void run(async () => setInstalledNow(await api.installMarketplaceItem(item.id)))
  const uninstall = () => {
    if (!window.confirm(t('Uninstall "{{name}}"? The {{kind}} it created is removed.', { name: item.name, kind: t(KIND_LABEL[item.kind]) }))) return
    void run(() => api.uninstallMarketplaceItem(item.id), () => setInstalledNow(null))
  }
  const remove = () => {
    if (!window.confirm(t('Delete "{{name}}" from the Marketplace? What people already installed stays where it is.', { name: item.name }))) return
    void run(() => api.deleteMarketplaceItem(item.id), onClose)
  }
  const approve = () => void run(() => api.approveMarketplaceItem(item.id))
  const reject = () => void run(() => api.rejectMarketplaceItem(item.id, reason.trim()), () => setRejecting(false))

  // Installed a moment ago, or before: either way the item links to what it became.
  const created =
    installedNow ??
    (item.installed ? { resourceId: item.installed.resourceId, kind: item.kind, version: item.installed.version } : null)
  const resourceTab = RESOURCE_TAB[item.kind]
  const rows = payloadRows(item.kind, item.payload)
  const short = rows.filter((r) => !r.long)
  const long = rows.filter((r) => r.long)

  return (
    <Modal title={`${item.icon ?? KIND_GLYPH[item.kind]} ${item.name}`} onClose={onClose} wide>
      <div className={styles.dialogHead}>
        <KindChip item={item} />
        <ScopeChip item={item} />
        <span className={styles.chip} title={t('Bumped by the author on every re-publish.')}>
          v{item.version}
        </span>
        <span className={styles.chip} title={t('Installs across the whole deployment.')}>
          {t('{{n}} installs', { n: item.installs })}
        </span>
        <StateChip item={item} />
        {item.builtIn && (
          <span className={styles.chip} title={t('Seeded by Concentus itself. It cannot be edited or deleted here; an update re-seeds it.')}>
            {t('built-in')}
          </span>
        )}
      </div>
      <p className={styles.meta}>
        {t('By {{who}}', { who: item.author.email })}
        {item.publishedAt ? ` · ${t('published {{when}}', { when: timeAgo(item.publishedAt) })}` : ''}
      </p>

      {item.status === 'rejected' && item.rejection && (
        <p className={styles.rejection} title={t("The curator's reason. Edit the item and publish again to resubmit.")}>
          {t('Rejected: {{reason}}', { reason: item.rejection })}
        </p>
      )}

      {item.description ? (
        <MarketplaceMarkdown text={item.description} />
      ) : (
        <p className={styles.description}>{item.summary}</p>
      )}

      {rows.length > 0 && (
        <>
          <h4 className={styles.sectionHead}>{t('Definition')}</h4>
          <div className={styles.rows}>
            {short.map((r) => (
              <Fragment key={r.label}>
                <span className={styles.rowLabel}>{t(r.label)}</span>
                <span className={styles.rowValue}>{VALUE_WORDS.has(r.value) ? t(r.value) : r.value}</span>
              </Fragment>
            ))}
          </div>
          {long.map((r) => (
            <div key={r.label}>
              <div className={styles.rowLabel}>{t(r.label)}</div>
              <pre className={styles.rowLong}>{r.value}</pre>
            </div>
          ))}
        </>
      )}

      {item.tags.length > 0 && (
        <div className={styles.cardTags}>
          {item.tags.map((tag) => (
            <span key={tag} className={styles.cardTag}>
              {tag}
            </span>
          ))}
        </div>
      )}

      {created && (
        <p className={styles.installedNote} role="status">
          ✓ {t('Installed as v{{n}}', { n: created.version })}
          {item.kind === 'flow' ? (
            <button className={styles.link} onClick={() => onOpenFlow(created.resourceId)}>
              {t('Open in Flows')}
            </button>
          ) : resourceTab ? (
            <button className={styles.link} onClick={() => onOpenResources(resourceTab)}>
              {t('Open in Resources')}
            </button>
          ) : (
            <span title={t("Nothing was created: pick it from an API node's inspector with Use from Marketplace.")}>
              {t('for the API node')}
            </span>
          )}
        </p>
      )}

      <div className={styles.actions}>
        {item.status === 'published' &&
          (state === 'update' ? (
            <button
              className={fx.primary}
              disabled={busy}
              onClick={install}
              title={t('Replaces what v{{from}} created with v{{to}}. Credentials you filled in stay.', {
                from: item.installed?.version ?? 1,
                to: item.version,
              })}
            >
              {t('Update to v{{n}}', { n: item.version })}
            </button>
          ) : state === 'installed' ? (
            <button
              className={fx.ghost}
              disabled={busy}
              onClick={uninstall}
              title={t('Removes the resource this install created from this organization.')}
            >
              {t('Uninstall')}
            </button>
          ) : (
            <button
              className={fx.primary}
              disabled={busy}
              onClick={install}
              title={t('Creates it in this organization — the panel then says which credential to fill in, if any. An API item creates nothing; the API node reads it.')}
            >
              {t('Install')}
            </button>
          ))}
        {item.canEdit && !item.builtIn && (
          <>
            <button
              className={fx.ghost}
              disabled={busy}
              onClick={() => onEdit(item)}
              title={t('Change the text, the tags or the payload. Saving publishes a new version; installs of the old one show Update.')}
            >
              {t('Edit')}
            </button>
            <button className={cx(fx.ghost, styles.danger)} disabled={busy} onClick={remove}>
              {t('Delete')}
            </button>
          </>
        )}
        {item.canCurate && item.status === 'pending' && (
          <>
            <button
              className={fx.primary}
              disabled={busy}
              onClick={approve}
              title={t('Publishes it to every organization on this deployment.')}
            >
              {t('Approve')}
            </button>
            <button
              className={cx(fx.ghost, styles.danger)}
              disabled={busy || rejecting}
              onClick={() => setRejecting(true)}
              title={t('Sends it back with a sentence the author reads.')}
            >
              {t('Reject…')}
            </button>
          </>
        )}
        <span className={styles.spacer} />
        <button className={fx.ghost} onClick={onClose}>
          {t('Close')}
        </button>
      </div>

      {rejecting && (
        <div className={styles.rejectBox}>
          <textarea
            rows={2}
            autoFocus
            aria-label={t('Reason for rejecting')}
            placeholder={t('Why — the author reads this sentence')}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <div className={fx.modalActions}>
            <button className={fx.ghost} onClick={() => setRejecting(false)}>
              {t('Cancel')}
            </button>
            <button
              className={cx(fx.ghost, styles.danger)}
              disabled={busy || reason.trim() === ''}
              onClick={reject}
              title={reason.trim() === '' ? t('A reason is required.') : undefined}
            >
              {t('Confirm rejection')}
            </button>
          </div>
        </div>
      )}
    </Modal>
  )
}
