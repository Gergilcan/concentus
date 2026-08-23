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
    if (!sent.ok) console.error('resend answered', sent.status, await sent.text())
  } catch (err) {
    console.error('resend send failed', err)
  }
  return res.status(200).json(OK)
}
