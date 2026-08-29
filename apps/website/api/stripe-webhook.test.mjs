/**
 * apps/website/api/stripe-webhook.mjs is the only place a payment becomes a license, so both
 * halves are covered: the handler for what Stripe sees (503 unconfigured, 400 on a bad or stale
 * signature, 200/500 by outcome), and `fulfil` for what happens to the session — minting from
 * its metadata, ignoring what must be ignored, and issuing exactly once per session against the
 * in-memory ledger twin. Fully offline: Resend is globalThis.fetch patched, as in license.test.mjs.
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import handler, { fulfil, orderFromSession } from './stripe-webhook.mjs'
import { generateKeypair, verifyLicense } from './_lib/license-format.mjs'
import { signStripePayload } from './_lib/stripe.mjs'
import { memoryLedger } from './_lib/memory-ledger.mjs'

const teamKeys = generateKeypair()
process.env.TEAM_SIGNING_KEY = teamKeys.privateKeyPkcs8Base64 // the one-line form keygen.mjs prints
process.env.STRIPE_WEBHOOK_SECRET = 'whsec_test_only'
process.env.RESEND_API_KEY = 'test-resend-key'
process.env.LICENSE_FROM = 'Concentus <license@concentus-ai.com>'

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

/** A request as Vercel hands it over with bodyParser off: the raw string, plus Stripe's header. */
function signedReq(rawBody, { timestamp = Math.floor(Date.now() / 1000), secret = process.env.STRIPE_WEBHOOK_SECRET } = {}) {
  return { method: 'POST', headers: { 'stripe-signature': signStripePayload(rawBody, secret, timestamp) }, body: rawBody }
}

function paidSession(overrides = {}) {
  return {
    id: 'cs_test_1',
    payment_status: 'paid',
    customer_details: { name: 'ACME S.L.', email: 'ops@acme.example' },
    metadata: { seats: '3', term: 'monthly', email: 'ops@acme.example' },
    ...overrides,
  }
}

function completedEvent(session = paidSession(), type = 'checkout.session.completed') {
  return { id: 'evt_1', type, data: { object: session } }
}

function resendStub(response = { ok: true, status: 200, text: async () => '' }) {
  const calls = []
  const fn = async (url, init) => { calls.push({ url, init }); return response }
  fn.calls = calls
  return fn
}

function rejectingFetch() {
  const fn = async () => { throw new Error('network is down') }
  return fn
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

async function quiet(fn) {
  const original = console.error
  console.error = () => {}
  try {
    return await fn()
  } finally {
    console.error = original
  }
}

function tokenSentBy(stub) {
  const [{ init }] = stub.calls
  const sentBody = JSON.parse(init.body)
  const [, token] = sentBody.text.match(/(CONCENTUS\.\S+)/)
  return { token, sentBody }
}

// ---- the handler: what Stripe sees ----

test('non-POST is 405', async () => {
  const res = makeRes()
  await handler({ method: 'GET', headers: {} }, res)
  assert.equal(res.statusCode, 405)
})

test('any required env var missing: 503 naming it, before the body is even read', async () => {
  const saved = process.env.TEAM_SIGNING_KEY
  delete process.env.TEAM_SIGNING_KEY
  try {
    const res = makeRes()
    await withFetch(rejectingFetch(), () => handler(signedReq(JSON.stringify(completedEvent())), res))
    assert.equal(res.statusCode, 503)
    assert.match(res.body.error, /not configured/)
    assert.match(res.body.error, /TEAM_SIGNING_KEY/)
  } finally {
    process.env.TEAM_SIGNING_KEY = saved
  }
})

test('a bad signature is 400 and nothing is minted or sent', async () => {
  const stub = resendStub()
  const raw = JSON.stringify(completedEvent())
  for (const req of [
    signedReq(raw, { secret: 'whsec_wrong' }),
    { method: 'POST', headers: {}, body: raw },
    { method: 'POST', headers: { 'stripe-signature': 'garbage' }, body: raw },
  ]) {
    const res = makeRes()
    await withFetch(stub, () => handler(req, res))
    assert.equal(res.statusCode, 400)
  }
  assert.equal(stub.calls.length, 0)
})

test('a stale signature (older than the tolerance) is 400: a replayed event mints nothing', async () => {
  const stub = resendStub()
  const res = makeRes()
  const raw = JSON.stringify(completedEvent())
  await withFetch(stub, () => handler(signedReq(raw, { timestamp: Math.floor(Date.now() / 1000) - 600 }), res))
  assert.equal(res.statusCode, 400)
  assert.equal(stub.calls.length, 0)
})

test('the raw body is read from the stream when Vercel hands one over (bodyParser off)', async () => {
  const stub = resendStub()
  const raw = JSON.stringify(completedEvent())
  const header = signStripePayload(raw, process.env.STRIPE_WEBHOOK_SECRET, Math.floor(Date.now() / 1000))
  const req = {
    method: 'POST',
    headers: { 'stripe-signature': header },
    async *[Symbol.asyncIterator]() { yield Buffer.from(raw.slice(0, 20)); yield Buffer.from(raw.slice(20)) },
  }
  const res = makeRes()
  await quiet(() => withFetch(stub, () => handler(req, res)))
  assert.equal(res.statusCode, 200)
  assert.equal(res.body.outcome, 'sent')
  assert.equal(stub.calls.length, 1)
})

test('a signed, paid checkout.session.completed: a team license that verifies is emailed to the buyer, 200 sent', async () => {
  const stub = resendStub()
  const res = makeRes()
  await quiet(() => withFetch(stub, () => handler(signedReq(JSON.stringify(completedEvent())), res)))
  assert.equal(res.statusCode, 200)
  assert.equal(res.body.outcome, 'sent')

  assert.equal(stub.calls.length, 1)
  assert.equal(stub.calls[0].url, 'https://api.resend.com/emails')
  const { token, sentBody } = tokenSentBy(stub)
  assert.deepEqual(sentBody.to, ['ops@acme.example'])
  assert.equal(sentBody.subject, 'Your Concentus team license')
  const payload = verifyLicense(token, { team: teamKeys.publicKeyPem })
  assert.equal(payload.tier, 'team')
  assert.equal(payload.seats, 3)
  assert.equal(payload.licensee, 'ACME S.L.')
  assert.equal(payload.email, 'ops@acme.example')
  assert.match(payload.expires, /^\d{4}-\d{2}-\d{2}$/)
  assert.equal('trial' in payload, false) // a bought license carries no trial flag at all
})

test('Resend failing: 500, so Stripe retries', async () => {
  const res = makeRes()
  await quiet(() => withFetch(rejectingFetch(), () => handler(signedReq(JSON.stringify(completedEvent())), res)))
  assert.equal(res.statusCode, 500)
})

// ---- fulfil: what happens to the session ----

test('orderFromSession: seats and term from metadata, the licensee from the name Stripe collected, the address as fallback licensee', () => {
  assert.deepEqual(orderFromSession(paidSession()), { seats: 3, term: 'monthly', email: 'ops@acme.example', licensee: 'ACME S.L.' })
  // No name and no metadata email: Stripe's collected address is both the recipient and the licensee.
  const nameless = orderFromSession(paidSession({ customer_details: { email: 'x@y.example' }, metadata: { seats: '3', term: 'monthly' } }))
  assert.equal(nameless.email, 'x@y.example')
  assert.equal(nameless.licensee, 'x@y.example')
  // The address typed on our form (metadata) wins over the one Stripe collected: it is where the buyer asked for the license.
  assert.equal(orderFromSession(paidSession({ customer_details: { name: 'ACME', email: 'x@y.example' } })).email, 'ops@acme.example')
  assert.match(orderFromSession(paidSession({ metadata: { seats: '11', term: 'monthly' } })).error, /seats/)
  assert.match(orderFromSession(paidSession({ metadata: { seats: '2', term: 'weekly' } })).error, /term/)
  assert.match(orderFromSession(paidSession({ customer_details: {}, metadata: { seats: '2', term: 'monthly' } })).error, /email/)
})

test('monthly expires one month after the injected clock; annual twelve', async () => {
  const now = new Date('2026-03-31T10:00:00Z')
  for (const [term, expires] of [['monthly', '2026-05-01'], ['annual', '2027-03-31']]) {
    const stub = resendStub()
    const result = await quiet(() => withFetch(stub, () =>
      fulfil(completedEvent(paidSession({ metadata: { seats: '2', term, email: 'ops@acme.example' } })), { now })))
    assert.equal(result.outcome, 'sent', term)
    assert.equal(result.expires, expires, term) // March 31 + 1 month overflows to May 1, as the enterprise CLI does
    assert.equal(verifyLicense(tokenSentBy(stub).token, { team: teamKeys.publicKeyPem }).expires, expires)
  }
})

test('an unpaid session, a foreign event type, or unfulfillable metadata: ignored with the reason, nothing sent', async () => {
  const stub = resendStub()
  const cases = [
    [completedEvent(paidSession({ payment_status: 'unpaid' })), /payment_status/],
    [completedEvent(paidSession(), 'payment_intent.succeeded'), /event type/],
    [completedEvent(paidSession({ metadata: { seats: '11', term: 'monthly', email: 'ops@acme.example' } })), /seats/],
    [completedEvent(paidSession({ id: undefined })), /session id/],
  ]
  for (const [event, reason] of cases) {
    const result = await quiet(() => withFetch(stub, () => fulfil(event)))
    assert.equal(result.outcome, 'ignored')
    assert.match(result.reason, reason)
  }
  assert.equal(stub.calls.length, 0)
})

test('async_payment_succeeded (SEPA, bank transfer) mints too — it is the paid arrival of a session that completed unpaid', async () => {
  const stub = resendStub()
  const result = await quiet(() => withFetch(stub, () =>
    fulfil(completedEvent(paidSession(), 'checkout.session.async_payment_succeeded'), { ledger: memoryLedger() })))
  assert.equal(result.outcome, 'sent')
  assert.equal(stub.calls.length, 1)
})

test('idempotent per session: the same event delivered twice sends one license, and the ledger holds one sent row', async () => {
  const ledger = memoryLedger()
  const stub = resendStub()
  const first = await withFetch(stub, () => fulfil(completedEvent(), { ledger }))
  const second = await withFetch(stub, () => fulfil(completedEvent(), { ledger }))
  assert.equal(first.outcome, 'sent')
  assert.equal(second.outcome, 'duplicate')
  assert.equal(stub.calls.length, 1)
  const rows = [...ledger.rows.values()]
  assert.equal(rows.length, 1)
  assert.equal(rows[0].status, 'sent')
  assert.equal(rows[0].stripeSession, 'cs_test_1')
  assert.equal(rows[0].kind, 'purchase')
  assert.equal(rows[0].source, 'stripe-webhook')
})

test("a retry after Resend failed DOES send: the pending row is taken over by the retry's token, then marked sent", async () => {
  const ledger = memoryLedger()
  const failed = await quiet(() => withFetch(rejectingFetch(), () => fulfil(completedEvent(), { ledger })))
  assert.equal(failed.outcome, 'send-failed')
  assert.equal([...ledger.rows.values()][0].status, 'pending_send')

  const stub = resendStub()
  const retried = await withFetch(stub, () => fulfil(completedEvent(), { ledger }))
  assert.equal(retried.outcome, 'sent')
  assert.equal(stub.calls.length, 1)
  const rows = [...ledger.rows.values()]
  assert.equal(rows.length, 1) // taken over, not duplicated
  assert.equal(rows[0].status, 'sent')
  assert.equal(rows[0].id, verifyLicense(tokenSentBy(stub).token, { team: teamKeys.publicKeyPem }).id) // the row is the license that was delivered

  // And a third delivery, now that it is sent, is a duplicate.
  const third = await withFetch(stub, () => fulfil(completedEvent(), { ledger }))
  assert.equal(third.outcome, 'duplicate')
  assert.equal(stub.calls.length, 1)
})

test('two different sessions are two licenses', async () => {
  const ledger = memoryLedger()
  const stub = resendStub()
  await withFetch(stub, () => fulfil(completedEvent(paidSession({ id: 'cs_a' })), { ledger }))
  await withFetch(stub, () => fulfil(completedEvent(paidSession({ id: 'cs_b' })), { ledger }))
  assert.equal(stub.calls.length, 2)
  assert.equal(ledger.rows.size, 2)
})

test('without a ledger the license still goes out (best-effort memory, logged)', async () => {
  const stub = resendStub()
  const result = await quiet(() => withFetch(stub, () => fulfil(completedEvent(), { ledger: null })))
  assert.equal(result.outcome, 'sent')
  assert.equal(stub.calls.length, 1)
})
