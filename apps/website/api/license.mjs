/**
 * Issues the free individual license: sign, email, tell nothing. The response is identical for
 * every input so the endpoint confirms no address; delivery to the inbox IS the verification.
 * Abuse posture for launch volume: a honeypot field plus a per-instance rate cap — honest and
 * cheap; revisit if the form ever meets real abuse.
 */
import { signLicense } from './_lib/license-format.mjs'

// ip -> count and normalized-email -> count, this instance; serverless instances recycle, and
// that is fine. Two independent counters, same threshold: an attacker rotating IPs against one
// address is still capped by the email counter, and one behind a shared IP (an office, a NAT)
// hammering many addresses is still capped by the IP counter.
const seenByIp = new Map()
const seenByEmail = new Map()
const OK = { message: 'If that address is valid, your license is on its way.' }

// Vercel parses req.body for us when the request arrives as application/json, but a form posted
// with the wrong (or missing) content-type header hands back the raw string instead — or nothing
// at all. Since every reply here is the same OK message regardless of what was sent, a body we
// cannot make sense of is just another way to end up with no name/email, not a reason to 500.
function readBody(req) {
  try {
    const body = req.body
    if (body == null) return {}
    if (typeof body === 'string') return body.trim() ? JSON.parse(body) : {}
    return body
  } catch {
    return {}
  }
}

/**
 * The analytics ledger: every issued license lands as a row in Neon, wired through Vercel's Neon
 * integration (which is what puts DATABASE_URL in this function's environment). Best-effort by
 * design — the license and the generic answer must survive the analytics database being down,
 * missing, or simply not set up yet, so every failure path here ends in console.error and
 * nothing else. The table is created on first use: zero-ops beats a migration pipeline while the
 * schema is one table.
 */
let tableReady = null
async function ensureTable(sql) {
  // The alter is how `status` reaches a table created by an earlier deploy — `create table if
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
      status text not null default 'pending_send',
      sent_at timestamptz,
      created_at timestamptz not null default now()
    )`
    await sql`alter table license_requests add column if not exists status text not null default 'pending_send'`
    await sql`alter table license_requests add column if not exists sent_at timestamptz`
  })()
  await tableReady
}

/**
 * Records the issue BEFORE the send is attempted, as `pending_send` — so a license that was
 * minted but never reached an inbox is a visible row, not a mystery. Returns a handle for
 * markSent, or null when there is no database (or it failed — best-effort as ever: the license
 * and the generic answer survive the analytics database in any state, with console.error as the
 * only trace).
 */
async function recordPending(token) {
  const url = process.env.DATABASE_URL ?? process.env.POSTGRES_URL
  if (!url) return null
  try {
    const { neon } = await import('@neondatabase/serverless')
    const sql = neon(url)
    await ensureTable(sql)
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
    await sql`insert into license_requests (id, tier, licensee, email, issued, expires, source, status)
              values (${payload.id}, ${payload.tier}, ${payload.licensee}, ${payload.email},
                      ${payload.issued}, ${payload.expires}, 'web-form', 'pending_send')
              on conflict (id) do nothing`
    return { sql, id: payload.id }
  } catch (err) {
    // A rejected creation promise must not stay cached, or one bad moment wedges every insert
    // this instance ever tries again.
    tableReady = null
    console.error('license analytics insert failed', err)
    return null
  }
}

/** Flips the row to `sent` once Resend accepted the email. Rows that stay `pending_send` ARE the
 * report: minted, never delivered — the ones worth retrying by hand. */
async function markSent(handle) {
  if (!handle) return
  try {
    await handle.sql`update license_requests set status = 'sent', sent_at = now() where id = ${handle.id}`
  } catch (err) {
    console.error('license analytics sent-update failed', err)
  }
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).end()
  const { name, email, company } = readBody(req)
  if (company) return res.status(200).json(OK) // honeypot: swallow silently
  if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || !name || String(name).length > 200) {
    return res.status(200).json(OK)
  }
  const ip = req.headers['x-forwarded-for']?.split(',')[0] ?? 'unknown'
  const normalizedEmail = String(email).trim().toLowerCase()
  const ipCount = (seenByIp.get(ip) ?? 0) + 1
  seenByIp.set(ip, ipCount)
  const emailCount = (seenByEmail.get(normalizedEmail) ?? 0) + 1
  seenByEmail.set(normalizedEmail, emailCount)
  if (ipCount > 10 || emailCount > 10) return res.status(200).json(OK)

  const token = signLicense(
    { tier: 'individual', licensee: String(name).trim(), email: String(email).trim(),
      issued: new Date().toISOString().slice(0, 10), expires: null },
    process.env.LICENSE_KEY_INDIVIDUAL,
  )
  const pending = await recordPending(token)
  // Never let a Resend outage (or a missing env var) turn into a 500: the response is identical
  // either way, so a transport failure (DNS, connection refused, timeout — fetch() rejects rather
  // than resolving) is caught right alongside the non-ok-response case already logged below.
  try {
    const sent = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${process.env.RESEND_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        from: process.env.LICENSE_FROM,
        to: [email],
        subject: 'Your Concentus license',
        text: `Hi ${name},\n\nYour free individual Concentus license:\n\n${token}\n\n`
            + 'Paste it in Resources → Settings → License if you like — the app works without it; '
            + 'this one just has your name on it.\n\nEnterprise (shared database, teams, SSO): '
            + 'https://www.concentus-ai.com/#license\n',
      }),
    })
    if (sent.ok) {
      await markSent(pending)
    } else {
      console.error('resend answered', sent.status, await sent.text())
    }
  } catch (err) {
    console.error('resend send failed', err)
  }
  return res.status(200).json(OK)
}
