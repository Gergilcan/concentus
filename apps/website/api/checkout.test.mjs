/**
 * apps/website/api/checkout.mjs is where a buyer's seats and term become the amount Stripe
 * charges — so the arithmetic is asserted here to the cent, against the pure params builder, and
 * the handler is exercised with a fake Stripe (globalThis.fetch patched, as license.test.mjs does)
 * for its status codes: 503 unconfigured, 400 bad order, 200 with the session URL, 502 on a
 * Stripe error.
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import handler, { checkoutSessionParams } from './checkout.mjs'
import { readPricing } from './_lib/pricing.mjs'

function makeRes() {
  return {
    statusCode: undefined,
    body: undefined,
    ended: false,
    status(code) { this.statusCode = code; return this },
    json(payload) { this.body = payload; return this },
    end() { this.ended = true; return this },
  }
}

function makeReq({ method = 'POST', body = {} } = {}) {
  return { method, headers: {}, body }
}

async function withEnv(vars, fn) {
  const saved = {}
  for (const [k, v] of Object.entries(vars)) {
    saved[k] = process.env[k]
    if (v == null) delete process.env[k]
    else process.env[k] = v
  }
  try {
    return await fn()
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v == null) delete process.env[k]
      else process.env[k] = v
    }
  }
}

async function withFetch(stub, fn) {
  const original = globalThis.fetch
  globalThis.fetch = stub
  try {
    return await fn()
  } finally {
    globalThis.fetch = original
  }
}

function stripeStub(response = { ok: true, status: 200, text: async () => JSON.stringify({ id: 'cs_1', url: 'https://checkout.stripe.com/c/pay/cs_1' }) }) {
  const calls = []
  const fn = async (url, init) => { calls.push({ url, init }); return response }
  fn.calls = calls
  return fn
}

const CONFIGURED = { STRIPE_SECRET_KEY: 'sk_test_x', TEAM_PRICE_MONTHLY_PER_SEAT: '12', SITE_URL: 'https://example.test' }
const PRICING = readPricing({ TEAM_PRICE_MONTHLY_PER_SEAT: '12' })

test('checkoutSessionParams: monthly — quantity is the seats, unit_amount the per-seat price in cents, metadata what the webhook reads back', () => {
  const p = checkoutSessionParams({ seats: 3, term: 'monthly', email: 'ops@example.com' }, PRICING, 'https://example.test')
  assert.equal(p.mode, 'payment')
  assert.equal(p.customer_email, 'ops@example.com')
  assert.equal(p.line_items.length, 1)
  assert.equal(p.line_items[0].quantity, 3)
  assert.equal(p.line_items[0].price_data.unit_amount, 1200)
  assert.equal(p.line_items[0].price_data.currency, 'eur')
  assert.match(p.line_items[0].price_data.product_data.name, /one month/)
  assert.deepEqual(p.metadata, { seats: '3', term: 'monthly', email: 'ops@example.com' })
  assert.equal(p.success_url, 'https://example.test/?checkout=success#license')
  assert.equal(p.cancel_url, 'https://example.test/?checkout=cancelled#license')
})

test('checkoutSessionParams: annual — a year per seat at 12 × 0.8 months, the discount named in the description', () => {
  const p = checkoutSessionParams({ seats: 5, term: 'annual', email: 'ops@example.com' }, PRICING, 'https://example.test')
  assert.equal(p.line_items[0].quantity, 5)
  assert.equal(p.line_items[0].price_data.unit_amount, 11520)
  assert.match(p.line_items[0].price_data.product_data.name, /one year/)
  assert.match(p.line_items[0].price_data.product_data.description, /20%/)
  assert.equal(p.metadata.term, 'annual')
})

test('non-POST is 405', async () => {
  const res = makeRes()
  await handler(makeReq({ method: 'GET' }), res)
  assert.equal(res.statusCode, 405)
  assert.equal(res.ended, true)
})

test('no Stripe key or no price: 503 "not configured", naming what is missing, never a stack trace', async () => {
  await withEnv({ STRIPE_SECRET_KEY: null, TEAM_PRICE_MONTHLY_PER_SEAT: null }, async () => {
    const res = makeRes()
    await withFetch(stripeStub(), () => handler(makeReq({ body: { seats: 3, term: 'monthly', email: 'a@b.co' } }), res))
    assert.equal(res.statusCode, 503)
    assert.match(res.body.error, /not open yet/)
    assert.deepEqual(res.body.missing, ['STRIPE_SECRET_KEY', 'TEAM_PRICE_MONTHLY_PER_SEAT'])
  })
  // The key alone is not enough: with no price there is nothing honest to charge.
  await withEnv({ STRIPE_SECRET_KEY: 'sk_test_x', TEAM_PRICE_MONTHLY_PER_SEAT: null }, async () => {
    const res = makeRes()
    await handler(makeReq({ body: { seats: 3, term: 'monthly', email: 'a@b.co' } }), res)
    assert.equal(res.statusCode, 503)
  })
})

test('a bad order (11 seats, an unknown term, no email) is 400 and never reaches Stripe', async () => {
  await withEnv(CONFIGURED, async () => {
    for (const body of [
      { seats: 11, term: 'monthly', email: 'a@b.co' },
      { seats: 2, term: 'weekly', email: 'a@b.co' },
      { seats: 2, term: 'annual', email: 'nope' },
      '{ not json',
    ]) {
      const stub = stripeStub()
      const res = makeRes()
      await withFetch(stub, () => handler(makeReq({ body }), res))
      assert.equal(res.statusCode, 400, JSON.stringify(body))
      assert.equal(stub.calls.length, 0)
    }
  })
})

test('a good order creates the session with the computed line item and answers its URL', async () => {
  await withEnv(CONFIGURED, async () => {
    const stub = stripeStub()
    const res = makeRes()
    await withFetch(stub, () => handler(makeReq({ body: { seats: '4', term: 'annual', email: 'ops@example.com' } }), res))
    assert.equal(res.statusCode, 200)
    assert.deepEqual(res.body, { url: 'https://checkout.stripe.com/c/pay/cs_1' })
    assert.equal(stub.calls.length, 1)
    const form = new URLSearchParams(stub.calls[0].init.body)
    assert.equal(form.get('mode'), 'payment')
    assert.equal(form.get('line_items[0][quantity]'), '4')
    assert.equal(form.get('line_items[0][price_data][unit_amount]'), '11520')
    assert.equal(form.get('metadata[seats]'), '4')
    assert.equal(form.get('metadata[term]'), 'annual')
    assert.equal(form.get('metadata[email]'), 'ops@example.com')
    assert.equal(form.get('success_url'), 'https://example.test/?checkout=success#license')
  })
})

test('Stripe refusing the session is a 502 that says nothing was charged', async () => {
  await withEnv(CONFIGURED, async () => {
    const stub = stripeStub({ ok: false, status: 402, text: async () => JSON.stringify({ error: { message: 'no' } }) })
    const res = makeRes()
    const originalConsoleError = console.error
    console.error = () => {}
    try {
      await withFetch(stub, () => handler(makeReq({ body: { seats: 1, term: 'monthly', email: 'a@b.co' } }), res))
    } finally {
      console.error = originalConsoleError
    }
    assert.equal(res.statusCode, 502)
    assert.match(res.body.error, /nothing was charged/)
  })
})
