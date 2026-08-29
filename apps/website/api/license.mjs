/**
 * Issues the free individual license: sign, email, tell nothing. The response is identical for
 * every input so the endpoint confirms no address; delivery to the inbox IS the verification.
 * Abuse posture for launch volume: a honeypot field plus a per-instance rate cap — honest and
 * cheap; revisit if the form ever meets real abuse.
 */
import { signLicense } from './_lib/license-format.mjs'
import { openLedger } from './_lib/ledger.mjs'
import { sendEmail } from './_lib/resend.mjs'
import { clientIp, looksLikeEmail, readBody } from './_lib/http.mjs'

// ip -> count and normalized-email -> count, this instance; serverless instances recycle, and
// that is fine. Two independent counters, same threshold: an attacker rotating IPs against one
// address is still capped by the email counter, and one behind a shared IP (an office, a NAT)
// hammering many addresses is still capped by the IP counter.
const seenByIp = new Map()
const seenByEmail = new Map()
const OK = { message: 'If that address is valid, your license is on its way.' }

/**
 * Records the issue BEFORE the send is attempted, as `pending_send` — so a license that was
 * minted but never reached an inbox is a visible row, not a mystery. Returns the ledger to mark
 * sent on, or null when there is none (or it failed — best-effort as ever: the license and the
 * generic answer survive the analytics database in any state, with console.error as the only
 * trace).
 */
async function recordPending(token) {
  const ledger = await openLedger()
  if (!ledger) return null
  try {
    await ledger.recordPending(token, { source: 'web-form', kind: 'free' })
    return ledger
  } catch (err) {
    console.error('license analytics insert failed', err)
    return null
  }
}

/** Rows that stay `pending_send` ARE the report: minted, never delivered — the ones worth retrying by hand. */
async function markSent(ledger, token) {
  if (!ledger) return
  try {
    await ledger.markSent(JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()).id)
  } catch (err) {
    console.error('license analytics sent-update failed', err)
  }
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).end()
  const { name, email, company } = readBody(req)
  if (company) return res.status(200).json(OK) // honeypot: swallow silently
  if (!looksLikeEmail(email) || !name || String(name).length > 200) {
    return res.status(200).json(OK)
  }
  const ip = clientIp(req)
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
  const ledger = await recordPending(token)
  // Never let a Resend outage (or a missing env var) turn into a 500: the response is identical
  // either way, and sendEmail has already logged whatever went wrong.
  const sent = await sendEmail({
    to: email,
    subject: 'Your Concentus license',
    text: `Hi ${name},\n\nYour free individual Concentus license:\n\n${token}\n\n`
        + 'Paste it in Resources → Settings → License if you like — the app works without it; '
        + 'this one just has your name on it.\n\nTeams (shared database, members, SSO): '
        + 'https://www.concentus-ai.com/#license\n',
  })
  if (sent.ok) await markSent(ledger, token)
  return res.status(200).json(OK)
}
