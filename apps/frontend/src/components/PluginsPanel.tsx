import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { PluginsView } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import styles from './resources.module.scss'

/**
 * Claude Code plugins: what is installed on this machine, plus install/uninstall and the
 * marketplaces they come from. Thin over the `claude plugin` CLI — the same store the terminal
 * sees. Which plugins a RUN loads is chosen per agent, on the agent's inspector.
 */
export function PluginsPanel({ pushError }: { pushError: (m: string) => void }) {
  const [view, setView] = useState<PluginsView | null>(null)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<string | null>(null)
  const [installId, setInstallId] = useState('')
  const [marketSource, setMarketSource] = useState('')

  const load = useCallback(() => {
    api
      .listPlugins()
      .then(setView)
      .catch((e) => pushError(errMessage(e)))
  }, [pushError])

  useEffect(load, [load])

  const act = async (run: () => Promise<{ status: string }>) => {
    setBusy(true)
    setStatus(null)
    try {
      setStatus((await run()).status)
      load()
    } catch (e) {
      pushError(errMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!view) return <div className={styles.muted}>Loading…</div>

  return (
    <div className={`${styles.crudForm} ${styles.lone}`} style={{ maxWidth: '46rem' }}>
      <h3 className={styles.h4}>Installed plugins</h3>
      {view.plugins.length === 0 && (
        <p className={styles.hint}>
          None yet — add a marketplace below, then install from it.
        </p>
      )}
      {view.plugins.map((p) => (
        <div key={p.id} className={styles.crudItem}>
          <span>
            <b>{p.id}</b>
            {p.version && <span className={styles.muted}> · {p.version}</span>}
            {!p.enabled && <span className={styles.muted}> · disabled</span>}
          </span>
          <button
            className={styles.delBtn}
            disabled={busy}
            onClick={() => void act(() => api.uninstallPlugin(p.id))}
          >
            Uninstall
          </button>
        </div>
      ))}
      <div className={styles.crudActions}>
        <input
          value={installId}
          placeholder="plugin or plugin@marketplace"
          onChange={(e) => setInstallId(e.target.value)}
        />
        <button
          className={styles.saveBtn}
          disabled={busy || !installId.trim()}
          onClick={() => void act(() => api.installPlugin(installId.trim())).then(() => setInstallId(''))}
        >
          Install
        </button>
      </div>
      <p
        className={styles.hint}
        title="Runs load plugins only on the Claude backend. Each agent picks its own set in its inspector; an agent with no selection runs with the CLI's own defaults."
      >
        Which plugins a run uses is chosen per agent, on the agent's inspector. ⓘ
      </p>

      <h3 className={styles.h4}>Marketplaces</h3>
      {view.marketplaces.map((m) => (
        <div key={m.name} className={styles.crudItem}>
          <span>
            <b>{m.name}</b>
            {m.source && <span className={styles.muted}> · {m.source}</span>}
          </span>
          <button
            className={styles.delBtn}
            disabled={busy}
            onClick={() => void act(() => api.removePluginMarketplace(m.name))}
          >
            Remove
          </button>
        </div>
      ))}
      <div className={styles.crudActions}>
        <input
          value={marketSource}
          placeholder="owner/repo, URL or path"
          onChange={(e) => setMarketSource(e.target.value)}
        />
        <button
          className={styles.saveBtn}
          disabled={busy || !marketSource.trim()}
          onClick={() => void act(() => api.addPluginMarketplace(marketSource.trim())).then(() => setMarketSource(''))}
        >
          Add marketplace
        </button>
      </div>

      {status && <p className={styles.status}>{status}</p>}
    </div>
  )
}
