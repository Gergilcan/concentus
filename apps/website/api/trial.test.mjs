/**
 * apps/website/api/trial.mjs hands out fourteen days of the team features to anyone with an
 * address — so what it hands out (a team license, trial-flagged, expiring in exactly fourteen
 * days, the seats asked for and not one more) and how often (once per address) are the things
 * asserted here. Offline, as the other issuer tests: Resend is globalThis.fetch patched, and the
 * ledger is the in-memory twin.
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import handler, { issueTrial } from './trial.mjs'
import { generateKeypair, verifyLicense } from './_lib/license-format.mjs'
import { memoryLedger } from './_lib/memory-ledger.mjs'

const teamKeys = generateKeypair()
process.env.TEAM_SIGNING_KEY = teamKeys.privateKeyPkcs8Base64
process.env.RESEND_API_KEY = 'test-resend-key'
process.env.LICENSE_FROM = 'Concentus <license@concentus-ai.com>'

const OK = { message: 'If that address is valid, your trial license is on its way.' }

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

function resendStub() {
  const calls = []
  const fn = async (url, init) => { calls.push({ url, init }); return { ok: true, status: 200, text: async () => '' } }
  fn.calls = calls
  return fn
}

function throwingFetch() {
  return async () => { throw new Error('fetch must not be called on this path') }
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

function tokenSentBy(stub, call = 0) {
  const sentBody = JSON.parse(stub.calls[call].init.body)
  const [, token] = sentBody.text.match(/(CONCENTUS\.\S+)/)
  return { token, sentBody, payload: verifyLicense(token, { team: teamKeys.publicKeyPem }) }
}

const GOOD = { name: 'Jane Dev', company: 'ACME S.L.', email: 'jane@acme.example', seats: 3, website: '' }

// ---- the handler ----

test('non-POST is 405', async () => {
  const res = makeRes()
  await handler(makeReq({ method: 'GET' }), res)
  assert.equal(res.statusCode, 405)
})

test('no signing key: 503 saying trials are not open, not the generic line — there is nothing on its way', async () => {
  const saved = process.env.TEAM_SIGNING_KEY
  delete process.env.TEAM_SIGNING_KEY
  try {
    const res = makeRes()
    await withFetch(throwingFetch(), () => handler(makeReq({ body: GOOD }), res))
    assert.equal(res.statusCode, 503)
    assert.match(res.body.error, /not open yet/)
    assert.match(res.body.error, /TEAM_SIGNING_KEY/)
  } finally {
    process.env.TEAM_SIGNING_KEY = saved
  }
})

test('honeypot, a bad address, no name, or seats outside 1..10: the generic OK and no send', async () => {
  for (const body of [
    { ...GOOD, website: 'https://bots.example' },
    { ...GOOD, email: 'not-an-email' },
    { ...GOOD, name: '' },
    { ...GOOD, seats: 11 },
    { ...GOOD, seats: 0 },
    { ...GOOD, seats: 'many' },
    '{ not json',
  ]) {
    const res = makeRes()
    await withFetch(throwingFetch(), () => handler(makeReq({ body }), res))
    assert.equal(res.statusCode, 200, JSON.stringify(body))
    assert.deepEqual(res.body, OK)
  }
})

test('a good request: a trial-flagged team license with the seats asked for, to the address lower-cased, licensed to the company', async () => {
  const stub = resendStub()
  const res = makeRes()
  await quiet(() => withFetch(stub, () => handler(makeReq({ body: { ...GOOD, email: 'Jane@ACME.example' } }), res)))
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body, OK)
  assert.equal(stub.calls.length, 1)
  const { sentBody, payload } = tokenSentBy(stub)
  assert.deepEqual(sentBody.to, ['jane@acme.example'])
  assert.equal(sentBody.subject, 'Your Concentus 14-day trial')
  assert.equal(payload.tier, 'team')
  assert.equal(payload.trial, true)
  assert.equal(payload.seats, 3)
  assert.equal(payload.licensee, 'ACME S.L.')
  assert.equal(payload.email, 'jane@acme.example')
})

test('response oracle: a refused request and a served one answer byte-for-byte identically', async () => {
  const refused = makeRes()
  await withFetch(throwingFetch(), () => handler(makeReq({ body: { ...GOOD, website: 'x' } }), refused))
  const served = makeRes()
  await quiet(() => withFetch(resendStub(), () => handler(makeReq({ body: GOOD }), served)))
  assert.equal(JSON.stringify({ s: refused.statusCode, b: refused.body }), JSON.stringify({ s: served.statusCode, b: served.body }))
})

// ---- issueTrial ----

test('expires exactly TRIAL_DAYS after the clock, issued today, in UTC dates', async () => {
  const stub = resendStub()
  const result = await quiet(() => withFetch(stub, () =>
    issueTrial({ name: 'Jane', company: '', email: 'jane@acme.example', seats: 5 }, { now: new Date('2026-08-29T23:30:00Z') })))
  assert.equal(result.outcome, 'sent')
  assert.equal(result.expires, '2026-09-12')
  const { payload } = tokenSentBy(stub)
  assert.equal(payload.issued, '2026-08-29')
  assert.equal(payload.expires, '2026-09-12')
  assert.equal(payload.seats, 5)
  assert.equal(payload.licensee, 'Jane') // no company: the person is the licensee
})

test('the seat cap holds at the last line too: eleven seats never reach the key', async () => {
  await assert.rejects(
    () => issueTrial({ name: 'Jane', company: '', email: 'jane@acme.example', seats: 11 }, { ledger: memoryLedger() }),
    /1 to 10 seats/,
  )
})

test('one trial per address: the second request for the same address sends nothing, and the ledger holds one trial row', async () => {
  const ledger = memoryLedger()
  const stub = resendStub()
  const first = await withFetch(stub, () => issueTrial({ name: 'Jane', company: 'ACME', email: 'jane@acme.example', seats: 3 }, { ledger }))
  const second = await withFetch(stub, () => issueTrial({ name: 'Jane', company: 'ACME', email: 'jane@acme.example', seats: 10 }, { ledger }))
  assert.equal(first.outcome, 'sent')
  assert.equal(second.outcome, 'already-had-one')
  assert.equal(stub.calls.length, 1)
  const rows = [...ledger.rows.values()]
  assert.equal(rows.length, 1)
  assert.equal(rows[0].kind, 'trial')
  assert.equal(rows[0].source, 'trial-form')
  assert.equal(rows[0].status, 'sent')
  // A different address is a different trial.
  const other = await withFetch(stub, () => issueTrial({ name: 'Joe', company: 'ACME', email: 'joe@acme.example', seats: 3 }, { ledger }))
  assert.equal(other.outcome, 'sent')
  assert.equal(stub.calls.length, 2)
})

test('Resend failing: send-failed, and the row stays pending_send as the report', async () => {
  const ledger = memoryLedger()
  const result = await quiet(() => withFetch(async () => { throw new Error('down') }, () =>
    issueTrial({ name: 'Jane', company: '', email: 'jane@acme.example', seats: 3 }, { ledger })))
  assert.equal(result.outcome, 'send-failed')
  assert.equal([...ledger.rows.values()][0].status, 'pending_send')
})
