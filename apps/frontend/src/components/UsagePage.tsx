import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client.ts'
import type { UsageAllowance, UsageDay, UsageSummary } from '../api/types.ts'
import { errMessage } from '../utils/errMessage.ts'
import { money } from '../utils/format.ts'
import styles from './usage.module.scss'

const REFRESH_MS = 60_000

/** The weekday letter a person reads a chart by. Locale's own, so it is not English-only. */
function weekday(date: string): string {
  return new Date(date + 'T12:00:00').toLocaleDateString(undefined, { weekday: 'short' })
}

/**
 * Seven days beside each other, today last and emphasised.
 *
 * Three totals say how much; only the days say whether today is unusual, which is the question a
 * total raises. Bars rather than a line: these are seven discrete amounts, not a continuous
 * quantity sampled seven times, and a line drawn between them implies values in between that
 * nobody measured.
 */
function SevenDays({ days }: { days: UsageDay[] }) {
  const { t } = useTranslation()
  const peak = Math.max(...days.map((d) => d.estimatedUsd), 0.01)
  return (
    <>
      <h3 className={styles.h3}>{t('Day by day')}</h3>
      <div className={styles.chart}>
        {days.map((d, i) => {
          const last = i === days.length - 1
          const height = Math.max(2, Math.round((d.estimatedUsd / peak) * 100))
          return (
            <div key={d.date} className={styles.bar} title={`${d.date} — ${t('{{n}} message(s)', { n: d.messages })}`}>
              <div className={styles.barValue}>{d.estimatedUsd > 0 ? money(d.estimatedUsd) : ''}</div>
              <div className={styles.barTrack}>
                <div
                  className={last ? `${styles.barFill} ${styles.barToday}` : styles.barFill}
                  style={{ height: `${height}%` }}
                />
              </div>
              <div className={styles.barLabel}>{last ? t('Today') : weekday(d.date)}</div>
            </div>
          )
        })}
      </div>
    </>
  )
}

/**
 * The allowance meter: what this machine's runs used of the plan's weekly allowance for
 * non-interactive use. A bar, because the question is "how close", and a number under it,
 * because a bar cannot be quoted. Runs are the numerator — headless use that never went
 * through this app (a script, a GitHub Action) counts on Anthropic's side and not here, so
 * the figure is a floor and the label says so.
 */
function Allowance({ a }: { a: UsageAllowance }) {
  const { t } = useTranslation()
  const width = Math.min(100, a.percent)
  const tone = a.state === 'exhausted' ? styles.meterOver : a.state === 'warn' ? styles.meterWarn : styles.meterOk
  return (
    <div className={styles.meter} title={t("Runs on this machine's subscription in the last 7 days, against the allowance set in Settings → Usage. Non-interactive use that never went through Concentus counts on Anthropic's side but not here, so this is a floor. Rolling 7 days, which is conservative against a plan that resets on a fixed day.")}>
      <div className={styles.meterHead}>
        <span className={styles.tileLabel}>{t('Weekly allowance for runs')} ⓘ</span>
        <span className={styles.meterPct}>{a.percent}%</span>
      </div>
      <div className={styles.meterTrack}>
        <div className={`${styles.meterFill} ${tone}`} style={{ width: `${width}%` }} />
      </div>
      <div className={styles.tileDetail}>
        {a.state === 'exhausted'
          ? t('{{used}} of {{allowance}} — spent. Runs on the subscription wait for the window to reset, or fall back if the flow says so.', { used: money(a.runsUsd), allowance: money(a.allowanceUsd) })
          : t('{{used}} of {{allowance}} — {{left}} left · whole machine: {{machine}}', { used: money(a.runsUsd), allowance: money(a.allowanceUsd), left: money(a.remainingUsd), machine: money(a.machineUsd) })}
      </div>
    </div>
  )
}

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
  const { t } = useTranslation()
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
  if (!data) return <div className={styles.page}><p className={styles.note}>{t('Reading transcripts…')}</p></div>
  if (!data.available) {
    return (
      <div className={styles.page}>
        <p className={styles.note}>
          {t('No Claude Code transcripts found on this machine (~/.claude/projects). Usage appears here once the CLI has run at least one session.')}
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
      <h2 className={styles.h2}>{t('Claude usage on this machine')}</h2>
      <p
        className={styles.note}
        title={t("Measured from Claude Code's own transcripts (~/.claude/projects) — every session on this machine, not only Concentus runs. The $ figure prices the tokens at API rates: on a subscription it is equivalent usage, not a bill. The official quota and extra-usage credit balance have no API; only /usage inside the CLI shows them.")}
      >
        {t("Measured from the CLI's transcripts, refreshed every minute · $ = API-equivalent value, not a bill · official quota & credits: run")}{' '}
        <code>/usage</code> {t('inside Claude Code')} ⓘ
      </p>

      <div className={styles.tiles}>
        {windows.map((w) => {
          const win = data.windows[w.key]
          return (
            <div key={w.key} className={styles.tile} title={t(w.hint)}>
              <div className={styles.tileLabel}>{t(w.label)}</div>
              <div className={styles.tileValue}>{money(win.estimatedUsd)}</div>
              <div className={styles.tileDetail}>
                {t('{{in}} in · {{out}} out · {{cache}} cache', {
                  in: tokens(win.inputTokens),
                  out: tokens(win.outputTokens),
                  cache: tokens(win.cacheReadTokens),
                })}
              </div>
              <div className={styles.tileDetail}>{t('{{n}} message(s)', { n: win.messages })}</div>
            </div>
          )
        })}
      </div>

      {data.allowance ? (
        <Allowance a={data.allowance} />
      ) : (
        <p className={styles.note}>
          {t('Set your plan\'s weekly allowance for non-interactive use under Settings → Usage, and this page shows how far the runs are from it.')}
        </p>
      )}

      {data.days && data.days.length > 0 && <SevenDays days={data.days} />}

      <h3 className={styles.h3}>{t('By model — last 7 days')}</h3>
      <div className={styles.tableWrap}>
        <table>
          <thead>
            <tr><th>{t('Model')}</th><th>{t('Input')}</th><th>{t('Output')}</th><th>{t('Cache read')}</th><th>{t('Cache write')}</th><th>{t('$ equiv.')}</th></tr>
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
