import { useTranslation } from 'react-i18next'
import { cx } from '../utils/cx.ts'
import type { DashboardStats } from './flowsDashboard.ts'
import { money } from './flowFormat.ts'
import styles from './flows.module.scss'

/**
 * The row of headline numbers atop the dashboard — once there are numbers.
 *
 * On an installation that has never run anything, four of the five tiles report zero, a dash and
 * a dollar sign: the most prominent thing on the first screen, carrying one fact. So before the
 * first run it collapses to that one fact in a line, and the space goes to the flows.
 */
export function FlowsKpis({ stats }: { stats: DashboardStats }) {
  const { t } = useTranslation()
  if (stats.executions === 0 && stats.active === 0) {
    return (
      <div className={styles.kpisQuiet}>
        {stats.flows === 1
          ? t('{{n}} flow · nothing has run yet', { n: stats.flows })
          : t('{{n}} flows · nothing has run yet', { n: stats.flows })}
      </div>
    )
  }
  return (
    <div className={styles.kpis}>
      <Kpi label={t('Flows')} value={String(stats.flows)} />
      <Kpi label={t('Executions')} value={String(stats.executions)} />
      <Kpi
        label={t('Success rate')}
        value={stats.success === null ? '—' : `${stats.success}%`}
        tone={stats.success !== null && stats.success < 70 ? 'warn' : 'ok'}
      />
      <Kpi label={t('Running now')} value={String(stats.active)} tone={stats.active ? 'active' : undefined} />
      <Kpi label={t('Est. cost')} value={money(stats.cost)} />
    </div>
  )
}

function Kpi({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className={styles.kpi}>
      <div className={styles.kpiLabel}>{label}</div>
      <div className={cx(styles.kpiValue, tone && styles['kpi_' + tone])}>{value}</div>
    </div>
  )
}
