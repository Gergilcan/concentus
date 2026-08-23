/**
 * apps/website/api/license.mjs is the internet-facing surface: every branch here decides what a
 * stranger's POST gets back and whether an email goes out. Fully offline — no real HTTP, no real
 * signing key — by hand-building the `req`/`res` Vercel hands the function and monkey-patching
 * `globalThis.fetch`, which is what the handler reads (its signature is untouched on purpose).
 *
 * The module keeps a rate-limit Map at module scope, so each scenario below imports its OWN copy
 * of the module with a cache-busting query string (`./license.mjs?case=N`) — Node's ESM loader
 * keys its module cache on the full specifier, so a new query string means a fresh Map, without
 * exposing a reset hook the production module has no other reason to have.
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { generateKeypair, verifyLicense } from './_lib/license-format.mjs'

// A keypair for this file only. The handler signs with process.env.LICENSE_KEY_INDIVIDUAL; tests
// verify against this keypair's public half, so nothing here touches a real production key.
const testKeys = generateKeypair()
process.env.LICENSE_KEY_INDIVIDUAL = testKeys.privateKeyPem
process.env.RESEND_API_KEY = 'test-resend-key'
process.env.LICENSE_FROM = 'Concentus <license@concentus-ai.com>'

const OK = { message: 'If that address is valid, your license is on its way.' }

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

function makeReq({ method = 'POST', headers = {}, body = {} } = {}) {
  return { method, headers, body }
}

let importSeq = 0
async function freshHandler() {
  importSeq += 1
  const mod = await import(`./license.mjs?case=${importSeq}`)
  return mod.default
}

// For paths that must never reach the network: fetch throwing turns "a send was attempted" into
// a failing test, rather than something a later assertion has to notice on its own.
function throwingFetch() {
  return async () => { throw new Error('fetch must not be called on this path') }
}

function countingFetch(response = { ok: true, status: 200, text: async () => '' }) {
  const calls = []
  const fn = async (url, init) => { calls.push({ url, init }); return response }
  fn.calls = calls
  return fn
}

// A transport failure: fetch() rejects rather than resolving with a non-ok response — DNS,
// connection refused, TLS, timeout. Counts calls the same way countingFetch does, so a test can
// assert the send was attempted (and failed) rather than skipped.
function rejectingFetch(message = 'network is down') {
  const calls = []
  const fn = async (url, init) => { calls.push({ url, init }); throw new Error(message) }
  fn.calls = calls
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

test('non-POST is refused with 405 and never touches the network', async () => {
  const handler = await freshHandler()
  const res = makeRes()
  await withFetch(throwingFetch(), () => handler(makeReq({ method: 'GET' }), res))
  assert.equal(res.statusCode, 405)
  assert.equal(res.ended, true)
  assert.equal(res.body, undefined) // .end(), not .json() — this one is NOT part of the response oracle
})

test('honeypot filled: generic OK, no send attempted', async () => {
  const handler = await freshHandler()
  const res = makeRes()
  await withFetch(throwingFetch(), () =>
    handler(makeReq({ body: { name: 'Bot', email: 'bot@example.com', company: 'Acme Bots LLC' } }), res))
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body, OK)
})

test('invalid email: generic OK, no send attempted', async () => {
  const handler = await freshHandler()
  const res = makeRes()
  await withFetch(throwingFetch(), () =>
    handler(makeReq({ body: { name: 'Jane', email: 'not-an-email', company: '' } }), res))
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body, OK)
})

test('missing name: generic OK, no send attempted', async () => {
  const handler = await freshHandler()
  const res = makeRes()
  await withFetch(throwingFetch(), () =>
    handler(makeReq({ body: { email: 'jane@example.com', company: '' } }), res))
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body, OK)
})

test('malformed / unparsed body: readBody degrades to {}, no crash, no send attempted', async () => {
  const handler = await freshHandler()
  const res = makeRes()
  // Simulates a form posted with the wrong content-type: Vercel hands the handler the raw,
  // unparsable string instead of an object. Not throwing here IS the assertion.
  await withFetch(throwingFetch(), () => handler(makeReq({ body: '{ this is not json' }), res))
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body, OK)
})

test('rate cap: the 11th request from one IP is refused at the boundary, the first 10 are not', async () => {
  const handler = await freshHandler()
  const fetchStub = countingFetch()
  const headers = { 'x-forwarded-for': '203.0.113.9' }

  await withFetch(fetchStub, async () => {
    for (let i = 0; i < 10; i++) {
      const res = makeRes()
      await handler(makeReq({ headers, body: { name: 'Jane Dev', email: `jane${i}@example.com`, company: '' } }), res)
      assert.equal(res.statusCode, 200)
    }
    assert.equal(fetchStub.calls.length, 10) // all ten under the cap actually tried to send

    const res11 = makeRes()
    await handler(makeReq({ headers, body: { name: 'Jane Dev', email: 'jane11@example.com', company: '' } }), res11)
    assert.equal(res11.statusCode, 200)
    assert.deepEqual(res11.body, OK)
    assert.equal(fetchStub.calls.length, 10) // unchanged: the 11th never reached fetch
  })
})

test('per-email rate cap: the 11th request for the same address from DIFFERENT IPs is refused, even though no single IP is over its own cap', async () => {
  const handler = await freshHandler()
  const fetchStub = countingFetch()

  await withFetch(fetchStub, async () => {
    for (let i = 0; i < 10; i++) {
      const res = makeRes()
      // A fresh IP every time: the per-IP counter never climbs past 1, so only the per-email
      // counter can be what trips the cap here.
      await handler(makeReq({
        headers: { 'x-forwarded-for': `203.0.113.${i}` },
        body: { name: 'Jane Dev', email: 'jane@example.com', company: '' },
      }), res)
      assert.equal(res.statusCode, 200)
    }
    assert.equal(fetchStub.calls.length, 10) // all ten under the cap actually tried to send

    const res11 = makeRes()
    await handler(makeReq({
      headers: { 'x-forwarded-for': '203.0.113.99' },
      body: { name: 'Jane Dev', email: 'JANE@Example.com', company: '' }, // same address, different casing
    }), res11)
    assert.equal(res11.statusCode, 200)
    assert.deepEqual(res11.body, OK)
    assert.equal(fetchStub.calls.length, 10) // unchanged: the 11th never reached fetch
  })
})

test('valid request: Resend is called with a token that verifies (individual, no expiry, enterprise URL in the body)', async () => {
  const handler = await freshHandler()
  const fetchStub = countingFetch()
  const res = makeRes()

  await withFetch(fetchStub, () =>
    handler(makeReq({ body: { name: 'Jane Dev', email: 'jane@example.com', company: '' } }), res))

  assert.equal(fetchStub.calls.length, 1)
  const [{ url, init }] = fetchStub.calls
  assert.equal(url, 'https://api.resend.com/emails')
  assert.equal(init.method, 'POST')
  assert.equal(init.headers.Authorization, `Bearer ${process.env.RESEND_API_KEY}`)

  const sentBody = JSON.parse(init.body)
  assert.deepEqual(sentBody.to, ['jane@example.com'])
  assert.equal(sentBody.subject, 'Your Concentus license')
  assert.match(sentBody.text, /https:\/\/www\.concentus-ai\.com\/#license/)

  const [, token] = sentBody.text.match(/(CONCENTUS\.\S+)/)
  const payload = verifyLicense(token, { individual: testKeys.publicKeyPem })
  assert.equal(payload.tier, 'individual')
  assert.equal(payload.expires, null)
  assert.equal(payload.licensee, 'Jane Dev')
  assert.equal(payload.email, 'jane@example.com')

  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body, OK)
})

test('analytics is best-effort: a broken DATABASE_URL neither blocks the license nor changes the answer', async () => {
  // Locally the Neon driver is not even installed (apps/website is not a workspace member; Vercel
  // installs it at deploy time), so this exercises the failure path for real: the dynamic import
  // rejects, recordIssue swallows it, and the email still goes out.
  process.env.DATABASE_URL = 'not-a-database-url'
  try {
    const handler = await freshHandler()
    const fetchStub = countingFetch()
    const res = makeRes()
    await withFetch(fetchStub, () =>
      handler(makeReq({ body: { name: 'Jane Dev', email: 'jane@example.com', company: '' } }), res))
    assert.equal(fetchStub.calls.length, 1)
    assert.equal(res.statusCode, 200)
    assert.deepEqual(res.body, OK)
  } finally {
    delete process.env.DATABASE_URL
  }
})

test('Resend transport failure: the send is attempted, fails, is logged, and the response is still the generic 200 OK', async () => {
  const handler = await freshHandler()
  const fetchStub = rejectingFetch()
  const res = makeRes()
  const originalConsoleError = console.error
  const logged = []
  console.error = (...args) => logged.push(args)

  try {
    await withFetch(fetchStub, () =>
      handler(makeReq({ body: { name: 'Jane Dev', email: 'jane@example.com', company: '' } }), res))
  } finally {
    console.error = originalConsoleError
  }

  assert.equal(fetchStub.calls.length, 1) // the send really was attempted
  assert.equal(logged.length, 1) // and the failure was logged, not swallowed silently
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body, OK)
})

test('response oracle: honeypot, invalid input, the rate-cap block, a malformed body, a Resend transport failure and success all answer identically', async () => {
  const results = []

  { // honeypot
    const handler = await freshHandler()
    const res = makeRes()
    await withFetch(throwingFetch(), () =>
      handler(makeReq({ body: { name: 'Bot', email: 'bot@x.com', company: 'x' } }), res))
    results.push(res)
  }
  { // invalid input
    const handler = await freshHandler()
    const res = makeRes()
    await withFetch(throwingFetch(), () => handler(makeReq({ body: { email: 'nope', company: '' } }), res))
    results.push(res)
  }
  { // rate-cap boundary
    const handler = await freshHandler()
    const headers = { 'x-forwarded-for': '198.51.100.7' }
    await withFetch(countingFetch(), async () => {
      for (let i = 0; i < 10; i++) {
        await handler(makeReq({ headers, body: { name: 'Jane', email: `j${i}@x.com`, company: '' } }), makeRes())
      }
      const res = makeRes()
      await handler(makeReq({ headers, body: { name: 'Jane', email: 'j11@x.com', company: '' } }), res)
      results.push(res)
    })
  }
  { // malformed body
    const handler = await freshHandler()
    const res = makeRes()
    await withFetch(throwingFetch(), () => handler(makeReq({ body: 'not-json{{' }), res))
    results.push(res)
  }
  { // Resend transport failure
    const handler = await freshHandler()
    const res = makeRes()
    const originalConsoleError = console.error
    console.error = () => {}
    try {
      await withFetch(rejectingFetch(), () =>
        handler(makeReq({ body: { name: 'Jane', email: 'transport-fail@x.com', company: '' } }), res))
    } finally {
      console.error = originalConsoleError
    }
    results.push(res)
  }
  { // success
    const handler = await freshHandler()
    const res = makeRes()
    await withFetch(countingFetch(), () =>
      handler(makeReq({ body: { name: 'Jane', email: 'jane@x.com', company: '' } }), res))
    results.push(res)
  }

  assert.equal(results.length, 6)
  for (const res of results) {
    assert.equal(res.statusCode, 200)
    assert.deepEqual(res.body, OK)
  }
  const serialized = results.map((r) => JSON.stringify({ status: r.statusCode, body: r.body }))
  assert.ok(serialized.every((s) => s === serialized[0]), 'every case must answer byte-for-byte identically')
})
