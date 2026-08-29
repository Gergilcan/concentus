/**
 * Where a paid checkout becomes a license. Stripe calls this with the session once it is paid;
 * the function checks the call really came from Stripe, mints a team license for what the
 * session's metadata says was bought, emails it, and records it in the ledger — once per
 * session, however many times Stripe delivers the event.
 *
 * Every refusal is deliberate about its status code, because the code is what Stripe acts on:
 * 2xx and it stops; anything else and it retries for up to three days. So an event this endpoint
 * does not act on is a 200 ("received, ignored"), a session that can never be fulfilled is a 200
 * with the reason logged (a retry would not change the metadata), and only one outcome is a 500:
 * the license was minted but Resend did not take the email — the retry is then exactly what
 * should send it.
 */
import { openLedger, payloadOf } from './_lib/ledger.mjs'
import { MAX_TEAM_SEATS, parseSeats, parseTerm } from './_lib/pricing.mjs'
import { sendEmail } from './_lib/resend.mjs'
import { verifyStripeSignature } from './_lib/stripe.mjs'
import { expiryForTerm, mintTeamLicense, purchaseEmail } from './_lib/team-license.mjs'
import { looksLikeEmail, missingEnv, readRawBody } from './_lib/http.mjs'

// The signature is over the bytes Stripe sent; Vercel's parser would hand us an object instead.
export const config = { api: { bodyParser: false } }

const REQUIRED_ENV = ['STRIPE_WEBHOOK_SECRET', 'TEAM_SIGNING_KEY', 'RESEND_API_KEY', 'LICENSE_FROM']

// `completed` arrives paid for cards; for delayed methods (SEPA, bank transfer) it arrives
// unpaid and `async_payment_succeeded` follows days later — both carry the same session object,
// and only the paid one mints.
const FULFILLING_EVENTS = new Set(['checkout.session.completed', 'checkout.session.async_payment_succeeded'])

/** What a completed session says was bought, or the reason it cannot be fulfilled. */
export function orderFromSession(session) {
  const meta = session?.metadata ?? {}
  const seats = parseSeats(meta.seats)
  const term = parseTerm(meta.term)
  const email = [meta.email, session?.customer_details?.email, session?.customer_email].find(looksLikeEmail)
  if (!seats) return { error: `metadata.seats is not 1 to ${MAX_TEAM_SEATS}: ${meta.seats}` }
  if (!term) return { error: `metadata.term is not monthly or annual: ${meta.term}` }
  if (!email) return { error: 'the session carries no email address' }
  // The name Stripe collected at checkout is the licensee — it is what the Settings screen will
  // print as "Licensed to". Absent (a session without a name field), the address stands in.
  const licensee = String(session?.customer_details?.name ?? '').trim() || email
  return { seats, term, email, licensee }
}

/**
 * The core: decides, mints, records, sends. Network-free except for Resend, with the ledger and
 * the clock injectable — which is how the tests prove idempotency without a database. Returns an
 * outcome; the handler maps it to a status code.
 */
export async function fulfil(event, { ledger = null, now = new Date(), signingKey = process.env.TEAM_SIGNING_KEY } = {}) {
  if (!FULFILLING_EVENTS.has(event?.type)) return { outcome: 'ignored', reason: `event type ${event?.type}` }
  const session = event.data?.object ?? {}
  if (session.payment_status !== 'paid') return { outcome: 'ignored', reason: `payment_status ${session.payment_status}` }
  if (!session.id) return { outcome: 'ignored', reason: 'no session id' }
  const order = orderFromSession(session)
  if (order.error) {
    console.error('checkout session cannot be fulfilled', session.id, order.error)
    return { outcome: 'ignored', reason: order.error }
  }

  const expires = expiryForTerm(now, order.term)
  const token = mintTeamLicense(
    { licensee: order.licensee, email: order.email, seats: order.seats, expires },
    signingKey, now,
  )

  // Idempotency, against the ledger's unique rule on the session id. Stripe retries anything not
  // answered 2xx, and may deliver an event twice regardless — so a second call for the same
  // session must not send a second license. Unless the first never went out: a row still
  // pending_send means Resend failed last time, and this retry takes the row over and sends.
  let remembered = false
  if (ledger) {
    try {
      const fresh = await ledger.recordPending(token, { source: 'stripe-webhook', kind: 'purchase', stripeSession: session.id })
      remembered = true
      if (!fresh) {
        if (await ledger.stripeSessionStatus(session.id) === 'sent') return { outcome: 'duplicate' }
        await ledger.takeOverStripeRow(session.id, token)
      }
    } catch (err) {
      // Best-effort as everywhere: a ledger outage must not stop a paid buyer's license. The
      // cost is that a duplicate delivery during the outage sends twice — two emails, one buyer.
      console.error('ledger failed for session', session.id, err)
      remembered = false
    }
  } else {
    console.error('no ledger: session', session.id, 'fulfilled without an idempotency record')
  }

  const sent = await sendEmail({
    to: order.email,
    ...purchaseEmail({ licensee: order.licensee, token, seats: order.seats, term: order.term, expires }),
  })
  if (!sent.ok) return { outcome: 'send-failed' }
  if (remembered) {
    try {
      await ledger.markSent(payloadOf(token).id)
    } catch (err) {
      console.error('ledger sent-update failed for session', session.id, err)
    }
  }
  return { outcome: 'sent', email: order.email, seats: order.seats, expires }
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).end()
  const missing = missingEnv(REQUIRED_ENV)
  if (missing.length) {
    return res.status(503).json({ error: `Team licensing is not configured: ${missing.join(', ')} missing.` })
  }
  const raw = await readRawBody(req)
  if (!verifyStripeSignature(raw, req.headers['stripe-signature'], process.env.STRIPE_WEBHOOK_SECRET)) {
    return res.status(400).json({ error: 'The Stripe signature does not verify.' })
  }
  let event
  try {
    event = JSON.parse(raw)
  } catch {
    return res.status(400).json({ error: 'The body is not JSON.' })
  }
  const result = await fulfil(event, { ledger: await openLedger() })
  if (result.outcome === 'send-failed') {
    return res.status(500).json({ error: 'The license email could not be sent; retry.' })
  }
  return res.status(200).json({ received: true, outcome: result.outcome, ...(result.reason ? { reason: result.reason } : {}) })
}
