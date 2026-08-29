import { describe, expect, it } from 'vitest'
import { PLAINTEXT_PREFIX, isValidKey, resolveDataKey, type DataKeyPorts } from './data-key'

/**
 * The decision that picks the credential key, against a faked keyring and disk.
 *
 * What it must never do is the reason it is tested: mint a key over one it cannot read (the only
 * copy of what opens every credential), or hand the backend a key it failed to store (rows sealed
 * under a key that exists only in memory). Everything else is the happy path.
 */

const KEY = Buffer.alloc(32, 7).toString('base64')
const OTHER = Buffer.alloc(32, 9).toString('base64')

function ports(over: Partial<DataKeyPorts> & { file?: Buffer | null }): DataKeyPorts & { written: (Buffer | string)[] } {
  const written: (Buffer | string)[] = []
  const stored = over.file ?? null
  return {
    envKey: undefined,
    readFile: () => stored,
    keyringAvailable: true,
    unwrap: (blob) => blob.toString('utf8').replace(/^wrapped:/, ''),
    wrap: (key) => Buffer.from('wrapped:' + key),
    writeFile: (contents) => void written.push(contents),
    generate: () => OTHER,
    written,
    ...over,
  }
}

describe('resolveDataKey', () => {
  it('generates a key once and keeps it in the keyring', () => {
    const p = ports({})

    const decision = resolveDataKey(p)

    expect(decision).toEqual({ key: OTHER, source: 'keyring', created: true })
    expect(p.written).toEqual([Buffer.from('wrapped:' + OTHER)])
  })

  it('reads the key back through the keyring on later launches', () => {
    const p = ports({ file: Buffer.from('wrapped:' + KEY) })

    expect(resolveDataKey(p)).toEqual({ key: KEY, source: 'keyring', created: false })
    expect(p.written).toEqual([])
  })

  // No keyring service — a normal Linux server. A file only this account can read, marked so
  // the next launch knows not to hand it to a keyring, and the caller says so out loud.
  it('falls back to an owner-only file when there is no keyring, and reads it back', () => {
    const first = ports({ keyringAvailable: false })
    const decision = resolveDataKey(first)
    expect(decision).toEqual({ key: OTHER, source: 'file', created: true })
    expect(first.written).toEqual([PLAINTEXT_PREFIX + OTHER])

    const later = ports({ keyringAvailable: false, file: Buffer.from(PLAINTEXT_PREFIX + OTHER) })
    expect(resolveDataKey(later)).toEqual({ key: OTHER, source: 'file', created: false })
  })

  it('takes CONCENTUS_SECRET_KEY from the environment over anything stored, and writes nothing', () => {
    const p = ports({ envKey: ` ${KEY} `, file: Buffer.from('wrapped:' + OTHER) })

    expect(resolveDataKey(p)).toEqual({ key: KEY, source: 'environment', created: false })
    expect(p.written).toEqual([])
  })

  it('ignores a malformed environment key rather than stopping on it', () => {
    const p = ports({ envKey: 'not-a-key', file: Buffer.from('wrapped:' + KEY) })

    expect(resolveDataKey(p)).toEqual({ key: KEY, source: 'keyring', created: false })
  })

  /**
   * The rule that matters most. A stored key the keyring will not open is NOT replaced: the file
   * is the only thing that can open every credential already sealed, and the keyring may be back
   * tomorrow. This launch simply runs without a key.
   */
  it('never overwrites a stored key it cannot open', () => {
    const refusing = ports({
      file: Buffer.from('wrapped:' + KEY),
      unwrap: () => { throw new Error('DPAPI: access denied') },
    })
    const decision = resolveDataKey(refusing)
    expect(decision.key).toBeNull()
    expect(decision).toMatchObject({ reason: 'unreadable' })
    expect((decision as { detail: string }).detail).toContain('access denied')
    expect(refusing.written).toEqual([])

    const noKeyring = ports({ file: Buffer.from('wrapped:' + KEY), keyringAvailable: false })
    expect(resolveDataKey(noKeyring)).toMatchObject({ key: null, reason: 'unreadable' })
    expect(noKeyring.written).toEqual([])
  })

  // A key that only exists in memory must not reach the backend: it would seal rows nothing can
  // ever open again.
  it('does not hand over a key it could not store', () => {
    const p = ports({ writeFile: () => { throw new Error('EROFS') } })

    expect(resolveDataKey(p)).toMatchObject({ key: null, reason: 'unwritable' })
  })

  it('accepts exactly 32 base64 bytes and nothing else', () => {
    expect(isValidKey(KEY)).toBe(true)
    expect(isValidKey(Buffer.alloc(16).toString('base64'))).toBe(false)
    expect(isValidKey('')).toBe(false)
    expect(isValidKey('not base64!')).toBe(false)
  })
})
