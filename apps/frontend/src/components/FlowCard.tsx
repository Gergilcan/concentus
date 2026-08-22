import { CardMenu, menuItems } from './CardMenu.tsx'
import { useEffect, useRef, useState, type DragEvent } from 'react'
import type { BackendFlow, GoldenStatus, RunSummary } from '../api/types.ts'
import { deniedReason, usePermissions } from '../state/permissions.tsx'
import { cx } from '../utils/cx.ts'
import { hueOf } from '../utils/hueOf.ts'
import { KIND_LABEL, compact, countsOf, decided, kindOf, money, timeAgo, triggerOf } from './flowFormat.ts'
import { templateJson } from './flowTemplate.ts'
import styles from './flows.module.scss'

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
}) {
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
      key={flow.id}
      className={cx(styles.card, styles['t_' + trigger.tone], paused && styles.paused)}
      draggable={!!onDragStart}
      onDragStart={onDragStart}
    >
      <div className={styles.cardHead}>
        <button
          className={cx(styles.star, flow.favorite && styles.starOn)}
          title={flow.favorite ? 'Unpin' : 'Pin to top'}
          onClick={() => void patch(flow, { favorite: !flow.favorite })}
        >
          {flow.favorite ? '★' : '☆'}
        </button>
        <h3 className={styles.cardName} title={flow.name}>
          {flow.name}
        </h3>
        <span className={styles.mode}>{flow.mode}</span>
      </div>

      <div className={styles.badges}>
        <span className={cx(styles.badge, styles['b_' + trigger.tone])}>{trigger.label}</span>
        {paused && <span className={cx(styles.badge, styles.b_paused)}>paused</span>}
        {voices.length > 0 && (
          <span className={styles.voices}>
            {voices.map((name, i) => (
              <span key={i} title={name} style={{ background: `hsl(${hueOf(name)} 55% var(--hue-l))` }} />
            ))}
          </span>
        )}
        <span className={styles.meta}>
          {agents} agent{agents === 1 ? '' : 's'} · {tools} tool{tools === 1 ? '' : 's'}
        </span>
      </div>

      {/* The quiet half of "edit without fear": the flow has a run you trust and no longer matches
          it. One click replays that run's input against what is saved now and opens the two side
          by side. Only shown when it is actually true — a chip that is always there is furniture. */}
      {golden?.stale && onGoldenCheck && (
        <button
          className={styles.goldenChip}
          disabled={goldenChecking}
          title="This flow has changed since its golden reference ran. Replay that run's input against the flow as it is saved now, then compare the two."
          onClick={() => onGoldenCheck(golden)}
        >
          {goldenChecking ? '⭐ testing…' : '⭐ golden outdated — test now'}
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
              {KIND_LABEL[kindOf(last.status)]} · {timeAgo(last.createdAt)}
            </span>
            {!!last.totalOutputTokens && (
              <span className={styles.tok}>{compact(last.totalOutputTokens)} out</span>
            )}
          </>
        ) : (
          <span className={styles.neverRun}>Never run</span>
        )}
      </div>

      <div className={styles.history} aria-label="Recent run outcomes">
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
        {flowRuns.length === 0 && <span className={styles.barsEmpty}>no history</span>}
        <span className={styles.rate}>
          {rate !== null && `${rate}%`}
          {cost > 0 && ` · ${money(cost)}`}
        </span>
      </div>

      <div className={styles.cardActions}>
        <button className={styles.open} onClick={() => flow.id && onOpen(flow.id)}>
          Open
        </button>
        <button
          className={styles.run}
          onClick={() => flow.id && onRun(flow.id)}
          disabled={!permissions.canRun}
          title={permissions.canRun ? undefined : deniedReason(permissions, 'run')}
        >
          ▶ Run
        </button>
        {trigger.scheduled && (
          <button
            className={styles.icon}
            title={paused ? 'Resume schedule' : 'Pause schedule'}
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
                label: 'Check this flow',
                icon: '⚕',
                hint: 'Missing credentials, servers without auth, un-installed plugins, an invalid schedule, an exhausted budget. Informs — never blocks a run.',
                onSelect: () => setDoctorFor(flow),
              },
            { label: 'Version history', icon: '⟲', onSelect: () => setVersionsFor(flow) },
            {
              label: 'Settings',
              icon: '⚙',
              disabled: !permissions.canEdit,
              disabledReason: deniedReason(permissions, 'edit'),
              onSelect: () => setSettingsFor(flow),
            },
            { label: 'Export JSON', icon: '↓', onSelect: () => exportFlow(flow) },
            {
              label: copied ? 'Copied as template' : 'Copy as template',
              icon: copied ? '✓' : '⎘',
              hint: 'Credentials, accounts and private endpoints are stripped, so it is safe to share. See docs/templates.md to propose it for the gallery.',
              onSelect: () => void copyTemplate(),
            },
            {
              label: 'Duplicate',
              icon: '⧉',
              disabled: !permissions.canEdit,
              disabledReason: deniedReason(permissions, 'edit'),
              onSelect: () => onDuplicate(flow),
            },
            onSandbox && {
              label: 'Duplicate as sandbox',
              icon: '🧪',
              hint: 'A copy that runs in plan mode, with every worker facade replaced by a dry-run twin. It proposes instead of acting — the dialog says exactly what is and is not simulated.',
              onSelect: () => void onSandbox(flow),
            },
            {
              label: 'Delete',
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
