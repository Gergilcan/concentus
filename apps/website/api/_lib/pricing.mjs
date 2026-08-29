/**
 * What a team license costs, from one environment variable, and the arithmetic every other
 * surface (the pricing endpoint, the checkout line item, the page) must agree on.
 *
 * The price is a decision, made on 29 August 2026: 25 € per seat per month, or 20 € per seat per
 * month when a year is paid at once. That is the default below; TEAM_PRICE_MONTHLY_PER_SEAT in
 * Vercel overrides it (a number), and the value `off` takes the price down again — the page then
 * prints "Pricing to be announced" and checkout answers 503, so nothing false is printed or charged.
 */

/** Annual is a year for the price of 9.6 months: 25 a month becomes 20 — the pricing-page fact, as code. */
export const ANNUAL_DISCOUNT = 0.2

/** The decided monthly price per seat, in the currency below, when Vercel says nothing else. */
export const DEFAULT_MONTHLY_PER_SEAT = 25

/** The ceiling the backend verifier enforces on a team license; past it, enterprise. */
export const MAX_TEAM_SEATS = 10

export const TERMS = ['monthly', 'annual']

export function readPricing(env = process.env) {
  const raw = env.TEAM_PRICE_MONTHLY_PER_SEAT
  let monthlyPerSeat = DEFAULT_MONTHLY_PER_SEAT
  if (raw != null && String(raw).trim() !== '') {
    const text = String(raw).trim().toLowerCase()
    if (text === 'off' || text === 'none') {
      monthlyPerSeat = null
    } else {
      const n = Number(raw)
      // A price that is not a positive number is a typo in the dashboard, not a price: printing
      // "€NaN" or charging zero would both be worse than staying unannounced.
      if (Number.isFinite(n) && n > 0) monthlyPerSeat = n
      else {
        console.error('TEAM_PRICE_MONTHLY_PER_SEAT is not a positive number:', raw)
        monthlyPerSeat = null
      }
    }
  }
  return {
    monthlyPerSeat,
    currency: String(env.TEAM_PRICE_CURRENCY || 'eur').toLowerCase(),
    annualDiscount: ANNUAL_DISCOUNT,
    maxSeats: MAX_TEAM_SEATS,
  }
}

/** An integer in 1..MAX_TEAM_SEATS, or null — never a clamp, a buyer typing 12 should be told. */
export function parseSeats(value) {
  const n = typeof value === 'number' ? value : Number(String(value ?? '').trim())
  return Number.isInteger(n) && n >= 1 && n <= MAX_TEAM_SEATS ? n : null
}

export function parseTerm(value) {
  return TERMS.includes(value) ? value : null
}

/**
 * The line item, in integer cents, so the page and Stripe never disagree by a rounding: per-seat
 * price for the term, times seats. Annual is twelve months less the discount, rounded once at
 * the per-seat level — the total is then an exact multiple, as the invoice will show it.
 */
export function quote({ seats, term }, pricing) {
  if (pricing.monthlyPerSeat == null) throw new Error('no price configured')
  const monthlyCents = Math.round(pricing.monthlyPerSeat * 100)
  const unitAmountCents = term === 'annual'
    ? Math.round(monthlyCents * 12 * (1 - pricing.annualDiscount))
    : monthlyCents
  return {
    seats,
    term,
    currency: pricing.currency,
    unitAmountCents,
    totalCents: unitAmountCents * seats,
    months: term === 'annual' ? 12 : 1,
  }
}
