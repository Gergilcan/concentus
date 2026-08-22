import test from 'node:test'
import assert from 'node:assert/strict'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { generateKeypair, verifyLicense } from '../../apps/website/api/_lib/license-format.mjs'
import { mint, sendEmail } from './mint-license.mjs'

function tempKeysDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'concentus-keys-test-'))
}

function withEnterpriseKey(dir) {
  const kp = generateKeypair()
  fs.writeFileSync(path.join(dir, 'enterprise.key.pem'), kp.privateKeyPem)
  return kp.publicKeyPem
}

test('mint() signs an enterprise license with seats, and --months 1 expires exactly one month later', () => {
  const dir = tempKeysDir()
  const publicKeyPem = withEnterpriseKey(dir)
  const now = new Date(2026, 0, 15, 10, 0, 0) // 2026-01-15 local

  const result = mint(
    { licensee: 'ACME S.L.', email: 'ops@acme.com', seats: 20, months: 1 },
    { keysDir: dir, now },
  )

  assert.ok(result.token.startsWith('CONCENTUS.'))
  const payload = verifyLicense(result.token, { enterprise: publicKeyPem })
  assert.equal(payload.tier, 'enterprise')
  assert.equal(payload.licensee, 'ACME S.L.')
  assert.equal(payload.email, 'ops@acme.com')
  assert.equal(payload.seats, 20)
  assert.equal(payload.issued, '2026-01-15')
  assert.equal(payload.expires, '2026-02-15')
})

test('mint() with --years 1 expires twelve months later', () => {
  const dir = tempKeysDir()
  const publicKeyPem = withEnterpriseKey(dir)
  const now = new Date(2026, 0, 15, 10, 0, 0)

  const result = mint(
    { licensee: 'ACME S.L.', email: 'ops@acme.com', seats: 5, years: 1 },
    { keysDir: dir, now },
  )

  const payload = verifyLicense(result.token, { enterprise: publicKeyPem })
  assert.equal(payload.expires, '2027-01-15')
})

test('mint() appends a ledger line with exactly the id/licensee/email/seats/expires/mintedAt fields', () => {
  const dir = tempKeysDir()
  withEnterpriseKey(dir)
  const now = new Date(2026, 0, 15, 10, 0, 0)

  const result = mint(
    { licensee: 'Beta Corp', email: 'it@beta.example', seats: 7, months: 3 },
    { keysDir: dir, now },
  )

  const ledgerPath = path.join(dir, 'issued-licenses.jsonl')
  const lines = fs.readFileSync(ledgerPath, 'utf8').trim().split('\n')
  assert.equal(lines.length, 1)
  const entry = JSON.parse(lines[0])
  assert.deepEqual(Object.keys(entry).sort(), ['email', 'expires', 'id', 'licensee', 'mintedAt', 'seats'].sort())
  assert.equal(entry.id, result.id)
  assert.equal(entry.licensee, 'Beta Corp')
  assert.equal(entry.email, 'it@beta.example')
  assert.equal(entry.seats, 7)
  assert.equal(entry.expires, '2026-04-15')
  assert.equal(entry.mintedAt, now.toISOString())

  // the id in the ledger must be the SAME id the token itself carries
  const publicKeyPem = fs.readFileSync(path.join(dir, 'enterprise.key.pem'), 'utf8') // sanity: key exists
  assert.ok(publicKeyPem.includes('PRIVATE KEY'))
  const [, segment] = result.token.split('.')
  const payload = JSON.parse(Buffer.from(segment, 'base64url').toString())
  assert.equal(payload.id, entry.id)
})

test('mint() appends further lines on repeated calls rather than overwriting the ledger', () => {
  const dir = tempKeysDir()
  withEnterpriseKey(dir)
  const now = new Date(2026, 0, 15, 10, 0, 0)

  mint({ licensee: 'First', email: 'a@x.com', seats: 1, months: 1 }, { keysDir: dir, now })
  mint({ licensee: 'Second', email: 'b@x.com', seats: 2, months: 1 }, { keysDir: dir, now })

  const ledgerPath = path.join(dir, 'issued-licenses.jsonl')
  const lines = fs.readFileSync(ledgerPath, 'utf8').trim().split('\n')
  assert.equal(lines.length, 2)
  assert.equal(JSON.parse(lines[0]).licensee, 'First')
  assert.equal(JSON.parse(lines[1]).licensee, 'Second')
})

test('mint() refuses when the enterprise key file is missing, naming the path and generate-keys.mjs', () => {
  const dir = tempKeysDir() // no enterprise.key.pem written
  const now = new Date(2026, 0, 15)

  assert.throws(
    () => mint({ licensee: 'ACME', email: 'ops@acme.com', seats: 1, months: 1 }, { keysDir: dir, now }),
    (err) => {
      assert.match(err.message, /enterprise\.key\.pem/)
      assert.match(err.message, /generate-keys\.mjs/)
      return true
    },
  )
})

test('mint() requires exactly one of --months or --years', () => {
  const dir = tempKeysDir()
  withEnterpriseKey(dir)
  const now = new Date(2026, 0, 15)

  assert.throws(() => mint({ licensee: 'ACME', email: 'ops@acme.com', seats: 1 }, { keysDir: dir, now }))
  assert.throws(() =>
    mint({ licensee: 'ACME', email: 'ops@acme.com', seats: 1, months: 1, years: 1 }, { keysDir: dir, now }),
  )
})

test('mint() rejects a non-positive or non-integer months/seats value', () => {
  const dir = tempKeysDir()
  withEnterpriseKey(dir)
  const now = new Date(2026, 0, 15)

  assert.throws(() => mint({ licensee: 'ACME', email: 'ops@acme.com', seats: 1, months: 0 }, { keysDir: dir, now }))
  assert.throws(() => mint({ licensee: 'ACME', email: 'ops@acme.com', seats: 1, months: 1.5 }, { keysDir: dir, now }))
  assert.throws(() => mint({ licensee: 'ACME', email: 'ops@acme.com', seats: 0, months: 1 }, { keysDir: dir, now }))
})

test('sendEmail() flags a transport-level failure instead of throwing (no network touched)', async () => {
  const rejectingFetch = async () => { throw new Error('getaddrinfo ENOTFOUND api.resend.com') }

  const result = await sendEmail(
    { token: 'CONCENTUS.fake.token', licensee: 'ACME S.L.' },
    { email: 'ops@acme.com', fetchImpl: rejectingFetch },
  )

  assert.equal(result.ok, false)
  assert.equal(result.status, null)
  assert.match(result.body, /ENOTFOUND/)
})

test('sendEmail() flags a non-2xx HTTP response as failure too (no network touched)', async () => {
  const failingFetch = async () => ({
    ok: false,
    status: 422,
    text: async () => 'invalid `from` address',
  })

  const result = await sendEmail(
    { token: 'CONCENTUS.fake.token', licensee: 'ACME S.L.' },
    { email: 'ops@acme.com', fetchImpl: failingFetch },
  )

  assert.equal(result.ok, false)
  assert.equal(result.status, 422)
  assert.match(result.body, /invalid/)
})

test('sendEmail() reports success on a 2xx response (no network touched)', async () => {
  const okFetch = async () => ({ ok: true, status: 200 })

  const result = await sendEmail(
    { token: 'CONCENTUS.fake.token', licensee: 'ACME S.L.' },
    { email: 'ops@acme.com', fetchImpl: okFetch },
  )

  assert.equal(result.ok, true)
})
