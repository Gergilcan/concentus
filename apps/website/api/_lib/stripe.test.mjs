import test from 'node:test'
import assert from 'node:assert/strict'
import { createCheckoutSession, encodeForm, signStripePayload, verifyStripeSignature } from './stripe.mjs'

const SECRET = 'whsec_test_secret_for_this_file_only'
const BODY = '{"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_1"}}}'
const NOW = 1_700_000_000

test('a signature Stripe would produce verifies', () => {
  const header = signStripePayload(BODY, SECRET, NOW)
  assert.equal(verifyStripeSignature(BODY, header, SECRET, { nowSeconds: NOW + 10 }), true)
})

test('the wrong secret, a tampered body, or a bogus v1 does not verify', () => {
  const header = signStripePayload(BODY, SECRET, NOW)
  assert.equal(verifyStripeSignature(BODY, header, 'whsec_other', { nowSeconds: NOW }), false)
  assert.equal(verifyStripeSignature(BODY.replace('cs_1', 'cs_2'), header, SECRET, { nowSeconds: NOW }), false)
  assert.equal(verifyStripeSignature(BODY, `t=${NOW},v1=deadbeef`, SECRET, { nowSeconds: NOW }), false)
})

test('a stale timestamp is a replay: outside the tolerance the signature is refused even though the HMAC matches', () => {
  const header = signStripePayload(BODY, SECRET, NOW)
  assert.equal(verifyStripeSignature(BODY, header, SECRET, { nowSeconds: NOW + 299 }), true)
  assert.equal(verifyStripeSignature(BODY, header, SECRET, { nowSeconds: NOW + 301 }), false)
  assert.equal(verifyStripeSignature(BODY, header, SECRET, { nowSeconds: NOW - 301 }), false)
})

test('two v1 signatures (a secret being rotated): any one matching is enough', () => {
  const good = signStripePayload(BODY, SECRET, NOW).split(',')[1]
  assert.equal(verifyStripeSignature(BODY, `t=${NOW},v1=0000,${good}`, SECRET, { nowSeconds: NOW }), true)
})

test('a missing or malformed header, or no secret, never verifies and never throws', () => {
  assert.equal(verifyStripeSignature(BODY, undefined, SECRET), false)
  assert.equal(verifyStripeSignature(BODY, '', SECRET), false)
  assert.equal(verifyStripeSignature(BODY, 'garbage', SECRET), false)
  assert.equal(verifyStripeSignature(BODY, 'v1=abc', SECRET), false) // no timestamp
  assert.equal(verifyStripeSignature(BODY, `t=${NOW}`, SECRET, { nowSeconds: NOW }), false) // no v1
  assert.equal(verifyStripeSignature(BODY, signStripePayload(BODY, SECRET, NOW), '', { nowSeconds: NOW }), false)
})

test('encodeForm writes Stripe bracket notation for nested objects and indexed arrays, and skips nulls', () => {
  const encoded = encodeForm({
    mode: 'payment',
    line_items: [{ quantity: 3, price_data: { currency: 'eur', unit_amount: 1200 } }],
    metadata: { seats: '3', term: 'monthly' },
    nothing: null,
  }).toString()
  assert.equal(
    encoded,
    'mode=payment'
      + '&line_items%5B0%5D%5Bquantity%5D=3'
      + '&line_items%5B0%5D%5Bprice_data%5D%5Bcurrency%5D=eur'
      + '&line_items%5B0%5D%5Bprice_data%5D%5Bunit_amount%5D=1200'
      + '&metadata%5Bseats%5D=3'
      + '&metadata%5Bterm%5D=monthly',
  )
})

test('createCheckoutSession posts a form-encoded body with the bearer key and returns the session', async () => {
  const calls = []
  const fetchImpl = async (url, init) => {
    calls.push({ url, init })
    return { ok: true, status: 200, text: async () => JSON.stringify({ id: 'cs_test', url: 'https://checkout.stripe.com/c/pay/cs_test' }) }
  }
  const session = await createCheckoutSession({ mode: 'payment', metadata: { seats: '2' } }, { secretKey: 'sk_test_x', fetchImpl })
  assert.equal(session.url, 'https://checkout.stripe.com/c/pay/cs_test')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, 'https://api.stripe.com/v1/checkout/sessions')
  assert.equal(calls[0].init.method, 'POST')
  assert.equal(calls[0].init.headers.Authorization, 'Bearer sk_test_x')
  assert.equal(calls[0].init.headers['Content-Type'], 'application/x-www-form-urlencoded')
  assert.equal(calls[0].init.body, 'mode=payment&metadata%5Bseats%5D=2')
})

test("createCheckoutSession rejects with Stripe's own message on a non-2xx", async () => {
  const fetchImpl = async () => ({ ok: false, status: 400, text: async () => JSON.stringify({ error: { message: 'Invalid currency: xyz' } }) })
  await assert.rejects(
    () => createCheckoutSession({}, { secretKey: 'sk', fetchImpl }),
    /Stripe answered 400: Invalid currency: xyz/,
  )
})
