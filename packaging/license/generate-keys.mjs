#!/usr/bin/env node
/**
 * Generates the two REAL signing keypairs, once. Writes private keys to the directory given as
 * the only argument — which must be OUTSIDE any repository — and prints the two public SPKI
 * base64 strings, which are what gets pasted into the backend's LicenseKeys.java and nothing else.
 *
 *   node packaging/license/generate-keys.mjs ~/concentus-keys
 *
 * The individual private key additionally goes into Vercel as env LICENSE_KEY_INDIVIDUAL (the
 * PEM, verbatim). The enterprise private key never leaves the machine.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { generateKeypair } from '../../apps/website/api/_lib/license-format.mjs'

const dir = process.argv[2]
if (!dir) { console.error('usage: generate-keys.mjs <directory-outside-any-repo>'); process.exit(1) }
fs.mkdirSync(dir, { recursive: true })
for (const tier of ['individual', 'enterprise']) {
  const file = path.join(dir, `${tier}.key.pem`)
  if (fs.existsSync(file)) { console.error(`${file} already exists — refusing to overwrite a signing key.`); process.exit(1) }
  const kp = generateKeypair()
  fs.writeFileSync(file, kp.privateKeyPem, { mode: 0o600 })
  console.log(`${tier}: private key -> ${file}`)
  console.log(`${tier}: public SPKI base64 (for LicenseKeys.java):\n${kp.publicKeySpkiBase64}\n`)
}
