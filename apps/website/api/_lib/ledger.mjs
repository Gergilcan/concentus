/**
 * The ledger: every issued license lands as a row in Neon, wired through Vercel's Neon
 * integration (which is what puts DATABASE_URL in a function's environment). Best-effort by
 * design — a license and its answer must survive the database being down, missing, or simply
 * not set up yet, so every failure path here ends in console.error and a null ledger, and every
 * caller has a way to proceed without one. The table is created on first use: zero-ops beats a
 * migration pipeline while the schema is one table.
 *
 * It started as analytics and grew two rules, both carried by partial unique indexes rather than
 * by code that reads then writes: one license per Stripe checkout session (the webhook's
 * idempotency), one trial per address. `insert … on conflict do nothing` against them is the
 * whole story — no race between two instances answering the same retry at once.
 *
 * Columns: `source` is which door (web-form, stripe-webhook, trial-form); `kind` is what was
 * issued (free, purchase, trial); `status` moves from pending_send to sent once Resend accepted
 * the email, so rows that stay pending_send ARE the report of licenses minted but never delivered.
 */
let tableReady = null

async function ensureTable(sql) {
  // The alters are how new columns reach a table created by an earlier deploy — `create table if
  // not exists` never touches columns on a table that already exists.
  tableReady ??= (async () => {
    await sql`create table if not exists license_requests (
      id uuid primary key,
      tier text not null,
      licensee text not null,
      email text not null,
      issued date not null,
      expires date,
      source text not null,
      kind text not null default 'free',
      stripe_session text,
      status text not null default 'pending_send',
      sent_at timestamptz,
      created_at timestamptz not null default now()
    )`
    await sql`alter table license_requests add column if not exists status text not null default 'pending_send'`
    await sql`alter table license_requests add column if not exists sent_at timestamptz`
    await sql`alter table license_requests add column if not exists kind text not null default 'free'`
    await sql`alter table license_requests add column if not exists stripe_session text`
    await sql`create unique index if not exists license_requests_stripe_session_key
              on license_requests (stripe_session) where stripe_session is not null`
    await sql`create unique index if not exists license_requests_one_trial_per_email
              on license_requests (email) where kind = 'trial'`
  })()
  await tableReady
}

/** The payload a token carries, decoded — what the ledger stores about it. */
export function payloadOf(token) {
  return JSON.parse(Buffer.from(String(token).split('.')[1], 'base64url').toString())
}

/**
 * The ledger behind DATABASE_URL, or null when there is none (or it failed). Callers treat null
 * as "nothing can be remembered": the free issuer just skips the row; the trial and the webhook
 * lose their one-per rules for that request and say so in the log.
 */
export async function openLedger() {
  const url = process.env.DATABASE_URL ?? process.env.POSTGRES_URL
  if (!url) return null
  try {
    const { neon } = await import('@neondatabase/serverless')
    const sql = neon(url)
    await ensureTable(sql)
    return neonLedger(sql)
  } catch (err) {
    // A rejected creation promise must not stay cached, or one bad moment wedges every insert
    // this instance ever tries again.
    tableReady = null
    console.error('license ledger unavailable', err)
    return null
  }
}

/**
 * The four questions the issuers ask, over one Neon connection. An in-memory twin lives in the
 * tests; the shape here is the contract both honour.
 */
function neonLedger(sql) {
  return {
    /**
     * Records the issue BEFORE the send is attempted, as pending_send. Answers false when a
     * unique rule refused the row — that Stripe session, or that trial address, already has a
     * license — which is the caller's cue not to send another. Throws on anything else; callers
     * wrap it, because their fallbacks differ.
     */
    async recordPending(token, { source, kind, stripeSession = null }) {
      const p = payloadOf(token)
      const rows = await sql`insert into license_requests
                  (id, tier, licensee, email, issued, expires, source, kind, stripe_session, status)
                values (${p.id}, ${p.tier}, ${p.licensee}, ${p.email}, ${p.issued}, ${p.expires},
                        ${source}, ${kind}, ${stripeSession}, 'pending_send')
                on conflict do nothing
                returning id`
      return rows.length > 0
    },

    /** Flips the row to sent once Resend accepted the email. */
    async markSent(id) {
      await sql`update license_requests set status = 'sent', sent_at = now() where id = ${id}`
    },

    /** 'sent', 'pending_send', or null when no row carries that Stripe session. */
    async stripeSessionStatus(sessionId) {
      const rows = await sql`select status from license_requests where stripe_session = ${sessionId}`
      return rows[0]?.status ?? null
    },

    /**
     * A row still pending_send is a license that never reached its buyer (Resend was down when
     * Stripe first called). The retry takes that row over with a freshly minted token, so what is
     * on record is the license that was actually delivered — the old id, which nobody received,
     * is not worth keeping.
     */
    async takeOverStripeRow(sessionId, token) {
      const p = payloadOf(token)
      await sql`update license_requests
                set id = ${p.id}, licensee = ${p.licensee}, email = ${p.email},
                    issued = ${p.issued}, expires = ${p.expires}
                where stripe_session = ${sessionId} and status = 'pending_send'`
    },
  }
}
