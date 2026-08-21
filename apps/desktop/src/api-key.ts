import { safeStorage } from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { dataDir } from './paths'
import { log } from './log'

/**
 * The Anthropic API key, when somebody chooses to pay per token instead of using a subscription.
 *
 * <p>Concentus runs on the Claude Code login already on the machine, which is the right default
 * and is free of a second bill. It is not everybody's answer: a machine nobody signs into, a
 * container, an account with no subscription, or simply a preference for per-token billing that
 * shows up on an invoice. Until now that meant setting an environment variable — which on a
 * desktop install means editing something the shell computes and the user cannot reach.
 *
 * <p><b>Not in the settings file.</b> The rest of what this shell remembers is a port and a
 * checkbox; this is a credential that can spend money, and a plain JSON file next to them is the
 * wrong shape for it. The OS keyring holds it where one is available.
 *
 * <p>Where it is not — a Linux box with no keyring service, which is a normal server — it is
 * written as a file with a marker saying so, readable by this user only. That is worse, and it is
 * said out loud in the log rather than quietly done: refusing instead would mean the machines that
 * most need an API key are the ones that cannot store one.
 */

const FILE = () => path.join(dataDir(), 'anthropic.key')

/** How a key is marked when the keyring was unavailable and it had to be stored as a file. */
const PLAINTEXT_PREFIX = 'plain:'

/** The key this installation should run with, or null when there is none. */
export function loadApiKey(): string | null {
  try {
    const file = FILE()
    if (!fs.existsSync(file)) return null
    const stored = fs.readFileSync(file, 'utf8').trim()
    if (!stored) return null
    if (stored.startsWith(PLAINTEXT_PREFIX)) return stored.slice(PLAINTEXT_PREFIX.length)
    if (!safeStorage.isEncryptionAvailable()) {
      // Encrypted by a keyring that is no longer answering — a different user, or a service that
      // stopped. Saying so beats returning null, which reads as "you never set one".
      log.warn('An API key is stored but the OS keyring cannot open it. Enter it again to replace it.')
      return null
    }
    return safeStorage.decryptString(Buffer.from(stored, 'base64')) || null
  } catch (err) {
    log.warn(`Could not read the stored API key: ${err instanceof Error ? err.message : String(err)}`)
    return null
  }
}

/** Whether one is stored, without decrypting it — for a screen that shows state, not the value. */
export function hasApiKey(): boolean {
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
 * Stores a key, or removes it when given nothing.
 *
 * <p>The shape is checked before it is kept. An Anthropic key starts {@code sk-ant-}; a value that
 * does not is almost always a paste of the wrong thing, and finding that out here costs a sentence
 * while finding it out later costs a failed run with an authentication error nobody can place.
 */
export function saveApiKey(key: string | null | undefined): SaveResult {
  const value = (key ?? '').trim()
  const file = FILE()

  if (!value) {
    try {
      if (fs.existsSync(file)) fs.rmSync(file)
      log.info('Removed the stored Anthropic API key.')
      return { ok: true, detail: 'Removed. Runs go back to your Claude subscription.' }
    } catch (err) {
      return { ok: false, detail: `Could not remove it: ${err instanceof Error ? err.message : String(err)}` }
    }
  }

  if (!value.startsWith('sk-ant-')) {
    return {
      ok: false,
      detail: 'That does not look like an Anthropic API key — they begin with "sk-ant-". '
        + 'Check you pasted the whole thing.',
    }
  }

  try {
    if (safeStorage.isEncryptionAvailable()) {
      fs.writeFileSync(file, safeStorage.encryptString(value).toString('base64'), { mode: 0o600 })
      log.info('Stored the Anthropic API key in the OS keyring.')
      return { ok: true, detail: 'Saved to your operating system\'s keyring.' }
    }
    fs.writeFileSync(file, PLAINTEXT_PREFIX + value, { mode: 0o600 })
    log.warn('No OS keyring available; the API key was written as a file readable by this user.')
    return {
      ok: true,
      detail: 'Saved — but this machine has no keyring, so it is a file only your account can read '
        + `(${file}). Treat that file as the key itself.`,
    }
  } catch (err) {
    return { ok: false, detail: `Could not save it: ${err instanceof Error ? err.message : String(err)}` }
  }
}
