import { safeStorage } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { dataDir } from './paths'
import { log } from './log'

/**
 * The registration token that lets this machine act as a runner for a Concentus server.
 *
 * <p>Minted on the server, shown there exactly once, and from then on the only thing that proves
 * to the server which machine is dialing in. Whoever holds it can register as this runner and be
 * handed flows to execute, so it is stored the way the Anthropic API key is (api-key.ts): the OS
 * keyring where there is one, and where there is not — a Linux box with no keyring service — a
 * file only this account can read, marked as such and said out loud in the log rather than
 * quietly done. Refusing the machines without a keyring would refuse exactly the servers and
 * containers most likely to be runners.
 *
 * <p>The server URL it pairs with is an address, not a secret, and lives in the settings file.
 */

const FILE = () => path.join(dataDir(), 'runner.token')

/** How a token is marked when the keyring was unavailable and it had to be stored as a file. */
const PLAINTEXT_PREFIX = 'plain:'

/** What the server mints: `crn_` and forty characters. Anything else is a paste of the wrong thing. */
const TOKEN_PREFIX = 'crn_'
const TOKEN_LENGTH = 44

/** The token this installation should register with, or null when there is none. */
export function loadRunnerToken(): string | null {
  try {
    const file = FILE()
    if (!fs.existsSync(file)) return null
    const stored = fs.readFileSync(file, 'utf8').trim()
    if (!stored) return null
    if (stored.startsWith(PLAINTEXT_PREFIX)) return stored.slice(PLAINTEXT_PREFIX.length)
    if (!safeStorage.isEncryptionAvailable()) {
      // Encrypted by a keyring that is no longer answering. Saying so beats returning null, which
      // the tray would show as "not set up" to somebody who very much did set it up.
      log.warn('A runner token is stored but the OS keyring cannot open it. Connect again to replace it.')
      return null
    }
    return safeStorage.decryptString(Buffer.from(stored, 'base64')) || null
  } catch (err) {
    log.warn(`Could not read the stored runner token: ${err instanceof Error ? err.message : String(err)}`)
    return null
  }
}

/** Whether one is stored, without decrypting it — for a screen that shows state, not the value. */
export function hasRunnerToken(): boolean {
  try {
    const file = FILE()
    return fs.existsSync(file) && fs.readFileSync(file, 'utf8').trim().length > 0
  } catch {
    return false
  }
}

export interface SaveResult {
  ok: boolean
  /** What happened, including the honest note when it could not be encrypted. */
  detail: string
}

/**
 * Stores a token, or removes it when given nothing.
 *
 * <p>The shape is checked before anything is written. A token that is not `crn_` plus forty
 * characters was not minted by a Concentus server — a truncated paste, or the API key pasted in
 * the wrong box — and finding that out here costs a sentence, while finding it out later costs a
 * backend that restarts, dials the server, is refused at the handshake and shows "not connected"
 * with a reason the person has to go and read.
 */
export function saveRunnerToken(token: string | null | undefined): SaveResult {
  const value = (token ?? '').trim()
  const file = FILE()

  if (!value) {
    try {
      if (fs.existsSync(file)) fs.rmSync(file)
      log.info('Removed the stored runner token.')
      return { ok: true, detail: 'Removed. This machine no longer registers with a server.' }
    } catch (err) {
      return { ok: false, detail: `Could not remove it: ${err instanceof Error ? err.message : String(err)}` }
    }
  }

  if (!value.startsWith(TOKEN_PREFIX) || value.length !== TOKEN_LENGTH) {
    return {
      ok: false,
      detail: 'That does not look like a runner registration token — they begin with "crn_" and are '
        + `${TOKEN_LENGTH} characters long. Copy it from the server's Runners screen, where it is shown once.`,
    }
  }

  try {
    if (safeStorage.isEncryptionAvailable()) {
      fs.writeFileSync(file, safeStorage.encryptString(value).toString('base64'), { mode: 0o600 })
      log.info('Stored the runner token in the OS keyring.')
      return { ok: true, detail: 'Saved to your operating system\'s keyring.' }
    }
    fs.writeFileSync(file, PLAINTEXT_PREFIX + value, { mode: 0o600 })
    log.warn('No OS keyring available; the runner token was written as a file readable by this user.')
    return {
      ok: true,
      detail: 'Saved — but this machine has no keyring, so it is a file only your account can read '
        + `(${file}). Treat that file as the token itself.`,
    }
  } catch (err) {
    return { ok: false, detail: `Could not save it: ${err instanceof Error ? err.message : String(err)}` }
  }
}
