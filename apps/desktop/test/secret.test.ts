import * as fs from 'node:fs'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeDir, scratchDir } from './helpers'

/**
 * secret.ts: the master key older versions encrypted with — read, never created, never deleted.
 */

const mocks = vi.hoisted(() => ({
  dir: '',
  safeStorage: { decryptString: vi.fn(), isEncryptionAvailable: vi.fn(() => true) },
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('electron', () => ({ safeStorage: mocks.safeStorage }))
vi.mock('../src/paths', () => ({ dataDir: () => mocks.dir }))
vi.mock('../src/log', () => ({ log: mocks.log }))

import { legacyMasterSecret } from '../src/secret'

const keyFile = () => path.join(mocks.dir, 'secret.key')

beforeEach(() => {
  mocks.dir = scratchDir('secret')
})

afterEach(() => removeDir(mocks.dir))

describe('legacyMasterSecret', () => {
  it('is null when this installation never had a key — and does not make one', () => {
    expect(legacyMasterSecret()).toBeNull()

    expect(fs.existsSync(keyFile())).toBe(false)
    expect(mocks.safeStorage.decryptString).not.toHaveBeenCalled()
  })

  it('reads a key that had to be stored as a file when the keyring was unavailable', () => {
    fs.writeFileSync(keyFile(), 'plain:0123456789abcdef')

    expect(legacyMasterSecret()).toBe('0123456789abcdef')
    expect(mocks.safeStorage.decryptString).not.toHaveBeenCalled()
  })

  it('asks the OS keyring to open one it encrypted', () => {
    const cipher = Buffer.from('v10-cipher-bytes')
    fs.writeFileSync(keyFile(), cipher)
    mocks.safeStorage.decryptString.mockReturnValue('the-master-key')

    expect(legacyMasterSecret()).toBe('the-master-key')

    const given = mocks.safeStorage.decryptString.mock.calls[0][0] as Buffer
    expect(given.equals(cipher)).toBe(true)
  })

  it('a key the keyring can no longer open is a warning about work to redo — and the file stays', () => {
    fs.writeFileSync(keyFile(), Buffer.from('v10-somebody-elses'))
    mocks.safeStorage.decryptString.mockImplementation(() => { throw new Error('Error while decrypting the ciphertext') })

    expect(legacyMasterSecret()).toBeNull()

    expect(mocks.log.warn).toHaveBeenCalledWith(expect.stringContaining('anything still encrypted must be re-entered'))
    // The only thing that can still open an older backup: not an update's to remove.
    expect(fs.existsSync(keyFile())).toBe(true)
  })
})
