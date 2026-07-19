import type { BackendFlow, RunSummary } from '../api/types.ts'

export type Sort = 'recent' | 'name' | 'runs'
export type Kind = 'ok' | 'fail' | 'active' | 'stopped'

export function kindOf(status: string): Kind {
  if (status === 'ERROR') return 'fail'
  // Stopped on purpose — neither a success nor a failure.
  if (status === 'TERMINATED') return 'stopped'
  if (status === 'RUNNING' || status === 'STARTING') return 'active'
  return 'ok'
}

export const KIND_LABEL: Record<Kind, string> = {
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
export function decided(r: RunSummary): boolean {
  const k = kindOf(r.status)
  return k === 'ok' || k === 'fail'
}

export function timeAgo(ts: number): string {
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (s < 45) return 'just now'
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  return d < 30 ? `${d}d ago` : new Date(ts).toLocaleDateString()
}

export function compact(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

export function money(usd: number): string {
  if (!usd) return '$0'
  return usd < 0.01 ? `<$0.01` : `$${usd.toFixed(2)}`
}

export function triggerOf(flow: BackendFlow): { label: string; tone: string; scheduled: boolean } {
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

export function countsOf(flow: BackendFlow) {
  let agents = 0
  let tools = 0
  for (const n of flow.nodes) {
    if (n.type === 'agent') agents++
    else if (n.type !== 'input') tools++
  }
  return { agents, tools }
}
