/**
 * The one definition of a Concentus license, shared by everything that MINTS one — the Vercel
 * function (individual) and the local CLI (enterprise). The backend re-implements verification
 * in Java on purpose: it must not trust this code, only the format.
 *
 * Token shape: CONCENTUS.<base64url(JSON payload)>.<base64url(Ed25519 signature)>
 * The signature is over the ASCII bytes of the payload SEGMENT — the base64url string itself —
 * so verification never depends on re-serializing JSON identically.
 */
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto'
import { randomUUID } from 'node:crypto'

export function generateKeypair() {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519')
  return {
    publicKeyPem: publicKey.export({ type: 'spki', format: 'pem' }).toString(),
    privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    // What the Java side embeds: the DER SPKI bytes, plain base64.
    publicKeySpkiBase64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  }
}

export function signLicense(payload, privateKeyPem) {
  const full = { v: 1, id: randomUUID(), ...payload }
  const segment = Buffer.from(JSON.stringify(full)).toString('base64url')
  const signature = sign(null, Buffer.from(segment, 'ascii'), createPrivateKey(privateKeyPem))
  return `CONCENTUS.${segment}.${signature.toString('base64url')}`
}

export function verifyLicense(token, publicKeyPemByTier) {
  const parts = String(token).trim().split('.')
  if (parts.length !== 3 || parts[0] !== 'CONCENTUS') throw new Error('not a Concentus license')
  const [, segment, sigB64] = parts
  let payload
  try {
    payload = JSON.parse(Buffer.from(segment, 'base64url').toString())
  } catch {
    throw new Error('license payload is not readable')
  }
  const pem = publicKeyPemByTier[payload.tier]
  if (!pem) throw new Error(`unknown license tier: ${payload.tier}`)
  const ok = verify(null, Buffer.from(segment, 'ascii'), createPublicKey(pem), Buffer.from(sigB64, 'base64url'))
  if (!ok) throw new Error('license signature does not verify for its tier')
  return payload
}
