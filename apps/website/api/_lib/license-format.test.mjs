import test from 'node:test'
import assert from 'node:assert/strict'
import { signLicense, verifyLicense, generateKeypair } from './license-format.mjs'

const ind = generateKeypair()
const ent = generateKeypair()
const keys = { individual: ind.publicKeyPem, enterprise: ent.publicKeyPem }

test('round-trip: an individual license signs and verifies', () => {
  const token = signLicense(
    { tier: 'individual', licensee: 'Jane Dev', email: 'jane@example.com', issued: '2026-08-22', expires: null, id: 'test-1' },
    ind.privateKeyPem,
  )
  assert.ok(token.startsWith('CONCENTUS.'))
  const payload = verifyLicense(token, keys)
  assert.equal(payload.tier, 'individual')
  assert.equal(payload.v, 1)
  assert.equal(payload.expires, null)
})

test('round-trip: an enterprise license carries seats and expiry', () => {
  const token = signLicense(
    { tier: 'enterprise', licensee: 'ACME S.L.', email: 'ops@acme.com', seats: 20, issued: '2026-08-22', expires: '2027-08-22', id: 'test-2' },
    ent.privateKeyPem,
  )
  const payload = verifyLicense(token, keys)
  assert.equal(payload.seats, 20)
  assert.equal(payload.expires, '2027-08-22')
})

test('a tampered payload is refused', () => {
  const token = signLicense(
    { tier: 'individual', licensee: 'Jane', email: 'j@e.com', issued: '2026-08-22', expires: null, id: 't3' },
    ind.privateKeyPem,
  )
  const [head, payload, sig] = token.split('.')
  const forged = JSON.parse(Buffer.from(payload, 'base64url').toString())
  forged.tier = 'enterprise'
  const bad = [head, Buffer.from(JSON.stringify(forged)).toString('base64url'), sig].join('.')
  assert.throws(() => verifyLicense(bad, keys))
})

test('tier must match the signing key: enterprise payload under the individual key is refused', () => {
  const token = signLicense(
    { tier: 'enterprise', licensee: 'Sneaky', email: 's@e.com', seats: 999, issued: '2026-08-22', expires: '2099-01-01', id: 't4' },
    ind.privateKeyPem,   // wrong key on purpose
  )
  assert.throws(() => verifyLicense(token, keys), /signature/)
})

test('the private key is accepted as a PEM or as the one-line PKCS8 base64 keygen.mjs prints — same key, same signatures verify', () => {
  const team = generateKeypair()
  const payload = { tier: 'team', licensee: 'Team', email: 't@e.com', seats: 3, issued: '2026-08-29', expires: '2026-09-29', id: 't5' }
  const fromPem = signLicense(payload, team.privateKeyPem)
  const fromBase64 = signLicense(payload, team.privateKeyPkcs8Base64)
  assert.equal(verifyLicense(fromPem, { team: team.publicKeyPem }).seats, 3)
  assert.equal(verifyLicense(fromBase64, { team: team.publicKeyPem }).seats, 3)
  assert.throws(() => signLicense(payload, ''), /no private key/)
})

test('garbage is refused, not crashed on', () => {
  assert.throws(() => verifyLicense('CONCENTUS.not-base64.!!', keys))
  assert.throws(() => verifyLicense('hello', keys))
})
