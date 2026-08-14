import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client.ts'
import type { AvailablePlugin, PluginsView } from '../api/types.ts'
import { cx } from '../utils/cx.ts'
import { errMessage } from '../utils/errMessage.ts'
import { compact } from './flowFormat.ts'
import { Spinner } from './Spinner.tsx'
import styles from './resources.module.scss'

/**
 * Claude Code plugins, presented the way a plugin manager presents them: one row per plugin —
 * name, then marketplace/version/state as quiet chips — with the actions right-aligned and
 * sized like every other row action in the app. Thin over the `claude plugin` CLI; which
 * plugins a RUN loads is chosen per agent, on the agent's inspector.
 */
export function PluginsPanel({ pushError }: { pushError: (m: string) => void }) {
  const [view, setView] = useState<PluginsView | null>(null)
  const [catalog, setCatalog] = useState<AvailablePlugin[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [marketSource, setMarketSource] = useState('')

  const load = useCallback(() => {
    api
      .listPlugins()
      .then(setView)
      .catch((e) => pushError(errMessage(e)))
  }, [pushError])

  useEffect(load, [load])

  // The catalog is one heavier read (every marketplace's full list); fetched once and filtered
  // client-side, so typing in the search box never waits on the CLI.
  useEffect(() => {
    let alive = true
    api
      .listAvailablePlugins()
      .then((c) => alive && setCatalog(c))
      .catch(() => alive && setCatalog([]))
    return () => {
      alive = false
    }
  }, [])

  const installedIds = useMemo(() => new Set((view?.plugins ?? []).map((p) => p.id)), [view])

  // Popular first; an empty query shows the top of the catalog so the section invites browsing.
  const results = useMemo(() => {
    if (!catalog) return []
    const q = search.trim().toLowerCase()
    const pool = q
      ? catalog.filter(
          (p) =>
            p.id.toLowerCase().includes(q) ||
            (p.description ?? '').toLowerCase().includes(q),
        )
      : catalog
    return [...pool]
      .sort((a, b) => (b.installCount ?? 0) - (a.installCount ?? 0))
      .slice(0, q ? 20 : 8)
  }, [catalog, search])

  const act = async (run: () => Promise<{ status: string }>, after?: () => void) => {
    setBusy(true)
    setStatus(null)
    try {
      setStatus((await run()).status)
      after?.()
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!view) return <Spinner />

  return (
    <div className={`${styles.crudForm} ${styles.lone}`}>
      <h3 className={styles.h4}>Installed plugins</h3>

      {view.plugins.length > 0 ? (
        <div className={styles.pluginList}>
          {view.plugins.map((p) => {
            const [name, marketplace] = p.id.split('@')
            return (
              <div key={p.id} className={styles.pluginRow}>
                <div className={styles.pluginInfo}>
                  <span className={styles.pluginName}>{name}</span>
                  {marketplace && <span className={styles.pluginChip}>@{marketplace}</span>}
                  {p.version && p.version !== 'unknown' && (
                    <span className={styles.pluginChip}>{p.version.slice(0, 12)}</span>
                  )}
                  <span className={cx(styles.pluginChip, p.enabled ? styles.pluginOn : styles.pluginOff)}>
                    {p.enabled ? 'enabled' : 'disabled'}
                  </span>
                </div>
                <div className={styles.pluginActs}>
                  <button
                    className={styles.rowBtn}
                    disabled={busy}
                    title={p.enabled ? 'Keep it installed but stop loading it' : 'Load it again'}
                    onClick={() => void act(() => api.setPluginEnabled(p.id, !p.enabled))}
                  >
                    {p.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button
                    className={cx(styles.rowBtn, styles.rowBtnDanger)}
                    disabled={busy}
                    onClick={() => void act(() => api.uninstallPlugin(p.id))}
                  >
                    Uninstall
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      ) : (
        <p className={styles.hint}>None installed yet — add a marketplace below, then install from it.</p>
      )}

      <p
        className={styles.hint}
        title="Runs load plugins only on the Claude backend. An agent with no selection runs with the CLI's own defaults."
      >
        Which plugins a run uses is chosen per agent, on the agent's inspector. ⓘ
      </p>

      <h3 className={styles.h4}>Install from marketplaces</h3>
      <div className={styles.installRow}>
        <input
          value={search}
          placeholder={catalog ? `Search ${catalog.length} plugins…` : 'Loading the catalog…'}
          aria-label="Search available plugins"
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>
      {results.length > 0 && (
        <div className={styles.pluginList}>
          {results.map((p) => {
            const installed = installedIds.has(p.id)
            return (
              <div key={p.id} className={styles.pluginRow}>
                <div className={styles.pluginMain}>
                  <div className={styles.pluginInfo}>
                    <span className={styles.pluginName}>{p.name}</span>
                    {p.marketplace && <span className={styles.pluginChip}>@{p.marketplace}</span>}
                    {(p.installCount ?? 0) > 0 && (
                      <span className={styles.pluginChip}>{compact(p.installCount ?? 0)} installs</span>
                    )}
                    {installed && (
                      <span className={cx(styles.pluginChip, styles.pluginOn)}>installed</span>
                    )}
                  </div>
                  {p.description && (
                    <div className={styles.pluginDesc} title={p.description}>
                      {p.description}
                    </div>
                  )}
                </div>
                {!installed && (
                  <div className={styles.pluginActs}>
                    <button
                      className={styles.rowBtn}
                      disabled={busy}
                      onClick={() => void act(() => api.installPlugin(p.id))}
                    >
                      Install
                    </button>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
      {catalog && catalog.length === 0 && (
        <p className={styles.hint}>The configured marketplaces offer nothing — add one below.</p>
      )}
      {catalog && catalog.length > 0 && results.length === 0 && (
        <p className={styles.hint}>Nothing matches “{search.trim()}”.</p>
      )}

      <h3 className={styles.h4}>Marketplaces</h3>

      {view.marketplaces.length > 0 ? (
        <div className={styles.pluginList}>
          {view.marketplaces.map((m) => (
            <div key={m.name} className={styles.pluginRow}>
              <div className={styles.pluginInfo}>
                <span className={styles.pluginName}>{m.name}</span>
                {m.source && <span className={styles.pluginChip}>{m.source}</span>}
              </div>
              <div className={styles.pluginActs}>
                <button
                  className={cx(styles.rowBtn, styles.rowBtnDanger)}
                  disabled={busy}
                  onClick={() => void act(() => api.removePluginMarketplace(m.name))}
                >
                  Remove
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <p className={styles.hint}>No marketplaces yet — add one to install plugins from it.</p>
      )}

      <div className={styles.installRow}>
        <input
          value={marketSource}
          placeholder="owner/repo, URL or path"
          aria-label="Marketplace to add"
          onChange={(e) => setMarketSource(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && marketSource.trim() && !busy)
              void act(() => api.addPluginMarketplace(marketSource.trim()), () => setMarketSource(''))
          }}
        />
        <button
          className={styles.saveBtn}
          disabled={busy || !marketSource.trim()}
          onClick={() => void act(() => api.addPluginMarketplace(marketSource.trim()), () => setMarketSource(''))}
        >
          Add marketplace
        </button>
      </div>

      {status && <p className={styles.status} role="status">{status}</p>}
    </div>
  )
}
