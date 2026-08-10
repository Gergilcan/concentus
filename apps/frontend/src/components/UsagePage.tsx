import { useEffect, useState } from 'react'
import { api } from '../api/client.ts'
import type { UsageSummary } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { money } from '../utils/format.ts'
import styles from './usage.module.scss'

const REFRESH_MS = 60_000

function tokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}

/**
 * What this machine's Claude login has consumed — measured, not asked.
 *
 * Read from Claude Code's own transcripts, refreshed automatically while the page is open.
 * Anthropic exposes no API for the subscription's official quota or credit balance (only /usage
 * inside the CLI shows those), so this page shows the thing that CAN be known exactly and says so.
 */
export function UsagePage() {
  const [data, setData] = useState<UsageSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    const tick = () => {
      if (document.visibilityState === 'hidden') return
      api
        .usageSummary()
        .then((d) => live && (setData(d), setError(null)))
        .catch((e) => live && setError(errMessage(e)))
    }
    tick()
    const t = setInterval(tick, REFRESH_MS)
    return () => {
      live = false
      clearInterval(t)
    }
  }, [])

  if (error) return <div className={styles.page}><p className={styles.note}>{error}</p></div>
  if (!data) return <div className={styles.page}><p className={styles.note}>Reading transcripts…</p></div>
  if (!data.available) {
    return (
      <div className={styles.page}>
        <p className={styles.note}>
          No Claude Code transcripts found on this machine (~/.claude/projects). Usage appears here
          once the CLI has run at least one session.
        </p>
      </div>
    )
  }

  const windows: { key: 'last5h' | 'today' | 'week'; label: string; hint: string }[] = [
    { key: 'last5h', label: 'Last 5 hours', hint: 'The rolling window your plan meters sessions in.' },
    { key: 'today', label: 'Today', hint: 'Since midnight, local time.' },
    { key: 'week', label: 'Last 7 days', hint: 'The window weekly plan limits think in.' },
  ]

  return (
    <div className={styles.page}>
      <h2 className={styles.h2}>Claude usage on this machine</h2>
      <p
        className={styles.note}
        title="Measured from Claude Code's own transcripts (~/.claude/projects) — every session on this machine, not only Concentus runs. The $ figure prices the tokens at API rates: on a subscription it is equivalent usage, not a bill. The official quota and extra-usage credit balance have no API; only /usage inside the CLI shows them."
      >
        Measured from the CLI's transcripts, refreshed every minute · $ = API-equivalent value, not
        a bill · official quota &amp; credits: run <code>/usage</code> inside Claude Code ⓘ
      </p>

      <div className={styles.tiles}>
        {windows.map((w) => {
          const win = data.windows[w.key]
          return (
            <div key={w.key} className={styles.tile} title={w.hint}>
              <div className={styles.tileLabel}>{w.label}</div>
              <div className={styles.tileValue}>{money(win.estimatedUsd)}</div>
              <div className={styles.tileDetail}>
                {tokens(win.inputTokens)} in · {tokens(win.outputTokens)} out ·{' '}
                {tokens(win.cacheReadTokens)} cache
              </div>
              <div className={styles.tileDetail}>{win.messages} message(s)</div>
            </div>
          )
        })}
      </div>

      <h3 className={styles.h3}>By model — last 7 days</h3>
      <div className={styles.tableWrap}>
        <table>
          <thead>
            <tr><th>Model</th><th>Input</th><th>Output</th><th>Cache read</th><th>Cache write</th><th>$ equiv.</th></tr>
          </thead>
          <tbody>
            {data.models.map((m) => (
              <tr key={m.model}>
                <td className={styles.model}>{m.model}</td>
                <td>{tokens(m.inputTokens)}</td>
                <td>{tokens(m.outputTokens)}</td>
                <td>{tokens(m.cacheReadTokens)}</td>
                <td>{tokens(m.cacheWriteTokens)}</td>
                <td>{money(m.estimatedUsd)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
