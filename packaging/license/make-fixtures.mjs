#!/usr/bin/env node
/**
 * Regenerates the committed TEST fixtures: throwaway keypairs and three licenses signed with
 * them, used by the Java interop tests. These keys sign nothing real — the point is that a
 * token minted by the Node side verifies in the Java parser byte for byte.
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
const licenses = {
  'individual-test.license': signLicense(
    { tier: 'individual', licensee: 'Test Person', email: 'test@example.com', issued: '2026-08-22', expires: null, id: 'fixture-individual' }, ind.privateKeyPem),
  'enterprise-test.license': signLicense(
    { tier: 'enterprise', licensee: 'Test Corp', email: 'ops@example.com', seats: 5, issued: '2026-08-22', expires: '2099-01-01', id: 'fixture-enterprise' }, ent.privateKeyPem),
  'enterprise-expired-test.license': signLicense(
    { tier: 'enterprise', licensee: 'Test Corp', email: 'ops@example.com', seats: 5, issued: '2020-01-01', expires: '2020-06-01', id: 'fixture-expired' }, ent.privateKeyPem),
}
const keys = JSON.stringify({
  individual: { publicKeySpkiBase64: ind.publicKeySpkiBase64 },
  enterprise: { publicKeySpkiBase64: ent.publicKeySpkiBase64 },
}, null, 2) + '\n'

for (const dir of [out, backendTest]) {
  fs.writeFileSync(path.join(dir, 'test-keys.json'), keys)
  for (const [name, token] of Object.entries(licenses)) fs.writeFileSync(path.join(dir, name), token + '\n')
}
console.log(`fixtures written to ${out} and ${backendTest}`)
