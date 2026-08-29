import { safeStorage } from 'electron'
import * as crypto from 'node:crypto'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { dataDir } from './paths'
import { log } from './log'
import { resolveDataKey, type DataKeyDecision, type DataKeySource } from './data-key'

/**
 * The key that seals stored credentials, handed to the backend as `CONCENTUS_SECRET_KEY`.
 *
 * Generated once, on the first launch that has nowhere to read one from, and kept in the OS
 * keyring — DPAPI on Windows, Keychain on macOS, libsecret on Linux — so the ciphertext in the
 * database is readable only by this account on this machine. The file under `secret.key` is the
 * keyring's wrapped blob, or, on a machine with no keyring service, the key itself behind a
 * `plain:` marker in a file only this account can read; the setup screen says which.
 *
 * <p><b>Why this is safe to bring back.</b> Credentials were encrypted once before and the key
 * was dropped on purpose: it belonged to a machine while the data belonged to the database, so a
 * reinstall, or a second machine on a shared database, met every row present and none readable —
 * and that surfaced as "not configured" and a run that did nothing. What changed is not the key,
 * it is what happens without it. A row sealed under a key this installation does not have now
 * shows as <em>locked</em> in the credentials list and in the flow doctor, and typing the value
 * again is the whole repair; the app starts and the flows keep their references. So this file can
 * be lost and the outcome is an afternoon of re-entering, never a database nobody can use.
 *
 * <p>Two rules follow from that, both in `data-key.ts`. The stored key is never overwritten: when
 * the keyring cannot open it this launch runs without a key rather than minting a new one over
 * the only copy. And a key that could not be stored is not used: sealing rows under a key that
 * exists only in memory is the one way to guarantee losing them.
 *
 * <p>The same file name earlier versions used, on purpose — the key that sealed their rows is the
 * key this version reads, so an upgrade needs nothing from anyone.
 */

const FILE = () => path.join(dataDir(), 'secret.key')

let decided: DataKeyDecision | null = null

/** The decision for this launch, made once and logged once. */
export function dataKey(): DataKeyDecision {
  if (decided) return decided
  const file = FILE()
  decided = resolveDataKey({
    envKey: process.env.CONCENTUS_SECRET_KEY,
    readFile: () => (fs.existsSync(file) ? fs.readFileSync(file) : null),
    keyringAvailable: safeStorage.isEncryptionAvailable(),
    unwrap: (blob) => safeStorage.decryptString(blob),
    wrap: (key) => safeStorage.encryptString(key),
    writeFile: (contents) => fs.writeFileSync(file, contents, { mode: 0o600 }),
    generate: () => crypto.randomBytes(32).toString('base64'),
  })
  report(decided, file)
  return decided
}

/** What the setup screen shows about the key: its source, or why there is none this launch. */
export interface DataKeyState {
  source: DataKeySource | 'none'
  /** The file involved, for the sentence that tells somebody what to back up. */
  file: string
  detail: string
}

export function dataKeyState(): DataKeyState {
  const decision = dataKey()
  if (decision.key === null) return { source: 'none', file: FILE(), detail: decision.detail }
  return { source: decision.source, file: FILE(), detail: '' }
}

function report(decision: DataKeyDecision, file: string): void {
  if (decision.key === null) {
    log.warn(`No credential key this launch: ${decision.detail} Credentials already encrypted show `
      + 'as locked; new ones are stored as typed until the key can be read.')
    return
  }
  switch (decision.source) {
    case 'environment':
      log.info('Credential key taken from CONCENTUS_SECRET_KEY in the environment.')
      break
    case 'keyring':
      log.info(decision.created
        ? 'Generated the credential key and stored it in the OS keyring.'
        : 'Credential key read from the OS keyring.')
      break
    case 'file':
      // Said out loud, every launch: this file IS every stored credential, and the person backing
      // up the data directory should know it belongs in the backup.
      log.warn(`${decision.created ? 'Generated the credential key; no' : 'Credential key read from a file: no'} `
        + `OS keyring is available, so it is kept at ${file}, readable by this account only. `
        + 'Treat that file as the credentials themselves.')
      break
  }
}
