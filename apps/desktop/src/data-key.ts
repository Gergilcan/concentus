/**
 * Deciding which key seals this installation's stored credentials — as a pure function, so the
 * decision can be tested without Electron, a keyring or a disk.
 *
 * <p>The rules, in the order they apply:
 *
 * <ol>
 *   <li><b>An explicit key wins.</b> `CONCENTUS_SECRET_KEY` in the shell's own environment is
 *       used as it is and nothing is written. It is the one way two desktop installs on a shared
 *       database can hold the same key — the keyring-wrapped file below cannot be copied between
 *       machines — and it is the same variable a server sets.</li>
 *   <li><b>A stored key is read, never replaced.</b> A `plain:` file is read directly; anything
 *       else is a keyring-wrapped blob and is opened through the keyring. When the keyring cannot
 *       open it — a different user, a service that stopped — the answer is <em>no key for this
 *       launch</em>, not a new key: writing a fresh one over the file would destroy the only thing
 *       that can open every credential in the database, and the keyring may well be back
 *       tomorrow. The backend runs, rows sealed under the unreadable key show as locked, and new
 *       values go in the clear until the key can be read again, at which point they are sealed.</li>
 *   <li><b>Otherwise a key is generated once</b> and kept in the keyring where there is one, or in
 *       a file only this account can read where there is not — the same fallback `api-key.ts`
 *       makes, for the same reason: a Linux box with no keyring service is a normal server, not a
 *       broken one, and refusing would mean the machines that most need encryption are the ones
 *       that cannot have it. A key that could not be written is not handed to the backend at all:
 *       sealing rows under a key that exists only in memory guarantees losing them.</li>
 * </ol>
 */

/** The prefix marking a key kept as a file because the keyring was unavailable. */
export const PLAINTEXT_PREFIX = 'plain:'

/** Where the key came from, for the log and the setup screen. Never the key itself. */
export type DataKeySource = 'environment' | 'keyring' | 'file'

export type DataKeyDecision =
  | { key: string; source: DataKeySource; created: boolean }
  | { key: null; reason: 'unreadable' | 'unwritable'; detail: string }

/** What the decision needs from the world, narrow enough to fake in a test. */
export interface DataKeyPorts {
  /** `process.env.CONCENTUS_SECRET_KEY`, or undefined. */
  envKey: string | undefined
  /** The stored file's bytes, or null when there is no file. */
  readFile(): Buffer | null
  /** Whether the OS keyring can wrap and unwrap right now. */
  keyringAvailable: boolean
  /** Opens a keyring-wrapped blob. Throws when the keyring will not. */
  unwrap(blob: Buffer): string
  /** Wraps a key for the keyring. */
  wrap(key: string): Buffer
  /** Writes the file, owner-only. Throws when it cannot. */
  writeFile(contents: Buffer | string): void
  /** 32 random bytes from the CSPRNG, base64. */
  generate(): string
}

/** Exactly 32 bytes of base64 — what AES-256 takes and what the backend will accept. */
export function isValidKey(candidate: string | undefined | null): candidate is string {
  if (!candidate) return false
  const trimmed = candidate.trim()
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(trimmed)) return false
  try {
    return Buffer.from(trimmed, 'base64').length === 32
  } catch {
    return false
  }
}

export function resolveDataKey(ports: DataKeyPorts): DataKeyDecision {
  const fromEnv = ports.envKey?.trim()
  if (fromEnv) {
    if (isValidKey(fromEnv)) return { key: fromEnv, source: 'environment', created: false }
    // Malformed is treated as absent rather than as a reason to stop: the backend would refuse
    // it too and run in the clear, and a typo in a variable must not strand a stored key.
  }

  const stored = ports.readFile()
  if (stored !== null) {
    const text = stored.toString('utf8')
    if (text.startsWith(PLAINTEXT_PREFIX)) {
      const key = text.slice(PLAINTEXT_PREFIX.length).trim()
      if (isValidKey(key)) return { key, source: 'file', created: false }
      return { key: null, reason: 'unreadable', detail: 'The stored key file does not hold a usable key.' }
    }
    if (!ports.keyringAvailable) {
      return {
        key: null,
        reason: 'unreadable',
        detail: 'A key is stored, but the OS keyring that wraps it is not available to open it.',
      }
    }
    try {
      const key = ports.unwrap(stored).trim()
      if (isValidKey(key)) return { key, source: 'keyring', created: false }
      return { key: null, reason: 'unreadable', detail: 'The keyring opened the stored key, but it is not a usable key.' }
    } catch (err) {
      return {
        key: null,
        reason: 'unreadable',
        detail: `The OS keyring could not open the stored key: ${err instanceof Error ? err.message : String(err)}`,
      }
    }
  }

  const fresh = ports.generate()
  try {
    if (ports.keyringAvailable) {
      ports.writeFile(ports.wrap(fresh))
      return { key: fresh, source: 'keyring', created: true }
    }
    ports.writeFile(PLAINTEXT_PREFIX + fresh)
    return { key: fresh, source: 'file', created: true }
  } catch (err) {
    return {
      key: null,
      reason: 'unwritable',
      detail: `A key was generated but could not be stored: ${err instanceof Error ? err.message : String(err)}`,
    }
  }
}
