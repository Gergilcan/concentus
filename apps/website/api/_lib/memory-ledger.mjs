/**
 * The in-memory twin of the Neon ledger, for tests: the same four methods and the same two
 * unique rules (one license per Stripe session, one trial per address), so an issuer's
 * idempotency can be proven without a database. Not imported by any function.
 */
import { payloadOf } from './ledger.mjs'

export function memoryLedger() {
  const rows = new Map() // license id -> row
  const all = () => [...rows.values()]
  return {
    rows,
    async recordPending(token, { source, kind, stripeSession = null }) {
      const p = payloadOf(token)
      if (stripeSession && all().some((r) => r.stripeSession === stripeSession)) return false
      if (kind === 'trial' && all().some((r) => r.kind === 'trial' && r.email === p.email)) return false
      rows.set(p.id, { ...p, source, kind, stripeSession, status: 'pending_send' })
      return true
    },
    async markSent(id) {
      rows.get(id).status = 'sent'
    },
    async stripeSessionStatus(sessionId) {
      return all().find((r) => r.stripeSession === sessionId)?.status ?? null
    },
    async takeOverStripeRow(sessionId, token) {
      const p = payloadOf(token)
      // Find first, then mutate: re-inserting while iterating a Map revisits the new entry.
      const pending = all().find((r) => r.stripeSession === sessionId && r.status === 'pending_send')
      if (!pending) return
      rows.delete(pending.id)
      rows.set(p.id, { ...pending, ...p, status: 'pending_send' })
    },
  }
}
