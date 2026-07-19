import type { BackendFlow, RunSummary } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { KIND_LABEL, compact, countsOf, decided, kindOf, money, timeAgo, triggerOf } from './flowFormat.ts'
import styles from './flows.module.scss'

export function FlowCard({
  flow,
  flowRuns,
  onOpen,
  onRun,
  onDuplicate,
  onDelete,
  patch,
  exportFlow,
  setVersionsFor,
  setSettingsFor,
  setTagFilter,
}: {
  flow: BackendFlow
  flowRuns: RunSummary[]
  onOpen: (id: string) => void
  onRun: (id: string) => void
  onDuplicate: (flow: BackendFlow) => void
  onDelete: (id: string) => void
  patch: (flow: BackendFlow, changes: Partial<BackendFlow>) => Promise<void>
  exportFlow: (flow: BackendFlow) => void
  setVersionsFor: (flow: BackendFlow) => void
  setSettingsFor: (flow: BackendFlow) => void
  setTagFilter: (tag: string) => void
}) {
  const last = flowRuns[0]
  const trigger = triggerOf(flow)
  const { agents, tools } = countsOf(flow)
  const finished = flowRuns.filter(decided)
  const ok = finished.filter((r) => kindOf(r.status) === 'ok').length
  const rate = finished.length ? Math.round((ok / finished.length) * 100) : null
  const cost = flowRuns.reduce((s, r) => s + (r.estimatedCostUsd ?? 0), 0)
  const paused = flow.enabled === false

  return (
    <article
      key={flow.id}
      className={cx(styles.card, styles['t_' + trigger.tone], paused && styles.paused)}
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
        <span className={styles.meta}>
          {agents} agent{agents === 1 ? '' : 's'} · {tools} tool{tools === 1 ? '' : 's'}
        </span>
      </div>

      {(flow.tags ?? []).length > 0 && (
        <div className={styles.cardTags}>
          {(flow.tags ?? []).map((t) => (
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
        <button className={styles.run} onClick={() => flow.id && onRun(flow.id)}>
          ▶ Run
        </button>
        {trigger.scheduled && (
          <button
            className={styles.icon}
            title={paused ? 'Resume schedule' : 'Pause schedule'}
            onClick={() => void patch(flow, { enabled: paused })}
          >
            {paused ? '▶' : '❚❚'}
          </button>
        )}
        <div className={styles.spacer} />
        <button className={styles.icon} title="Version history" onClick={() => setVersionsFor(flow)}>
          ⟲
        </button>
        <button className={styles.icon} title="Settings" onClick={() => setSettingsFor(flow)}>
          ⚙
        </button>
        <button className={styles.icon} title="Export JSON" onClick={() => exportFlow(flow)}>
          ↓
        </button>
        <button className={styles.icon} title="Duplicate" onClick={() => onDuplicate(flow)}>
          ⧉
        </button>
        <button
          className={cx(styles.icon, styles.danger)}
          title="Delete"
          onClick={() => flow.id && onDelete(flow.id)}
        >
          ✕
        </button>
      </div>
    </article>
  )
}
