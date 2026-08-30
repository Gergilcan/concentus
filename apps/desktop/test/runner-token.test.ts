import * as fs from 'node:fs'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeDir, scratchDir } from './helpers'

/**
 * runner-token.ts: the credential that registers this machine with a server, kept the way the
 * API key is — in the keyring where there is one, in an honestly-labelled file where there is not.
 */

const mocks = vi.hoisted(() => ({
  dir: '',
  safeStorage: {
    isEncryptionAvailable: vi.fn(() => true),
    encryptString: vi.fn((value: string) => Buffer.from(`enc(${value})`)),
    decryptString: vi.fn((buffer: Buffer) => buffer.toString('utf8').replace(/^enc\((.*)\)$/, '$1')),
  },
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('electron', () => ({ safeStorage: mocks.safeStorage }))
vi.mock('../src/paths', () => ({ dataDir: () => mocks.dir }))
vi.mock('../src/log', () => ({ log: mocks.log }))

import { hasRunnerToken, loadRunnerToken, saveRunnerToken } from '../src/runner-token'

/** `crn_` and forty characters, as the server mints them. */
const TOKEN = 'crn_' + 'a1b2c3d4e5'.repeat(4)
const tokenFile = () => path.join(mocks.dir, 'runner.token')

beforeEach(() => {
  mocks.dir = scratchDir('runner-token')
  mocks.safeStorage.isEncryptionAvailable.mockReturnValue(true)
})

afterEach(() => removeDir(mocks.dir))

describe('saveRunnerToken', () => {
  it('refuses anything that is not crn_ plus forty characters, before writing a byte', () => {
    expect(TOKEN).toHaveLength(44)
    for (const wrong of ['sk-ant-api03-pasted-in-the-wrong-box-xxxxxxxxx', 'crn_tooshort', TOKEN + 'x', 'crn_']) {
      const result = saveRunnerToken(wrong)
      expect(result.ok).toBe(false)
      expect(result.detail).toContain('begin with "crn_"')
      expect(result.detail).toContain('44 characters')
    }
    expect(fs.existsSync(tokenFile())).toBe(false)
  })

  it('stores it through the OS keyring when there is one, and reads it back', () => {
    const result = saveRunnerToken(`  ${TOKEN}\n`)

    expect(result).toEqual({ ok: true, detail: 'Saved to your operating system\'s keyring.' })
    expect(mocks.safeStorage.encryptString).toHaveBeenCalledWith(TOKEN)
    expect(fs.readFileSync(tokenFile(), 'utf8')).toBe(Buffer.from(`enc(${TOKEN})`).toString('base64'))
    expect(loadRunnerToken()).toBe(TOKEN)
  })

  it('without a keyring, writes a marked file and says so — in the result and the log', () => {
    mocks.safeStorage.isEncryptionAvailable.mockReturnValue(false)

    const result = saveRunnerToken(TOKEN)

    expect(result.ok).toBe(true)
    expect(result.detail).toContain('this machine has no keyring')
    expect(result.detail).toContain(tokenFile())
    expect(fs.readFileSync(tokenFile(), 'utf8')).toBe(`plain:${TOKEN}`)
    expect(mocks.safeStorage.encryptString).not.toHaveBeenCalled()
    expect(mocks.log.warn).toHaveBeenCalledWith('No OS keyring available; the runner token was written as a file readable by this user.')
    expect(loadRunnerToken()).toBe(TOKEN)
  })

  it('given nothing, removes the stored token — and is fine when there was none', () => {
    saveRunnerToken(TOKEN)

    expect(saveRunnerToken('')).toEqual({ ok: true, detail: 'Removed. This machine no longer registers with a server.' })
    expect(fs.existsSync(tokenFile())).toBe(false)
    expect(loadRunnerToken()).toBeNull()
    expect(saveRunnerToken(null).ok).toBe(true)
    expect(saveRunnerToken(undefined).ok).toBe(true)
  })
})

describe('loadRunnerToken', () => {
  it('is null when none is stored, or the file is empty', () => {
    expect(loadRunnerToken()).toBeNull()
    fs.writeFileSync(tokenFile(), '  \n')
    expect(loadRunnerToken()).toBeNull()
  })

  it('a token encrypted by a keyring that no longer answers is said out loud, not reported as "none"', () => {
    saveRunnerToken(TOKEN)
    mocks.safeStorage.isEncryptionAvailable.mockReturnValue(false)

    expect(loadRunnerToken()).toBeNull()

    expect(mocks.log.warn).toHaveBeenCalledWith('A runner token is stored but the OS keyring cannot open it. Connect again to replace it.')
    expect(mocks.safeStorage.decryptString).not.toHaveBeenCalled()
  })
})

describe('hasRunnerToken', () => {
  it('answers from the file alone, without decrypting anything', () => {
    expect(hasRunnerToken()).toBe(false)
    saveRunnerToken(TOKEN)
    mocks.safeStorage.decryptString.mockClear()

    expect(hasRunnerToken()).toBe(true)
    expect(mocks.safeStorage.decryptString).not.toHaveBeenCalled()
  })
})
