/**
 * Starts a Stripe Checkout for a team license: seats × the per-seat price for the term, as one
 * payment. The buyer leaves for Stripe's page; the license itself is minted by stripe-webhook.mjs
 * when Stripe reports the session paid, so nothing here can issue anything — a request that
 * never pays never reaches the signing key.
 *
 * One payment, not a subscription, on purpose: it mirrors the enterprise sale ("renewal: buy
 * again"), needs no Product or Price objects in the dashboard (the line item carries its own
 * ad-hoc price), and keeps the license format's "expires, then grace" the only lifecycle. A
 * subscription that re-mints on every invoice is a later step, not a different design.
 */
import { parseSeats, parseTerm, quote, readPricing } from './_lib/pricing.mjs'
import { createCheckoutSession } from './_lib/stripe.mjs'
import { looksLikeEmail, missingEnv, readBody } from './_lib/http.mjs'

const CONTACT = 'gila791@hotmail.com'
const NOT_CONFIGURED = `Team checkout is not open yet — write to ${CONTACT} and we will work out seats and term by hand.`

/** Where Stripe sends the buyer back. Overridable so a preview deployment lands on itself. */
export function siteUrl(env = process.env) {
  return String(env.SITE_URL || 'https://www.concentus-ai.com').replace(/\/+$/, '')
}

/**
 * The Checkout Session, as Stripe's API takes it. A pure function of the order and the pricing,
 * so the line-item arithmetic — the thing a buyer is actually charged — is testable without a
 * Stripe account. `metadata` is what the webhook reads back to know what was bought.
 */
export function checkoutSessionParams({ seats, term, email }, pricing, site = siteUrl()) {
  const q = quote({ seats, term }, pricing)
  const discount = Math.round(pricing.annualDiscount * 100)
  return {
    mode: 'payment',
    customer_email: email,
    line_items: [{
      quantity: seats,
      price_data: {
        currency: q.currency,
        unit_amount: q.unitAmountCents,
        product_data: {
          name: term === 'annual' ? 'Concentus Team seat — one year' : 'Concentus Team seat — one month',
          description: term === 'annual'
            ? `Per-seat team license for twelve months, ${discount}% under twelve monthly ones. Shared database, members up to your seats, SSO.`
            : 'Per-seat team license for one month. Shared database, members up to your seats, SSO.',
        },
      },
    }],
    metadata: { seats: String(seats), term, email },
    success_url: `${site}/?checkout=success#license`,
    cancel_url: `${site}/?checkout=cancelled#license`,
  }
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).end()
  const missing = missingEnv(['STRIPE_SECRET_KEY', 'TEAM_PRICE_MONTHLY_PER_SEAT'])
  const pricing = readPricing()
  if (missing.length || pricing.monthlyPerSeat == null) {
    return res.status(503).json({ error: NOT_CONFIGURED, missing })
  }
  const body = readBody(req)
  const seats = parseSeats(body.seats)
  const term = parseTerm(body.term)
  const email = String(body.email ?? '').trim()
  if (!seats || !term || !looksLikeEmail(email)) {
    return res.status(400).json({
      error: `Seats (1 to ${pricing.maxSeats}), a term (monthly or annual) and an email address are required.`,
    })
  }
  try {
    const session = await createCheckoutSession(
      checkoutSessionParams({ seats, term, email }, pricing),
      { secretKey: process.env.STRIPE_SECRET_KEY },
    )
    return res.status(200).json({ url: session.url })
  } catch (err) {
    // Stripe's message goes to the log, where the author reads it; the buyer gets told nothing
    // was charged, which is the one thing they need to know before trying again.
    console.error('stripe checkout session failed', err)
    return res.status(502).json({
      error: `Checkout could not start — nothing was charged. Try again in a minute, or write to ${CONTACT}.`,
    })
  }
}
