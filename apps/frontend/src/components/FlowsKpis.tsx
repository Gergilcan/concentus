import { cx } from '../utils/cx.ts'
import type { DashboardStats } from './flowsDashboard.ts'
import { money } from './flowFormat.ts'
import styles from './flows.module.scss'

/** The row of headline numbers (flow/run counts, success rate, cost) atop the flows dashboard. */
export function FlowsKpis({ stats }: { stats: DashboardStats }) {
  return (
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
