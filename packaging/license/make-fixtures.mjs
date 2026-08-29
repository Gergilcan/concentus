#!/usr/bin/env node
/**
 * Regenerates the committed TEST fixtures: throwaway keypairs, one per tier, and the licenses
 * signed with them, used by the Java interop tests. These keys sign nothing real — the point is
 * that a token minted by the Node side verifies in the Java parser byte for byte.
 *
 * Every run replaces ALL the keys and ALL the tokens together: a fixture signed by a previous
 * run's key would fail against this run's test-keys.json, so there is no adding one file by hand.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { generateKeypair, signLicense } from '../../apps/website/api/_lib/license-format.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const out = path.join(here, 'fixtures')
const backendTest = path.join(here, '..', '..', 'apps', 'backend', 'src', 'test', 'resources', 'license')
fs.mkdirSync(out, { recursive: true })
fs.mkdirSync(backendTest, { recursive: true })

const ind = generateKeypair()
const ent = generateKeypair()
const team = generateKeypair()
const licenses = {
  'individual-test.license': signLicense(
    { tier: 'individual', licensee: 'Test Person', email: 'test@example.com', issued: '2026-08-22', expires: null, id: 'fixture-individual' }, ind.privateKeyPem),
  'enterprise-test.license': signLicense(
    { tier: 'enterprise', licensee: 'Test Corp', email: 'ops@example.com', seats: 5, issued: '2026-08-22', expires: '2099-01-01', id: 'fixture-enterprise' }, ent.privateKeyPem),
  'enterprise-expired-test.license': signLicense(
    { tier: 'enterprise', licensee: 'Test Corp', email: 'ops@example.com', seats: 5, issued: '2020-01-01', expires: '2020-06-01', id: 'fixture-expired' }, ent.privateKeyPem),
  // No `seats` at all — a hand-minted or otherwise malformed enterprise token, not one
  // mint-license.mjs would ever produce (it requires --seats). Exercises LicenseService#seatLimit
  // clamping a seatless active enterprise license to one rather than NPEing at the unboxing call
  // sites (AccountController, OidcSignIn) that treat it as a plain int.
  'enterprise-no-seats-test.license': signLicense(
    { tier: 'enterprise', licensee: 'Test Corp', email: 'ops@example.com', issued: '2026-08-22', expires: '2099-01-01', id: 'fixture-enterprise-no-seats' }, ent.privateKeyPem),

  // The team tier: what the website's Stripe webhook mints. Signed by its own key, so the two
  // "signed by the wrong tier's key" fixtures below are how the Java side proves it cross-checks
  // tier against key for this tier too.
  'team-test.license': signLicense(
    { tier: 'team', licensee: 'Test Team', email: 'team@example.com', seats: 3, issued: '2026-08-22', expires: '2099-01-01', id: 'fixture-team' }, team.privateKeyPem),
  'team-expired-test.license': signLicense(
    { tier: 'team', licensee: 'Test Team', email: 'team@example.com', seats: 3, issued: '2020-01-01', expires: '2020-06-01', id: 'fixture-team-expired' }, team.privateKeyPem),
  // Genuinely signed, and still refused: the verifier's ceiling is what makes a key that lives in
  // Vercel acceptable — the most it can sign is a small, expiring license.
  'team-eleven-seats-test.license': signLicense(
    { tier: 'team', licensee: 'Test Team', email: 'team@example.com', seats: 11, issued: '2026-08-22', expires: '2099-01-01', id: 'fixture-team-eleven' }, team.privateKeyPem),
  'team-perpetual-test.license': signLicense(
    { tier: 'team', licensee: 'Test Team', email: 'team@example.com', seats: 3, issued: '2026-08-22', expires: null, id: 'fixture-team-perpetual' }, team.privateKeyPem),
  'team-signed-by-individual-test.license': signLicense(
    { tier: 'team', licensee: 'Test Team', email: 'team@example.com', seats: 3, issued: '2026-08-22', expires: '2099-01-01', id: 'fixture-team-wrong-key-ind' }, ind.privateKeyPem),
  'team-signed-by-enterprise-test.license': signLicense(
    { tier: 'team', licensee: 'Test Team', email: 'team@example.com', seats: 3, issued: '2026-08-22', expires: '2099-01-01', id: 'fixture-team-wrong-key-ent' }, ent.privateKeyPem),
  // What the trial form issues: a team license with `trial: true` and fourteen days on it. The
  // flag is what the Settings screen counts down from; to every gate it is a team license.
  'team-trial-test.license': signLicense(
    { tier: 'team', licensee: 'Trial Team', email: 'trial@example.com', seats: 3, issued: '2026-08-22', expires: '2026-09-05', trial: true, id: 'fixture-team-trial' }, team.privateKeyPem),
}
const keys = JSON.stringify({
  individual: { publicKeySpkiBase64: ind.publicKeySpkiBase64 },
  enterprise: { publicKeySpkiBase64: ent.publicKeySpkiBase64 },
  team: { publicKeySpkiBase64: team.publicKeySpkiBase64 },
}, null, 2) + '\n'

for (const dir of [out, backendTest]) {
  fs.writeFileSync(path.join(dir, 'test-keys.json'), keys)
  for (const [name, token] of Object.entries(licenses)) fs.writeFileSync(path.join(dir, name), token + '\n')
}
console.log(`fixtures written to ${out} and ${backendTest}`)
