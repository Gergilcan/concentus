import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { MarketplaceItem, MarketplaceKind, MarketplaceList, MarketplaceScope } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { compact } from './flowFormat.ts'
import {
  EMPTY_FILTERS,
  KIND_GLYPH,
  KIND_LABEL,
  KINDS,
  tagsOf,
  visibleItems,
  type Filters,
  type MarketplaceSort,
  type ResourceTab,
  type StateFilter,
} from './marketplace.ts'
import { KindChip, ScopeChip, StateChip } from './MarketplaceChips.tsx'
import { MarketplaceItemDialog } from './MarketplaceItemDialog.tsx'
import { MarketplacePublishDialog } from './MarketplacePublishDialog.tsx'
import { Spinner } from './Spinner.tsx'
import { usePanelLoad } from './usePanelLoad.ts'
import fx from './flows.module.scss'
import styles from './marketplace.module.scss'

const EMPTY_LIST: MarketplaceList = { items: [], tags: [], curator: false, pending: 0 }

interface Props {
  pushError: (m: string) => void
  /** Opens Resources on the tab that holds what an install created. */
  onOpenResources: (tab: ResourceTab) => void
  /** Opens an installed flow in Studio. */
  onOpenFlow: (id: string) => void
}

/**
 * The Marketplace: the things a flow is built from, published by people and installed with one
 * click. The Flows dashboard's card grid, in the canvas's node-kind colours.
 *
 * The server decides what this caller may see — published items, their own, the pending ones
 * if they curate — and sends it whole; the search, the filters and the sort happen here, on
 * every keystroke, without another request.
 */
export function MarketplacePage({ pushError, onOpenResources, onOpenFlow }: Props) {
  const { t } = useTranslation()
  const { value: list, reload } = usePanelLoad(() => api.listMarketplaceItems(), pushError, EMPTY_LIST)
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [publishing, setPublishing] = useState(false)
  const [editing, setEditing] = useState<MarketplaceItem | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const items = useMemo(() => list?.items ?? [], [list])
  const visible = useMemo(() => visibleItems(items, filters), [items, filters])
  const tags = useMemo(() => (list?.tags.length ? list.tags : tagsOf(items)), [list, items])
  const selected = selectedId ? (items.find((i) => i.id === selectedId) ?? null) : null

  const set = <K extends keyof Filters>(key: K, value: Filters[K]) => setFilters((f) => ({ ...f, [key]: value }))
  const toggleTag = (tag: string) =>
    set('tags', filters.tags.includes(tag) ? filters.tags.filter((x) => x !== tag) : [...filters.tags, tag])

  const onPublished = (_item: MarketplaceItem, stripped: string[]) => {
    setPublishing(false)
    setEditing(null)
    reload()
    if (stripped.length > 0) {
      setNotice(
        t('Published without its credentials ({{names}}). Whoever installs it fills them in on their side.', {
          names: stripped.join(', '),
        }),
      )
    }
  }

  if (!list) return <Spinner />

  return (
    <div className={styles.page}>
      <div className={styles.inner}>
        <div className={styles.toolbar}>
          <input
            className={fx.search}
            value={filters.query}
            onChange={(e) => set('query', e.target.value)}
            placeholder={t('Search the Marketplace…')}
            aria-label={t('Search the Marketplace')}
            title={t('Name, summary and tags.')}
          />
          <select
            className={fx.sort}
            value={filters.kind}
            aria-label={t('Kind')}
            onChange={(e) => set('kind', e.target.value as '' | MarketplaceKind)}
          >
            <option value="">{t('All kinds')}</option>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t(KIND_LABEL[k])}
              </option>
            ))}
          </select>
          <select
            className={fx.sort}
            value={filters.scope}
            aria-label={t('Scope')}
            onChange={(e) => set('scope', e.target.value as '' | MarketplaceScope)}
          >
            <option value="">{t('All scopes')}</option>
            <option value="organization">{t('Organization')}</option>
            <option value="global">{t('Global')}</option>
          </select>
          <select
            className={fx.sort}
            value={filters.state}
            aria-label={t('State')}
            onChange={(e) => set('state', e.target.value as StateFilter)}
          >
            <option value="">{t('Any state')}</option>
            <option value="installed">{t('Installed')}</option>
            <option value="update">{t('Update available')}</option>
            <option value="pending">{t('Pending')}</option>
            <option value="rejected">{t('Rejected')}</option>
            <option value="mine">{t('Mine')}</option>
          </select>
          <select
            className={fx.sort}
            value={filters.sort}
            aria-label={t('Sort')}
            onChange={(e) => set('sort', e.target.value as MarketplaceSort)}
          >
            <option value="installs">{t('Most installed')}</option>
            <option value="newest">{t('Newest')}</option>
            <option value="name">{t('Name')}</option>
          </select>
          <span className={styles.toolbarSpacer} />
          {list.curator && list.pending > 0 && (
            <button
              className={cx(styles.pendingBadge, filters.state === 'pending' && styles.pendingBadgeOn)}
              aria-pressed={filters.state === 'pending'}
              title={t('Global submissions waiting for your approval. Click to see only those.')}
              onClick={() => set('state', filters.state === 'pending' ? '' : 'pending')}
            >
              ⏳ {t('{{n}} pending', { n: list.pending })}
            </button>
          )}
          <button
            className={fx.primary}
            onClick={() => setPublishing(true)}
            title={t('Share an MCP server, an agent, a facade, a skill, a plugin, an API or a flow with your organization — or with everyone.')}
          >
            + {t('Publish')}
          </button>
        </div>

        {tags.length > 0 && (
          <div className={styles.tagRow} aria-label={t('Tags')}>
            {tags.map((tag) => {
              const on = filters.tags.includes(tag)
              return (
                <button
                  key={tag}
                  className={cx(styles.tag, on && styles.tagOn)}
                  aria-pressed={on}
                  onClick={() => toggleTag(tag)}
                  title={on ? t('Stop narrowing by this tag') : t('Only items with this tag')}
                >
                  {tag}
                </button>
              )
            })}
          </div>
        )}

        {notice && (
          <p className={styles.notice} role="status">
            {notice}
            <button onClick={() => setNotice(null)} aria-label={t('Dismiss')}>
              ×
            </button>
          </p>
        )}

        {visible.length === 0 ? (
          <p className={styles.empty}>
            {items.length === 0
              ? t('Nothing published yet — press Publish to share the first item.')
              : t('Nothing matches those filters.')}
          </p>
        ) : (
          <div className={styles.grid}>
            {visible.map((item) => (
              <MarketplaceCard key={item.id} item={item} onOpen={() => setSelectedId(item.id)} onTag={toggleTag} />
            ))}
          </div>
        )}
      </div>

      {selected && (
        <MarketplaceItemDialog
          item={selected}
          onClose={() => setSelectedId(null)}
          onChanged={reload}
          onEdit={setEditing}
          onOpenResources={onOpenResources}
          onOpenFlow={onOpenFlow}
          pushError={pushError}
        />
      )}

      {(publishing || editing) && (
        <MarketplacePublishDialog
          onClose={() => {
            setPublishing(false)
            setEditing(null)
          }}
          onPublished={onPublished}
          pushError={pushError}
          editing={editing ?? undefined}
        />
      )}
    </div>
  )
}

/**
 * One card: icon, name, the kind and scope chips, the summary on one line, the install count and
 * the state chip. Nothing else — hover explains, and a click opens the dialog.
 */
function MarketplaceCard({
  item,
  onOpen,
  onTag,
}: {
  item: MarketplaceItem
  onOpen: () => void
  onTag: (tag: string) => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      className={cx(styles.card, styles['k_' + item.kind])}
      data-testid="marketplace-card"
      onClick={onOpen}
    >
      <span className={styles.cardHead}>
        <span className={styles.icon} aria-hidden="true">
          {item.icon ?? KIND_GLYPH[item.kind]}
        </span>
        <span className={styles.cardName} title={item.name}>
          {item.name}
        </span>
        {item.builtIn && (
          <span className={styles.chip} title={t('Seeded by Concentus itself. It cannot be edited or deleted here; an update re-seeds it.')}>
            {t('built-in')}
          </span>
        )}
      </span>
      <span className={styles.chips}>
        <KindChip item={item} />
        <ScopeChip item={item} />
        {item.version > 1 && (
          <span className={styles.chip} title={t('Bumped by the author on every re-publish.')}>
            v{item.version}
          </span>
        )}
      </span>
      <span className={styles.summary} title={item.summary}>
        {item.summary}
      </span>
      {item.tags.length > 0 && (
        <span className={styles.cardTags}>
          {item.tags.slice(0, 4).map((tag) => (
            <span
              key={tag}
              role="button"
              className={styles.cardTag}
              title={t('Only items with this tag')}
              onClick={(e) => {
                e.stopPropagation()
                onTag(tag)
              }}
            >
              {tag}
            </span>
          ))}
        </span>
      )}
      <span className={styles.foot}>
        <span title={t('Installs across the whole deployment.')}>
          {t('{{n}} installs', { n: compact(item.installs) })}
        </span>
        <span className={styles.spacer} />
        <StateChip item={item} />
      </span>
    </button>
  )
}
