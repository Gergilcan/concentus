import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client.ts'
import type { BackendFlow, FlowVersionInfo, RunSummary } from '../api/types.ts'
import styles from './flows.module.scss'

interface Props {
  flows: BackendFlow[]
  runs: RunSummary[]
  onOpen: (id: string) => void
  onRun: (id: string) => void
  onDuplicate: (flow: BackendFlow) => void
  onDelete: (id: string) => void
  onNew: () => void
  onOpenRun: (runId: string) => void
  onSaveFlow: (flow: BackendFlow) => Promise<void>
  onRetryRun: (runId: string) => void
  pushError: (m: string) => void
}

type Sort = 'recent' | 'name' | 'runs'
type Kind = 'ok' | 'fail' | 'active' | 'stopped'

function kindOf(status: string): Kind {
  if (status === 'ERROR') return 'fail'
  // Stopped on purpose — neither a success nor a failure.
  if (status === 'TERMINATED') return 'stopped'
  if (status === 'RUNNING' || status === 'STARTING') return 'active'
  return 'ok'
}

const KIND_LABEL: Record<Kind, string> = {
  ok: 'Succeeded',
  fail: 'Failed',
  active: 'Running',
  stopped: 'Stopped',
}

/**
 * Runs with a decided outcome. Success rate is computed over these only: a run the user
 * stopped by hand says nothing about whether the flow works, so counting it either way
 * would skew the number.
 */
function decided(r: RunSummary): boolean {
  const k = kindOf(r.status)
  return k === 'ok' || k === 'fail'
}

function timeAgo(ts: number): string {
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (s < 45) return 'just now'
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  return d < 30 ? `${d}d ago` : new Date(ts).toLocaleDateString()
}

function compact(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

function money(usd: number): string {
  if (!usd) return '$0'
  return usd < 0.01 ? `<$0.01` : `$${usd.toFixed(2)}`
}

function triggerOf(flow: BackendFlow): { label: string; tone: string; scheduled: boolean } {
  const input = flow.nodes.find((n) => n.type === 'input')
  const d = (input?.data ?? {}) as { mode?: string; cron?: string }
  switch (d.mode) {
    case 'cron':
      return { label: `⏱ ${d.cron || 'scheduled'}`, tone: 'cron', scheduled: true }
    case 'webhook':
      return { label: '⚡ Webhook', tone: 'webhook', scheduled: false }
    case 'prompt':
      return { label: '▶ Prompt', tone: 'prompt', scheduled: false }
    default:
      return { label: '✋ Manual', tone: 'manual', scheduled: false }
  }
}

function countsOf(flow: BackendFlow) {
  let agents = 0
  let tools = 0
  for (const n of flow.nodes) {
    if (n.type === 'agent') agents++
    else if (n.type !== 'input') tools++
  }
  return { agents, tools }
}

export function FlowsPage({
  flows,
  runs,
  onOpen,
  onRun,
  onDuplicate,
  onDelete,
  onNew,
  onOpenRun,
  onSaveFlow,
  onRetryRun,
  pushError,
}: Props) {
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<Sort>('recent')
  const [tagFilter, setTagFilter] = useState<string | null>(null)
  const [settingsFor, setSettingsFor] = useState<BackendFlow | null>(null)
  const [versionsFor, setVersionsFor] = useState<BackendFlow | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  const runsByFlow = useMemo(() => {
    const m = new Map<string, RunSummary[]>()
    for (const r of runs) {
      if (!r.flowId) continue
      const list = m.get(r.flowId) ?? []
      list.push(r)
      m.set(r.flowId, list)
    }
    for (const list of m.values()) list.sort((a, b) => b.createdAt - a.createdAt)
    return m
  }, [runs])

  const allTags = useMemo(() => {
    const s = new Set<string>()
    flows.forEach((f) => (f.tags ?? []).forEach((t) => s.add(t)))
    return [...s].sort()
  }, [flows])

  const stats = useMemo(() => {
    const finished = runs.filter(decided)
    const ok = finished.filter((r) => kindOf(r.status) === 'ok').length
    return {
      flows: flows.length,
      executions: runs.length,
      active: runs.filter((r) => kindOf(r.status) === 'active').length,
      success: finished.length ? Math.round((ok / finished.length) * 100) : null,
      cost: runs.reduce((sum, r) => sum + (r.estimatedCostUsd ?? 0), 0),
    }
  }, [flows, runs])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = flows.filter(
      (f) =>
        (!q || f.name.toLowerCase().includes(q)) &&
        (!tagFilter || (f.tags ?? []).includes(tagFilter)),
    )
    const lastRunAt = (f: BackendFlow) => (f.id ? (runsByFlow.get(f.id)?.[0]?.createdAt ?? 0) : 0)
    return [...list].sort((a, b) => {
      // Favourites always float to the top.
      if (!!a.favorite !== !!b.favorite) return a.favorite ? -1 : 1
      if (sort === 'name') return a.name.localeCompare(b.name)
      if (sort === 'runs') {
        return (b.id ? (runsByFlow.get(b.id)?.length ?? 0) : 0) - (a.id ? (runsByFlow.get(a.id)?.length ?? 0) : 0)
      }
      return lastRunAt(b) - lastRunAt(a)
    })
  }, [flows, query, sort, tagFilter, runsByFlow])

  const recent = useMemo(() => [...runs].sort((a, b) => b.createdAt - a.createdAt).slice(0, 12), [runs])

  const patch = async (flow: BackendFlow, changes: Partial<BackendFlow>) => {
    try {
      await onSaveFlow({ ...flow, ...changes })
    } catch (e) {
      pushError(e instanceof Error ? e.message : String(e))
    }
  }

  const exportFlow = (flow: BackendFlow) => {
    const blob = new Blob([JSON.stringify(flow, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${flow.name.replace(/[^\w.-]+/g, '-').toLowerCase()}.flow.json`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  const importFlow = async (file: File) => {
    try {
      const parsed = JSON.parse(await file.text()) as BackendFlow
      if (!parsed || !Array.isArray(parsed.nodes)) throw new Error('That file is not a Concentus flow.')
      await onSaveFlow({
        ...parsed,
        id: undefined,
        name: parsed.name ? `${parsed.name} (imported)` : 'Imported flow',
      })
    } catch (e) {
      pushError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.inner}>
        <header className={styles.pageHead}>
          <div>
            <h1 className={styles.title}>Flows</h1>
            <p className={styles.subtitle}>
              Design multi-agent flows, run them on your Claude subscription, and watch every step.
            </p>
          </div>
          <div className={styles.headActions}>
            <input
              className={styles.search}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search flows…"
              aria-label="Search flows"
            />
            <select
              className={styles.sort}
              value={sort}
              onChange={(e) => setSort(e.target.value as Sort)}
              aria-label="Sort flows"
            >
              <option value="recent">Recently run</option>
              <option value="name">Name</option>
              <option value="runs">Most runs</option>
            </select>
            <button className={styles.ghost} onClick={() => fileRef.current?.click()}>
              Import
            </button>
            <input
              ref={fileRef}
              type="file"
              accept="application/json,.json"
              hidden
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) void importFlow(f)
                e.target.value = ''
              }}
            />
            <button className={styles.primary} onClick={onNew}>
              + New flow
            </button>
          </div>
        </header>

        <div className={styles.kpis}>
          <Kpi label="Flows" value={String(stats.flows)} />
          <Kpi label="Executions" value={String(stats.executions)} />
          <Kpi
            label="Success rate"
            value={stats.success === null ? '—' : `${stats.success}%`}
            tone={stats.success !== null && stats.success < 70 ? 'warn' : 'ok'}
          />
          <Kpi label="Running now" value={String(stats.active)} tone={stats.active ? 'active' : undefined} />
          <Kpi label="Est. cost" value={money(stats.cost)} />
        </div>

        {allTags.length > 0 && (
          <div className={styles.tagBar}>
            <button
              className={`${styles.tagChip} ${!tagFilter ? styles.tagActive : ''}`}
              onClick={() => setTagFilter(null)}
            >
              All
            </button>
            {allTags.map((t) => (
              <button
                key={t}
                className={`${styles.tagChip} ${tagFilter === t ? styles.tagActive : ''}`}
                onClick={() => setTagFilter(tagFilter === t ? null : t)}
              >
                {t}
              </button>
            ))}
          </div>
        )}

        <div className={styles.body}>
          <section className={styles.gridCol}>
            {visible.length === 0 ? (
              <div className={styles.emptyCard}>
                <div className={styles.emptyIcon}>⬡</div>
                <h3>{flows.length === 0 ? 'No flows yet' : 'Nothing matches those filters'}</h3>
                <p>
                  {flows.length === 0
                    ? 'Create a flow, drop in a coordinator and a couple of sub-agents, and run it.'
                    : 'Try a different name or tag.'}
                </p>
                {flows.length === 0 && (
                  <button className={styles.primary} onClick={onNew}>
                    + New flow
                  </button>
                )}
              </div>
            ) : (
              <div className={styles.grid}>
                {visible.map((flow) => {
                  const flowRuns = flow.id ? (runsByFlow.get(flow.id) ?? []) : []
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
                      className={`${styles.card} ${styles['t_' + trigger.tone]} ${paused ? styles.paused : ''}`}
                    >
                      <div className={styles.cardHead}>
                        <button
                          className={`${styles.star} ${flow.favorite ? styles.starOn : ''}`}
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
                        <span className={`${styles.badge} ${styles['b_' + trigger.tone]}`}>{trigger.label}</span>
                        {paused && <span className={`${styles.badge} ${styles.b_paused}`}>paused</span>}
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
                            <span className={`${styles.dot} ${styles['k_' + kindOf(last.status)]}`} />
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
                              className={`${styles.bar} ${styles['k_' + kindOf(r.status)]}`}
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
                        <button
                          className={styles.icon}
                          title="Version history"
                          onClick={() => setVersionsFor(flow)}
                        >
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
                          className={`${styles.icon} ${styles.danger}`}
                          title="Delete"
                          onClick={() => flow.id && onDelete(flow.id)}
                        >
                          ✕
                        </button>
                      </div>
                    </article>
                  )
                })}
              </div>
            )}
          </section>

          <aside className={styles.sideCol}>
            <h2 className={styles.sideTitle}>Recent executions</h2>
            {recent.length === 0 ? (
              <div className={styles.sideEmpty}>Nothing has run yet.</div>
            ) : (
              <ul className={styles.runList}>
                {recent.map((r) => (
                  <li key={r.id} className={styles.runItem}>
                    <button className={styles.runRow} onClick={() => onOpenRun(r.id)}>
                      <span className={`${styles.dot} ${styles['k_' + kindOf(r.status)]}`} />
                      <span className={styles.runFlow}>{r.flowName || 'flow'}</span>
                      {r.trigger && r.trigger !== 'manual' && (
                        <span className={styles.runTrigger}>{r.trigger}</span>
                      )}
                      <span className={styles.runTime}>{timeAgo(r.createdAt)}</span>
                    </button>
                    <button
                      className={styles.retry}
                      title="Re-run with the same input"
                      onClick={() => onRetryRun(r.id)}
                    >
                      ⟳
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </aside>
        </div>
      </div>

      {settingsFor && (
        <SettingsModal
          flow={settingsFor}
          onClose={() => setSettingsFor(null)}
          onSave={async (changes) => {
            await patch(settingsFor, changes)
            setSettingsFor(null)
          }}
        />
      )}

      {versionsFor && (
        <VersionsModal flow={versionsFor} onClose={() => setVersionsFor(null)} pushError={pushError} />
      )}
    </div>
  )
}

function Kpi({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className={styles.kpi}>
      <div className={styles.kpiLabel}>{label}</div>
      <div className={`${styles.kpiValue} ${tone ? styles['kpi_' + tone] : ''}`}>{value}</div>
    </div>
  )
}

/** Flow-level settings: name, tags, schedule pause, failure webhook. */
function SettingsModal({
  flow,
  onClose,
  onSave,
}: {
  flow: BackendFlow
  onClose: () => void
  onSave: (changes: Partial<BackendFlow>) => Promise<void>
}) {
  const [name, setName] = useState(flow.name)
  const [tags, setTags] = useState((flow.tags ?? []).join(', '))
  const [enabled, setEnabled] = useState(flow.enabled !== false)
  const [webhook, setWebhook] = useState(flow.notifyWebhook ?? '')
  const [busy, setBusy] = useState(false)

  const save = async () => {
    setBusy(true)
    await onSave({
      name: name.trim() || flow.name,
      tags: tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean),
      enabled,
      notifyWebhook: webhook.trim(),
    })
    setBusy(false)
  }

  return (
    <Modal title="Flow settings" onClose={onClose}>
      <label className={styles.field}>
        <span>Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label className={styles.field}>
        <span>Tags (comma separated)</span>
        <input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="ops, nightly" />
      </label>
      <label className={styles.toggleRow}>
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        <span>
          Enabled — when off, scheduled (cron) runs are paused. Manual runs still work.
        </span>
      </label>
      <label className={styles.field}>
        <span>Failure notification webhook</span>
        <input
          value={webhook}
          onChange={(e) => setWebhook(e.target.value)}
          placeholder="https://hooks.slack.com/services/…"
        />
      </label>
      <p className={styles.modalHint}>
        POSTed with a Slack-compatible <code>text</code> field plus run details whenever an execution
        of this flow fails.
      </p>
      <div className={styles.modalActions}>
        <button className={styles.ghost} onClick={onClose}>
          Cancel
        </button>
        <button className={styles.primary} onClick={() => void save()} disabled={busy}>
          {busy ? 'Saving…' : 'Save'}
        </button>
      </div>
    </Modal>
  )
}

/** Version history with one-click rollback. */
function VersionsModal({
  flow,
  onClose,
  pushError,
}: {
  flow: BackendFlow
  onClose: () => void
  pushError: (m: string) => void
}) {
  const [versions, setVersions] = useState<FlowVersionInfo[] | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!flow.id) {
      setVersions([])
      return
    }
    api
      .listFlowVersions(flow.id)
      .then(setVersions)
      .catch(() => setVersions([]))
  }, [flow.id])

  const restore = async (version: number) => {
    if (!flow.id) return
    if (!confirm(`Restore version ${version}? The current version is kept in history.`)) return
    setBusy(true)
    try {
      await api.restoreFlowVersion(flow.id, version)
      onClose()
    } catch (e) {
      pushError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={`History — ${flow.name}`} onClose={onClose}>
      {versions === null ? (
        <div className={styles.sideEmpty}>Loading…</div>
      ) : versions.length === 0 ? (
        <div className={styles.sideEmpty}>
          No history yet. Every save from now on adds a restorable version.
        </div>
      ) : (
        <ul className={styles.versionList}>
          {versions.map((v, i) => (
            <li key={v.version} className={styles.versionRow}>
              <span className={styles.versionNum}>v{v.version}</span>
              <span className={styles.versionName}>{v.name}</span>
              <span className={styles.versionTime}>{timeAgo(v.createdAt)}</span>
              {i === 0 ? (
                <span className={styles.versionCurrent}>current</span>
              ) : (
                <button
                  className={styles.ghost}
                  disabled={busy}
                  onClick={() => void restore(v.version)}
                >
                  Restore
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Modal>
  )
}

function Modal({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: ReactNode
}) {
  return (
    <div className={styles.backdrop} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()} role="dialog" aria-label={title}>
        <div className={styles.modalHead}>
          <h3>{title}</h3>
          <button className={styles.icon} onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
