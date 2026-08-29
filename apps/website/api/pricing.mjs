/**
 * What the pricing card reads on load. The number comes from TEAM_PRICE_MONTHLY_PER_SEAT in
 * Vercel and from nowhere else — not this file, not the HTML — so the page can never print a
 * price the author did not set. Null means "not announced", and the card says so.
 */
import { readPricing } from './_lib/pricing.mjs'

export default function handler(req, res) {
  if (req.method !== 'GET') return res.status(405).end()
  // Short on the browser, longer at the edge: a price change should be visible within minutes,
  // and the page should not pay a function invocation per visitor for a value that changes yearly.
  res.setHeader('Cache-Control', 'public, max-age=60, s-maxage=300')
  return res.status(200).json(readPricing())
}
