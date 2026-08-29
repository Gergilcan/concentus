/**
 * The two things this site needs from Stripe, done against the REST API with fetch — no SDK.
 * Checkout Sessions are one POST with a form-encoded body; webhook signatures are one HMAC. A
 * dependency would be a hundred times the code for those two calls, and would put a package
 * install between the author and a deploy of a two-page site.
 */
import { createHmac, timingSafeEqual } from 'node:crypto'

/** Stripe's own default: a signed event older than five minutes is a replay, not a delivery. */
export const SIGNATURE_TOLERANCE_SECONDS = 300

/**
 * Stripe's scheme: the `Stripe-Signature` header carries `t=<unix seconds>` and one or more
 * `v1=<hex>`; each v1 is HMAC-SHA256(secret, `${t}.${rawBody}`). Any v1 matching is enough
 * (there are two while an endpoint's secret is being rotated). The timestamp must be within the
 * tolerance of now, or a captured-and-replayed event would mint a license every time it landed.
 */
export function verifyStripeSignature(rawBody, header, secret, {
  nowSeconds = Math.floor(Date.now() / 1000),
  toleranceSeconds = SIGNATURE_TOLERANCE_SECONDS,
} = {}) {
  if (!secret || typeof header !== 'string' || rawBody == null) return false
  let timestamp = null
  const signatures = []
  for (const part of header.split(',')) {
    const [key, value] = part.trim().split('=')
    if (key === 't') timestamp = Number(value)
    else if (key === 'v1' && value) signatures.push(value)
  }
  if (!Number.isFinite(timestamp) || signatures.length === 0) return false
  if (Math.abs(nowSeconds - timestamp) > toleranceSeconds) return false
  const expected = createHmac('sha256', secret).update(`${timestamp}.${rawBody}`, 'utf8').digest('hex')
  const expectedBuf = Buffer.from(expected, 'utf8')
  return signatures.some((sig) => {
    const given = Buffer.from(sig, 'utf8')
    return given.length === expectedBuf.length && timingSafeEqual(given, expectedBuf)
  })
}

/** What a test (or the Stripe CLI) does to produce a header this module accepts. */
export function signStripePayload(rawBody, secret, timestampSeconds) {
  const v1 = createHmac('sha256', secret).update(`${timestampSeconds}.${rawBody}`, 'utf8').digest('hex')
  return `t=${timestampSeconds},v1=${v1}`
}

/**
 * Stripe's form encoding for nested objects: `line_items[0][price_data][currency]=eur`. Arrays
 * are indexed, objects bracketed, scalars stringified; null/undefined are left out entirely.
 */
export function encodeForm(value, prefix = '', into = new URLSearchParams()) {
  if (value == null) return into
  if (Array.isArray(value)) {
    value.forEach((item, i) => encodeForm(item, `${prefix}[${i}]`, into))
  } else if (typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) {
      encodeForm(item, prefix ? `${prefix}[${key}]` : key, into)
    }
  } else {
    into.append(prefix, String(value))
  }
  return into
}

/**
 * POST /v1/checkout/sessions. Resolves to Stripe's session object (url, id); rejects with
 * Stripe's own error message on anything but a 2xx, so the caller's log says what Stripe said.
 */
export async function createCheckoutSession(params, { secretKey, fetchImpl = globalThis.fetch }) {
  const res = await fetchImpl('https://api.stripe.com/v1/checkout/sessions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${secretKey}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: encodeForm(params).toString(),
  })
  const text = await res.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    json = null
  }
  if (!res.ok) {
    throw new Error(`Stripe answered ${res.status}: ${json?.error?.message ?? text}`)
  }
  return json
}
