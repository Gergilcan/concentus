#!/usr/bin/env node
/**
 * Prints a fresh Ed25519 keypair for the TEAM tier, in the two shapes the two sides take, and
 * writes nothing anywhere:
 *
 *   node apps/website/scripts/keygen.mjs
 *
 * The private half goes into Vercel as TEAM_SIGNING_KEY; the public half goes into
 * apps/backend/src/main/resources/application.properties as license.team-public-key. Until both
 * are in place the tier is off on both sides — the site cannot mint, the app does not accept.
 *
 * Why a third key rather than the individual one: the individual key is in Vercel too, but a
 * license it signs is worth nothing, so its compromise costs nothing. A team license is worth
 * money; giving it its own key means a leak can be rotated (new pair here, both sides updated,
 * old team licenses re-issued) without touching the free tier — and the enterprise key stays
 * where it always was, on the author's machine only. Nothing on Vercel can sign enterprise.
 */
import { generateKeypair } from '../api/_lib/license-format.mjs'

const kp = generateKeypair()

console.log('TEAM_SIGNING_KEY  (Vercel > Settings > Environment Variables; the PRIVATE half, PKCS8 DER, base64):')
console.log(kp.privateKeyPkcs8Base64)
console.log()
console.log('license.team-public-key  (application.properties; the PUBLIC half, SPKI DER, base64):')
console.log(kp.publicKeySpkiBase64)
console.log()
console.log('Nothing was written to disk. Paste both, then clear this terminal — the private line must')
console.log('never land in a file inside any repository, and this script will not generate the same pair twice.')
