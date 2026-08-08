import { safeStorage } from 'electron'
import * as crypto from 'node:crypto'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { dataDir } from './paths'
import { log } from './log'

/**
 * The master key the backend encrypts stored credentials with (`app.secret-key`).
 *
 * On a server this is an environment variable an operator sets. There is no operator here, and
 * asking a desktop user to run `openssl rand -base64 32` before the app will start is not a real
 * option — so the shell generates one on first launch and keeps it for every launch after.
 *
 * Where it is kept matters, because this key is what stands between a stolen copy of the app-data
 * folder and every mailbox password and API token in it. The OS keyring (Keychain / DPAPI /
 * libsecret, via Electron's safeStorage) is the right home: the ciphertext on disk is then only
 * readable by this user on this machine.
 */

const FILE = () => path.join(dataDir(), 'secret.key')
/** Marks a file we could not encrypt, so a later launch on a working keyring can upgrade it. */
const PLAINTEXT_PREFIX = 'plain:'

function generate(): string {
  // 32 bytes, base64 — the size and encoding the backend documents for CONCENTUS_SECRET_KEY.
  return crypto.randomBytes(32).toString('base64')
}

function readStored(): string | null {
  const file = FILE()
  if (!fs.existsSync(file)) return null
  const raw = fs.readFileSync(file)
  const text = raw.toString('utf8')

  if (text.startsWith(PLAINTEXT_PREFIX)) {
    return text.slice(PLAINTEXT_PREFIX.length)
  }
  try {
    return safeStorage.decryptString(raw)
  } catch (err) {
    // Losing this key makes every stored credential unreadable, so be loud rather than quietly
    // generating a new one — the user needs to know why their mailbox passwords stopped working.
    log.error('Stored secret key could not be decrypted. Saved credentials will need re-entering', err)
    return null
  }
}

function writeStored(secret: string): void {
  const file = FILE()
  fs.mkdirSync(path.dirname(file), { recursive: true })

  if (safeStorage.isEncryptionAvailable()) {
    fs.writeFileSync(file, safeStorage.encryptString(secret), { mode: 0o600 })
    return
  }
  // A Linux box with no keyring daemon is a real configuration, not a broken one. Refusing to
  // start there would be worse than this, but it is a genuine downgrade and is logged as one:
  // the key sits in a file readable by this user, protected by nothing but file permissions.
  log.warn('OS keyring unavailable — storing the master key as a permission-protected file instead.')
  fs.writeFileSync(file, PLAINTEXT_PREFIX + secret, { mode: 0o600 })
}

/** The key for this installation, generating and persisting one on first launch. */
export function masterSecret(): string {
  const existing = readStored()
  if (existing) return existing

  const secret = generate()
  writeStored(secret)
  log.info('Generated a new master key for stored credentials.')
  return secret
}
