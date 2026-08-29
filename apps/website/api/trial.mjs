/**
 * The 14-day trial: a team license that expires in two weeks, from a form, with no card. It is
 * minted with the team key and carries `trial: true`, which is the only thing that separates it
 * from a bought one — the app's gates treat it as a team license, and the Settings screen counts
 * its days down. After it ends, the same 14-day grace as every paid license, then one seat.
 *
 * Same posture as the free issuer: the answer is one generic line for every input, so the
 * endpoint confirms nothing about any address; delivery IS the verification. One trial per
 * address, carried by the ledger's unique rule — a second request gets the same line and no
 * email, which is what "one" means without saying "you already had one" to whoever asked.
 */
import { openLedger, payloadOf } from './_lib/ledger.mjs'
import { parseSeats } from './_lib/pricing.mjs'
import { sendEmail } from './_lib/resend.mjs'
import { TRIAL_DAYS, addDaysUtc, isoDate, mintTeamLicense, trialEmail } from './_lib/team-license.mjs'
import { clientIp, looksLikeEmail, missingEnv, readBody, requestCounter } from './_lib/http.mjs'

const requests = requestCounter(10)
const OK = { message: 'If that address is valid, your trial license is on its way.' }
const REQUIRED_ENV = ['TEAM_SIGNING_KEY', 'RESEND_API_KEY', 'LICENSE_FROM']
const CONTACT = 'gila791@hotmail.com'

/**
 * The core: TRIAL_DAYS from the clock, the seats asked for, the company (or the person) as the
 * licensee. Ledger and clock injectable, so one-per-address is provable without a database.
 */
export async function issueTrial({ name, company, email, seats }, {
  ledger = null, now = new Date(), signingKey = process.env.TEAM_SIGNING_KEY,
} = {}) {
  const expires = isoDate(addDaysUtc(now, TRIAL_DAYS))
  const licensee = company || name
  const token = mintTeamLicense({ licensee, email, seats, expires, trial: true }, signingKey, now)

  let remembered = false
  if (ledger) {
    try {
      const fresh = await ledger.recordPending(token, { source: 'trial-form', kind: 'trial' })
      remembered = true
      if (!fresh) return { outcome: 'already-had-one' }
    } catch (err) {
      // Best-effort as everywhere: a ledger outage must not stop a trial, it only forgets the rule
      // for this one request.
      console.error('ledger failed for trial', err)
      remembered = false
    }
  } else {
    console.error('no ledger: trial for', email, 'issued without the one-per-address rule')
  }

  const sent = await sendEmail({ to: email, ...trialEmail({ licensee, token, seats, expires }) })
  if (!sent.ok) return { outcome: 'send-failed' }
  if (remembered) {
    try {
      await ledger.markSent(payloadOf(token).id)
    } catch (err) {
      console.error('ledger sent-update failed for trial', err)
    }
  }
  return { outcome: 'sent', expires, seats, licensee }
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).end()
  const missing = missingEnv(REQUIRED_ENV)
  if (missing.length) {
    // Honest rather than generic: with no signing key there is no trial to be "on its way".
    return res.status(503).json({ error: `Trials are not open yet (${missing.join(', ')} missing) — write to ${CONTACT}.` })
  }
  const body = readBody(req)
  // The honeypot is `website` here, not `company` as on the free form: company is a real field
  // on a trial, and a bot that fills every field it finds fills this one too.
  if (body.website) return res.status(200).json(OK)
  const name = String(body.name ?? '').trim()
  const company = String(body.company ?? '').trim()
  // Lower-cased so the ledger's one-per-address rule sees Jane@ and jane@ as the one person they are.
  const email = String(body.email ?? '').trim().toLowerCase()
  const seats = parseSeats(body.seats)
  if (!looksLikeEmail(email) || !name || name.length > 200 || company.length > 200 || !seats) {
    return res.status(200).json(OK)
  }
  if (requests.exceeded(clientIp(req), email)) return res.status(200).json(OK)

  await issueTrial({ name, company, email, seats }, { ledger: await openLedger() })
  return res.status(200).json(OK)
}
