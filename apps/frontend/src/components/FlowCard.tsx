import { CardMenu, type CardMenuItem } from './CardMenu.tsx'
import { useEffect, useRef, useState, type DragEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { BackendFlow, GoldenStatus, RunSummary } from '../api/types.ts'
import { deniedReason, usePermissions } from '../state/permissionRules.ts'
import { cx } from '../utils/cx.ts'
import { hueOf } from '../utils/hueOf.ts'
import { KIND_LABEL, compact, countsOf, decided, kindOf, money, timeAgo, triggerOf } from './flowFormat.ts'
import { templateJson } from './flowTemplate.ts'
import styles from './flows.module.scss'

/** Drops the menu items whose feature is not wired up on this card. */
// The falsy union is wide on purpose: an item is guarded with `flow.id && …`, and an absent id
// is an empty string rather than false. Narrowing this to `false` would make every optional item
// a ternary, which is noise around the thing that matters.
function menuItems(items: Array<CardMenuItem | false | null | undefined | '' | 0>): CardMenuItem[] {
  return items.filter((i): i is CardMenuItem => !!i)
}

export function FlowCard({
  flow,
  flowRuns,
  onOpen,
  onRun,
  onDuplicate,
  onSandbox,
  onDelete,
  patch,
  exportFlow,
  setVersionsFor,
  setSettingsFor,
  setDoctorFor,
  setTagFilter,
  onDragStart,
  onPublish,
  golden,
  onGoldenCheck,
  goldenChecking = false,
}: {
  flow: BackendFlow
  flowRuns: RunSummary[]
  /** This flow's golden reference, when it has one. Absent means there is nothing to be stale. */
  golden?: GoldenStatus
  /** Starts a golden re-run against the flow as saved now. */
  onGoldenCheck?: (status: GoldenStatus) => void
  /** A check this card started is still running. */
  goldenChecking?: boolean
  onOpen: (id: string) => void
  onRun: (id: string) => void
  onDuplicate: (flow: BackendFlow) => void
  /** Makes a plan-mode, dry-run copy. Absent simply hides the button. */
  onSandbox?: (flow: BackendFlow) => Promise<void> | void
  onDelete: (id: string) => void
  patch: (flow: BackendFlow, changes: Partial<BackendFlow>) => Promise<void>
  exportFlow: (flow: BackendFlow) => void
  setVersionsFor: (flow: BackendFlow) => void
  setSettingsFor: (flow: BackendFlow) => void
  /** Opens the pre-run check. Absent (or an unsaved flow) simply hides the button. */
  setDoctorFor?: (flow: BackendFlow) => void
  setTagFilter: (tag: string) => void
  /** When set, the card can be dragged (onto a dashboard folder). The handler fills the payload. */
  onDragStart?: (e: DragEvent) => void
  /** Opens the Marketplace publish form for this flow. Absent simply hides the item. */
  onPublish?: (flow: BackendFlow) => void
}) {
  const { t } = useTranslation()
  // Copy-as-template feedback: a ✓ for a moment, then back. Clipboard writes are invisible,
  // and a button that seems to do nothing gets clicked five times.
  const permissions = usePermissions()
  const [copied, setCopied] = useState(false)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => {
    if (copiedTimer.current) clearTimeout(copiedTimer.current)
  }, [])

  const copyTemplate = async () => {
    const json = templateJson(flow)
    const markCopied = () => {
      setCopied(true)
      copiedTimer.current = setTimeout(() => setCopied(false), 1500)
    }
    try {
      await navigator.clipboard.writeText(json)
      markCopied()
    } catch {
      // Clipboard access can be denied (insecure context, permissions). The old fallback was
      // window.prompt — which Electron does not implement, so it threw and copied nothing. A
      // hidden textarea + execCommand works in exactly the contexts where the async API refuses,
      // and the ✓ only shows when a copy actually happened.
      const ta = document.createElement('textarea')
      ta.value = json
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      ta.remove()
      if (ok) markCopied()
    }
  }

  const last = flowRuns[0]
  const trigger = triggerOf(flow)
  const { agents, tools } = countsOf(flow)
  // The flow's ensemble: one dot per agent, wearing the same hue that agent's console chip and
  // log lines wear. Capped so a fan-out monster doesn't turn the card into confetti.
  const voices = flow.nodes
    .filter((n) => n.type === 'agent')
    .map((n) => String((n.data as { name?: unknown })?.name ?? ''))
    .filter(Boolean)
    .slice(0, 6)
  const finished = flowRuns.filter(decided)
  const ok = finished.filter((r) => kindOf(r.status) === 'ok').length
  const rate = finished.length ? Math.round((ok / finished.length) * 100) : null
  const cost = flowRuns.reduce((s, r) => s + (r.estimatedCostUsd ?? 0), 0)
  const paused = flow.enabled === false
  const tags = flow.tags ?? []

  return (
    <article
      className={cx(styles.card, styles['t_' + trigger.tone], paused && styles.paused)}
      draggable={!!onDragStart}
      onDragStart={onDragStart}
      // Double-click anywhere on the card opens the flow — the Open button stays as the
      // discoverable path; this is the muscle-memory one. Except on the card's own controls:
      // two fast clicks on a button mean "that control, twice" (a star toggled and untoggled),
      // not "open".
      onDoubleClick={(e) => {
        if ((e.target as HTMLElement).closest('button, a')) return
        if (flow.id) onOpen(flow.id)
      }}
    >
      <div className={styles.cardHead}>
        <button
          className={cx(styles.star, flow.favorite && styles.starOn)}
          title={flow.favorite ? t('Unpin') : t('Pin to top')}
          onClick={() => void patch(flow, { favorite: !flow.favorite })}
        >
          {flow.favorite ? '★' : '☆'}
        </button>
        <h3 className={styles.cardName} title={flow.name}>
          {flow.name}
        </h3>
      </div>

      <div className={styles.badges}>
        <span className={cx(styles.badge, styles['b_' + trigger.tone])}>{t(trigger.label)}</span>
        {paused && <span className={cx(styles.badge, styles.b_paused)}>{t('paused')}</span>}
        {voices.length > 0 && (
          <span className={styles.voices}>
            {voices.map((name, i) => (
              <span key={i} title={name} style={{ background: `hsl(${hueOf(name)} 55% var(--hue-l))` }} />
            ))}
          </span>
        )}
        <span className={styles.meta}>
          {agents === 1 ? t('{{n}} agent', { n: agents }) : t('{{n}} agents', { n: agents })} ·{' '}
          {tools === 1 ? t('{{n}} tool', { n: tools }) : t('{{n}} tools', { n: tools })}
        </span>
      </div>

      {/* The quiet half of "edit without fear": the flow has a run you trust and no longer matches
          it. One click replays that run's input against what is saved now and opens the two side
          by side. Only shown when it is actually true — a chip that is always there is furniture. */}
      {golden?.stale && onGoldenCheck && (
        <button
          className={styles.goldenChip}
          disabled={goldenChecking}
          title={t("This flow has changed since its golden reference ran. Replay that run's input against the flow as it is saved now, then compare the two.")}
          onClick={() => onGoldenCheck(golden)}
        >
          {goldenChecking ? t('⭐ testing…') : t('⭐ golden outdated — test now')}
        </button>
      )}

      {tags.length > 0 && (
        <div className={styles.cardTags}>
          {tags.map((t) => (
            <button key={t} className={styles.cardTag} onClick={() => setTagFilter(t)}>
              {t}
            </button>
          ))}
        </div>
      )}

      <div className={styles.lastRun}>
        {last ? (
          <>
            <span className={cx(styles.dot, styles['k_' + kindOf(last.status)])} />
            <span className={styles.lastText}>
              {t(KIND_LABEL[kindOf(last.status)])} · {timeAgo(last.createdAt)}
            </span>
            {!!last.totalOutputTokens && (
              <span className={styles.tok}>{t('{{tokens}} out', { tokens: compact(last.totalOutputTokens) })}</span>
            )}
          </>
        ) : (
          <span className={styles.neverRun}>{t('Never run')}</span>
        )}
      </div>

      <div className={styles.history} aria-label={t('Recent run outcomes')}>
        {flowRuns
          .slice(0, 10)
          .reverse()
          .map((r) => (
            <span
              key={r.id}
              className={cx(styles.bar, styles['k_' + kindOf(r.status)])}
              title={`${r.status} · ${timeAgo(r.createdAt)}`}
            />
          ))}
        {flowRuns.length === 0 && <span className={styles.barsEmpty}>{t('no history')}</span>}
        <span className={styles.rate}>
          {rate !== null && `${rate}%`}
          {cost > 0 && ` · ${money(cost)}`}
        </span>
      </div>

      <div className={styles.cardActions}>
        <button className={styles.open} onClick={() => flow.id && onOpen(flow.id)}>
          {t('Open')}
        </button>
        <button
          className={styles.run}
          onClick={() => flow.id && onRun(flow.id)}
          disabled={!permissions.canRun}
          title={permissions.canRun ? undefined : deniedReason(permissions, 'run')}
        >
          {t('▶ Run')}
        </button>
        {trigger.scheduled && (
          <button
            className={styles.icon}
            title={paused ? t('Resume schedule') : t('Pause schedule')}
            disabled={!permissions.canEdit}
            onClick={() => void patch(flow, { enabled: paused })}
          >
            {paused ? '▶' : '❚❚'}
          </button>
        )}
        <div className={styles.spacer} />
        {/* Everything a person does rarely, behind one button and with a name on it. Nine controls
            is more than a card footer holds at four columns — they used to wrap onto a second line,
            and before that sit on the card's own border — and seven of them were a glyph you had to
            hover to identify. */}
        <CardMenu
          label={flow.name}
          items={menuItems([
            flow.id &&
              setDoctorFor && {
                label: t('Check this flow'),
                icon: '⚕',
                hint: t('Missing credentials, servers without auth, un-installed plugins, an invalid schedule, an exhausted budget. Informs — never blocks a run.'),
                onSelect: () => setDoctorFor(flow),
              },
            { label: t('Version history'), icon: '⟲', onSelect: () => setVersionsFor(flow) },
            {
              label: t('Settings'),
              icon: '⚙',
              disabled: !permissions.canEdit,
              disabledReason: deniedReason(permissions, 'edit'),
              onSelect: () => setSettingsFor(flow),
            },
            { label: t('Export JSON'), icon: '↓', onSelect: () => exportFlow(flow) },
            {
              label: copied ? t('Copied as template') : t('Copy as template'),
              icon: copied ? '✓' : '⎘',
              hint: t('Credentials, accounts and private endpoints are stripped, so it is safe to share. See docs/templates.md to propose it for the gallery.'),
              onSelect: () => void copyTemplate(),
            },
            flow.id &&
              onPublish && {
                label: t('Publish to Marketplace'),
                icon: '⇪',
                hint: t('Shares this flow as a template on the Marketplace. Credentials, accounts and private endpoints are stripped and named.'),
                onSelect: () => onPublish(flow),
              },
            {
              label: t('Duplicate'),
              icon: '⧉',
              disabled: !permissions.canEdit,
              disabledReason: deniedReason(permissions, 'edit'),
              onSelect: () => onDuplicate(flow),
            },
            onSandbox && {
              label: t('Duplicate as sandbox'),
              icon: '🧪',
              hint: t('A copy that runs in plan mode, with every worker facade replaced by a dry-run twin. It proposes instead of acting — the dialog says exactly what is and is not simulated.'),
              onSelect: () => void onSandbox(flow),
            },
            {
              label: t('Delete'),
              icon: '✕',
              danger: true,
              disabled: !permissions.canEdit,
              disabledReason: deniedReason(permissions, 'edit'),
              onSelect: () => flow.id && onDelete(flow.id),
            },
          ])}
        />
      </div>
    </article>
  )
}
