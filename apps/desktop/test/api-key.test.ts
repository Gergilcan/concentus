import * as fs from 'node:fs'
import * as path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeDir, scratchDir } from './helpers'

/**
 * api-key.ts: a credential that can spend money, kept in the keyring where there is one and in
 * an honestly-labelled file where there is not.
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

import { hasApiKey, loadApiKey, saveApiKey } from '../src/api-key'

const KEY = 'sk-ant-api03-example-key'
const keyFile = () => path.join(mocks.dir, 'anthropic.key')

beforeEach(() => {
  mocks.dir = scratchDir('api-key')
  mocks.safeStorage.isEncryptionAvailable.mockReturnValue(true)
})

afterEach(() => removeDir(mocks.dir))

describe('saveApiKey', () => {
  it('rejects anything that does not look like an Anthropic key, before writing a byte', () => {
    const result = saveApiKey('ghp_notananthropickey')

    expect(result.ok).toBe(false)
    expect(result.detail).toContain('begin with "sk-ant-"')
    expect(fs.existsSync(keyFile())).toBe(false)
  })

  it('stores it through the OS keyring when there is one', () => {
    const result = saveApiKey(`  ${KEY}\n`)

    expect(result).toEqual({ ok: true, detail: 'Saved to your operating system\'s keyring.' })
    expect(mocks.safeStorage.encryptString).toHaveBeenCalledWith(KEY)
    expect(fs.readFileSync(keyFile(), 'utf8')).toBe(Buffer.from(`enc(${KEY})`).toString('base64'))
    expect(loadApiKey()).toBe(KEY)
  })

  it('without a keyring, writes a marked file and says so — out loud, in the result and the log', () => {
    mocks.safeStorage.isEncryptionAvailable.mockReturnValue(false)

    const result = saveApiKey(KEY)

    expect(result.ok).toBe(true)
    expect(result.detail).toContain('this machine has no keyring')
    expect(result.detail).toContain(keyFile())
    expect(fs.readFileSync(keyFile(), 'utf8')).toBe(`plain:${KEY}`)
    expect(mocks.safeStorage.encryptString).not.toHaveBeenCalled()
    expect(mocks.log.warn).toHaveBeenCalledWith('No OS keyring available; the API key was written as a file readable by this user.')
  })

  it('given nothing, removes the stored key — and is fine when there was none', () => {
    saveApiKey(KEY)

    expect(saveApiKey('')).toEqual({ ok: true, detail: 'Removed. Runs go back to your Claude subscription.' })
    expect(fs.existsSync(keyFile())).toBe(false)
    expect(saveApiKey(null).ok).toBe(true)
    expect(saveApiKey(undefined).ok).toBe(true)
  })
})

describe('loadApiKey', () => {
  it('is null when none is stored, or the file is empty', () => {
    expect(loadApiKey()).toBeNull()
    fs.writeFileSync(keyFile(), '  \n')
    expect(loadApiKey()).toBeNull()
  })

  it('reads the fallback file without asking the keyring', () => {
    fs.writeFileSync(keyFile(), `plain:${KEY}`)

    expect(loadApiKey()).toBe(KEY)
    expect(mocks.safeStorage.decryptString).not.toHaveBeenCalled()
  })

  it('a key encrypted by a keyring that no longer answers is said out loud, not reported as "none"', () => {
    saveApiKey(KEY)
    mocks.safeStorage.isEncryptionAvailable.mockReturnValue(false)

    expect(loadApiKey()).toBeNull()

    expect(mocks.log.warn).toHaveBeenCalledWith('An API key is stored but the OS keyring cannot open it. Enter it again to replace it.')
    expect(mocks.safeStorage.decryptString).not.toHaveBeenCalled()
  })

  it('a decryption failure is a warning and null, never a throw', () => {
    saveApiKey(KEY)
    mocks.safeStorage.decryptString.mockImplementationOnce(() => { throw new Error('bad key') })

    expect(loadApiKey()).toBeNull()
    expect(mocks.log.warn).toHaveBeenCalledWith('Could not read the stored API key: bad key')
  })
})

describe('hasApiKey', () => {
  it('answers from the file alone, without decrypting anything', () => {
    expect(hasApiKey()).toBe(false)
    saveApiKey(KEY)
    mocks.safeStorage.decryptString.mockClear()

    expect(hasApiKey()).toBe(true)
    expect(mocks.safeStorage.decryptString).not.toHaveBeenCalled()
  })
})
